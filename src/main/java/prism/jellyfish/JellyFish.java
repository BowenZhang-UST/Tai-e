package prism.jellyfish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.cfg.CFGEdge;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.proginfo.ExceptionEntry;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.*;
import pascal.taie.language.type.*;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.collection.Pair;
import prism.jellyfish.synthesis.JellyClass;
import prism.jellyfish.synthesis.JellyMethod;
import prism.jellyfish.synthesis.SynthesisResult;
import prism.jellyfish.util.AssertUtil;
import prism.jellyfish.util.JavaUtil;
import prism.jellyfish.util.StringUtil;
import prism.llvm.LLVMCodeGen;
import prism.llvm.LLVMUtil;

import javax.annotation.Nullable;
import java.io.*;
import java.util.*;

import static prism.llvm.LLVMUtil.*;


public class JellyFish extends ProgramAnalysis<Void> {
    public static final String ID = "jelly-fish";
    private static final Logger logger = LogManager.getLogger(JellyFish.class);
    private static final AssertUtil as = new AssertUtil(logger);

    private static final Level DEBUG_LEVEL = Level.DEBUG;

    World world;
    ClassHierarchy classHierarchy;
    LLVMCodeGen codeGen;
    Mappings maps;
    AnalysisConfig config;
    SynthesisResult synRes;


    public JellyFish(AnalysisConfig config) {
        super(config);
        this.config = config;
        this.world = World.get();
        this.classHierarchy = world.getClassHierarchy();
        this.codeGen = new LLVMCodeGen();
        this.maps = new Mappings();
        Configurator.setAllLevels(LogManager.getRootLogger().getName(), DEBUG_LEVEL);
    }

    /*
    Entry Point
     */
    @Override
    public Void analyze() {
        logger.info("Jellyfish is a transpiler from Tai-e IR to LLVM IR.");
        logger.info("Phase 1: synthesize layout.");
        synthesizeLayout();
        logger.info("Phase 2: translate the classes.");
        translateClasses();
        logger.info("Phase 3: apply optimization and generate bitcode.");
        generateLLVMBitcode();
        return null;
    }

    private boolean _persistClassInfo() {
        List<JClass> jclasses = classHierarchy.allClasses().toList();
        List<JellyClass> jellyClasses = new ArrayList<>();
        for (JClass jclass : jclasses) {
            List<String> callableSigs = JavaUtil.getCallableSignatures(jclass);
            List<JMethod> ownedMethods = JavaUtil.getOwnedMethods(classHierarchy, jclass);
            String kindName = jclass.isInterface() ? "interface" : "class";
            String className = jclass.getName();
            String superName = jclass.getSuperClass() == null ? "" : jclass.getSuperClass().getName();
            List<String> interfaces = jclass.getInterfaces().stream().map(JClass::getName).toList();
            List<JellyMethod> jellyMethods = ownedMethods.stream().map(m -> new JellyMethod(m.getSubsignature().toString(), m.getDeclaringClass().getName(), m.getSignature())).toList();
            JellyClass jellyClass = new JellyClass(kindName, className, superName, callableSigs, interfaces, jellyMethods);
            jellyClasses.add(jellyClass);
        }
        try (FileWriter writer = new FileWriter("output/oo.json")) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(jellyClasses, writer);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean _triggerSynthesizer() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("python3", "chironex/python/main.py", "-i", "output/oo.json", "-o", "output/oo.o.json", "-g", "slot");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info(line);
                }
            }
            int retCode = process.waitFor();
            logger.info("retCode: {}", retCode);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean _loadSynthesisResult() {
        try (FileReader reader = new FileReader("output/oo.o.json")) {
            Gson gson = new GsonBuilder().create();
            synRes = gson.fromJson(reader, SynthesisResult.class);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public void synthesizeLayout() {
        as.assertTrue(_persistClassInfo(), "Failed to persist class info");
//        as.assertTrue(_triggerSynthesizer(), "Failed to perform synthesis");
        as.assertTrue(_loadSynthesisResult(), "Failed to load synthesis result");
    }

    public void translateClasses() {
        List<JClass> jclasses = classHierarchy.applicationClasses().toList();
        for (JClass jclass : jclasses) {
            String className = jclass.getName();
            String moduleName = jclass.getModuleName();
            String simpleName = jclass.getSimpleName();
            // Class
            LLVMTypeRef llvmClass = getOrTranClass(jclass, ClassStatus.DEP_METHOD_DEF);
        }

        while (true) {
            boolean isStable = true;
            for (JClass jclass : maps.getAllClasses()) {
                ClassStatus status = maps.getClassStatusMap(jclass).get();
                if (status == ClassStatus.DEP_DECL) {
                    getOrTranClass(jclass, ClassStatus.DEP_FIELDS);
                    isStable = false;
                }
            }
            if (isStable) {
                break;
            }
        }
    }

    public void generateLLVMBitcode() {
        codeGen.verify();
        codeGen.optimize();
        codeGen.generate();
    }

    /*
     * Mappings between Java and LLVM
     */
    public enum ClassStatus {
        DEP_DECL(0),
        DEP_FIELDS(1),
        DEP_METHOD_DECL(2),
        DEP_METHOD_DEF(3);
        private final int ord;

        ClassStatus(int ord) {
            this.ord = ord;
        }

        public int getOrd() {
            return ord;
        }
    }

    public LLVMTypeRef getOrTranClass(JClass jclass, ClassStatus dep) {
        Optional<LLVMTypeRef> llvmClass = maps.getClassMap(jclass);
        LLVMTypeRef theClass;
        ClassStatus curDep = null;
        if (llvmClass.isPresent()) {
            LLVMTypeRef existClass = llvmClass.get();
            theClass = existClass;

            curDep = maps.getClassStatusMap(jclass).get();
        } else {
            LLVMTypeRef newClass = this.tranClassDecl(jclass);
            boolean ret = maps.setClassMap(jclass, newClass);
            as.assertTrue(ret, "The jclass {} has been duplicate translated.", jclass);
            theClass = newClass;

            ret = maps.setClassStatusMap(jclass, ClassStatus.DEP_DECL);
            as.assertTrue(ret, "The status of jclass {} has been duplicate updated.", jclass);
            curDep = ClassStatus.DEP_DECL;
        }

        for (int i = curDep.getOrd() + 1; i <= dep.getOrd(); i++) {
            if (i == ClassStatus.DEP_DECL.getOrd()) {
                continue;
            } else if (i == ClassStatus.DEP_FIELDS.getOrd()) {
                this.tranClassFields(jclass);
                maps.setClassStatusMap(jclass, ClassStatus.DEP_FIELDS);
            } else if (i == ClassStatus.DEP_METHOD_DECL.getOrd()) {
                Collection<JMethod> methods = jclass.getDeclaredMethods();
                for (JMethod jmethod : methods) {
                    LLVMValueRef llvmMethod = getOrTranMethodDecl(jmethod);
                }
                maps.setClassStatusMap(jclass, ClassStatus.DEP_METHOD_DECL);
            } else if (i == ClassStatus.DEP_METHOD_DEF.getOrd()) {
                Collection<JMethod> methods = jclass.getDeclaredMethods();
                for (JMethod jmethod : methods) {
                    this.tranMethodBody(jmethod);
                }
                maps.setClassStatusMap(jclass, ClassStatus.DEP_METHOD_DEF);
            }
        }
        return theClass;
    }

    public LLVMValueRef getOrTranMethodDecl(JMethod jmethod) {
        Optional<LLVMValueRef> llvmMethod = maps.getMethodMap(jmethod);
        if (llvmMethod.isPresent()) {
            LLVMValueRef existMethod = llvmMethod.get();
            return existMethod;
        } else {
            LLVMValueRef newMethod = this.tranMethodDecl(jmethod);
            return newMethod;
        }
    }

    public LLVMValueRef getOrTranStaticField(JField jfield) {
        requireType(jfield.getDeclaringClass().getType(), ClassStatus.DEP_FIELDS);
        Optional<LLVMValueRef> opfieldPtr = maps.getStaticFieldMap(jfield);
        as.assertTrue(opfieldPtr.isPresent(), "The field {} should have been translated.", jfield);
        return opfieldPtr.get();
    }

    public Integer getOrTranMemberField(JField jfield) {
        requireType(jfield.getDeclaringClass().getType(), ClassStatus.DEP_FIELDS);
        Optional<Integer> opfieldIndex = maps.getMemberFieldMap(jfield);
        as.assertTrue(opfieldIndex.isPresent(), "The field {} should have been translated.", jfield);
        return opfieldIndex.get();
    }

    public LLVMValueRef getOrTranStringLiteral(String str) {
        /*
         * Trans:
         * Java String literal  "XXX"
         * =>
         * 1. (*[i8 x 0]) str = "XXX"
         * 2. (*%java.lang.String) s = malloc(sizeof(%java.lang.String)); // new
         * 3. <init>(s, str);
         */
        JClass stringClass = classHierarchy.getClass("java.lang.String");
        JMethod jinitMethod = stringClass.getDeclaredMethod(Subsignature.get("void <init>(byte[])"));
        as.assertTrue(jinitMethod != null, "String Class should has a init method. Got null.");

        LLVMValueRef llvmStrInitMethod = getOrTranMethodDecl(jinitMethod);

        // 1.
        LLVMValueRef llvmStrConst;
        Optional<LLVMValueRef> opStrVal = maps.getStringPoolMap(str);
        if (opStrVal.isPresent()) {
            // Already in string pool
            llvmStrConst = opStrVal.get();
        } else {
            llvmStrConst = codeGen.buildConstString(str);
            maps.setStringPoolMap(str, llvmStrConst);
        }
        LLVMTypeRef byteArrayType = getValueType(LLVM.LLVMGetParam(llvmStrInitMethod, 1));
        LLVMValueRef llvmStrConst2 = codeGen.buildTypeCast(llvmStrConst, byteArrayType);

        // 2.
        LLVMTypeRef llvmStringClass = tranTypeAlloc(stringClass.getType(), ClassStatus.DEP_FIELDS);
        LLVMValueRef llvmStrPtr = codeGen.buildMalloc(llvmStringClass);

        // 3.
        codeGen.buildCall(llvmStrInitMethod, List.of(llvmStrPtr, llvmStrConst2));
        return llvmStrPtr;
    }


    /*
     * Translations from Java to LLVM
     */

    public LLVMTypeRef tranClassDecl(JClass jclass) {
        String className = StringUtil.getClassName(jclass);

        LLVMTypeRef classType = codeGen.buildNamedStruct(className);

        // A placeholder value to let the type stay in bitcode.
        String placeHolderValName = String.format("placeholder.%s", className);
        LLVMValueRef phValue = codeGen.addGlobalVariable(classType, placeHolderValName);
        LLVM.LLVMSetLinkage(phValue, LLVM.LLVMExternalLinkage);
        return classType;
    }

    public void tranClassFields(JClass jclass) {
        Optional<LLVMTypeRef> opllvmClass = maps.getClassMap(jclass);
        as.assertTrue(opllvmClass.isPresent(), "The class declaration of {} should have been translated.", jclass);
        LLVMTypeRef llvmClass = opllvmClass.get();

        JClass objClass = world.getClassHierarchy().getClass("java.lang.Object");
        LLVMTypeRef llvmObjClass = tranType(objClass.getType(), ClassStatus.DEP_DECL);

        List<LLVMTypeRef> fieldTypes = new ArrayList<>();
        // 1. "super field"
        JClass sclass = jclass.getSuperClass();
        if (sclass != null) {
            LLVMTypeRef llvmSuperClass = tranTypeAlloc(sclass.getType(), ClassStatus.DEP_DECL);
            fieldTypes.add(llvmSuperClass);
        } else {
            fieldTypes.add(llvmObjClass);
        }
        // 2. "interface field"
        Collection<JClass> interfaces = jclass.getInterfaces();
        for (JClass i : interfaces) {
            LLVMTypeRef llvmInterface = tranTypeAlloc(i.getType(), ClassStatus.DEP_DECL);
            maps.setInterfaceIndexMap(jclass, i, fieldTypes.size());
            fieldTypes.add(llvmInterface);
        }

        // 3. Virtual method Fields
        List<JMethod> methods = JavaUtil.getCallableMethodTypes(jclass);
        for (JMethod method : methods) {
            if (synRes.shouldContainSlot(jclass, method)) {
                LLVMTypeRef funcType = tranMethodType(method);
                LLVMTypeRef funcPtrType = codeGen.buildPointerType(funcType);
                boolean ret = maps.setSlotIndexMap(jclass, method.getSubsignature(), fieldTypes.size());
                as.assertTrue(ret, "The slot {} of class {} has been duplicate translated", method.getSubsignature(), jclass);
                fieldTypes.add(funcPtrType);
            }
        }

        // 4. Static and Member Fields
        Collection<JField> fields = jclass.getDeclaredFields();
        for (JField field : fields) {
            String fieldName = field.getName();
            Type ftype = field.getType();
            LLVMTypeRef fllvmType = tranType(ftype, ClassStatus.DEP_DECL);
            if (field.isStatic()) {
                String staticFieldName = StringUtil.getStaticFieldName(jclass, field);
                LLVMValueRef fieldVar = codeGen.addGlobalVariable(fllvmType, staticFieldName);
                boolean ret = maps.setStaticFieldMap(field, fieldVar);
                as.assertTrue(ret, "The jfield {} has been duplicate translated.", field);
                continue;
            } else {
                boolean ret = maps.setMemberFieldMap(field, fieldTypes.size());
                as.assertTrue(ret, "The jfield {} has been duplicate translated", field);
                fieldTypes.add(fllvmType);
            }
        }
        codeGen.setStructFields(llvmClass, fieldTypes);
        return;
    }

    public LLVMTypeRef tranTypeAlloc(Type jType, ClassStatus dep) {
        /*
         * Translate this type for allocation.
         * So the reference type will be not be transformed to pointer.
         */
        LLVMTypeRef type1 = tranType(jType, dep);
        if (jType instanceof ReferenceType) {
            as.assertTrue(LLVM.LLVMGetTypeKind(type1) == LLVM.LLVMPointerTypeKind, "The type should be a pointer type. Got {}", getLLVMStr(type1));
            return LLVM.LLVMGetElementType(type1);
        } else {
            return type1;
        }
    }

    private LLVMTypeRef tranMethodType(JMethod jmethod) {
        JClass jclass = jmethod.getDeclaringClass();
        List<LLVMTypeRef> paramTypes = new ArrayList<>();
        if (!jmethod.isStatic()) {
            ClassType classType = jclass.getType();
            LLVMTypeRef llvmClassType = tranType(classType, ClassStatus.DEP_DECL);
            paramTypes.add(llvmClassType);
        }
        for (Type jType : jmethod.getParamTypes()) {
            LLVMTypeRef type = tranType(jType, ClassStatus.DEP_DECL);
            paramTypes.add(type);
        }
        Type jretType = jmethod.getReturnType();
        LLVMTypeRef retType = tranType(jretType, ClassStatus.DEP_DECL);
        LLVMTypeRef funcType = codeGen.buildFunctionType(retType, paramTypes);
        return funcType;
    }

    public void requireType(Type jType, ClassStatus dep) {
        tranType(jType, dep);
        return;
    }

    public LLVMTypeRef tranType(Type jType, ClassStatus dep) {
        /*
         * Translate the type for variable typing.
         * So the reference type will be automatically transformed to pointer type.
         */
        if (jType instanceof VoidType) {
            LLVMTypeRef llvmVoidType = codeGen.buildVoidType();
            return llvmVoidType;
        } else if (jType instanceof ValueType) {
            as.assertTrue(jType instanceof PrimitiveType, "It should be primitive type");
            if (jType instanceof BooleanType) {
                LLVMTypeRef llvmBooleanType = codeGen.buildIntType(1);
                return llvmBooleanType;
            } else if (jType instanceof ByteType) {
                LLVMTypeRef llvmByteType = codeGen.buildIntType(8);
                return llvmByteType;
            } else if (jType instanceof CharType) {
                LLVMTypeRef llvmCharType = codeGen.buildIntType(16);
                return llvmCharType;
            } else if (jType instanceof ShortType) {
                LLVMTypeRef llvmShortType = codeGen.buildIntType(16);
                return llvmShortType;
            } else if (jType instanceof IntType) {
                LLVMTypeRef llvmIntType = codeGen.buildIntType(32);
                return llvmIntType;
            } else if (jType instanceof LongType) {
                LLVMTypeRef llvmLongType = codeGen.buildIntType(64);
                return llvmLongType;
            } else if (jType instanceof FloatType) {
                LLVMTypeRef llvmFloatType = codeGen.buildFloatType();
                return llvmFloatType;
            } else if (jType instanceof DoubleType) {
                LLVMTypeRef llvmDoubleType = codeGen.buildDoubleType();
                return llvmDoubleType;
            }
        } else if (jType instanceof ReferenceType) {
            if (jType instanceof ArrayType) {
                // Example: A[][] => *[*[i32 * 0] * 0]
                Type jBaseType = ((ArrayType) jType).baseType();
                int dimension = ((ArrayType) jType).dimensions();

                LLVMTypeRef baseType = this.tranType(jBaseType, dep);
                int arraySize = 0; // in java, array sizes are unknown.

                LLVMTypeRef curArrayPtrType = codeGen.buildPointerType(codeGen.buildArrayType(baseType, arraySize));
                for (int d = 1; d < dimension; d++) {
                    curArrayPtrType = codeGen.buildPointerType(codeGen.buildArrayType(curArrayPtrType, arraySize));
                }
                return curArrayPtrType;
            } else if (jType instanceof ClassType) {
                JClass jclass = ((ClassType) jType).getJClass();
                as.assertTrue(dep != null, "The dep should be specified. Got null.");
                LLVMTypeRef llvmStruct = getOrTranClass(jclass, dep);
                LLVMTypeRef llvmStructPtr = codeGen.buildPointerType(llvmStruct);
                return llvmStructPtr;
            } else if (jType instanceof NullType) {
                as.unreachable("We don't translate Null Type");
            }
        } else if (jType instanceof BottomType) {
            as.unimplemented();
        }
        as.unreachable("All types should be considered except: {}", jType);
        return null;
    }

    public LLVMValueRef tranMethodDecl(JMethod jmethod) {
        JClass jclass = jmethod.getDeclaringClass();
        String methodName = StringUtil.getMethodName(jclass, jmethod);
        LLVMTypeRef funcType = tranMethodType(jmethod);

        LLVMValueRef func = codeGen.addFunction(funcType, methodName);
        boolean ret = maps.setMethodMap(jmethod, func);
        as.assertTrue(ret, "The method {} has been duplicate translated.", jmethod);

        return func;
    }

    public void tranMethodBody(JMethod jmethod) {

        Optional<LLVMValueRef> opllvmFunc = maps.getMethodMap(jmethod);
        as.assertTrue(opllvmFunc.isPresent(), "The decl of jmethod {} should have be translated", jmethod);
        LLVMValueRef llvmFunc = opllvmFunc.get();

        IR ir;
        CFG<Stmt> cfg;
        try {
            ir = jmethod.getIR();
            cfg = ir.getResult(CFGBuilder.ID);

            if (cfg == null) {
                maps.clearVarMap();
                maps.clearStmtBlockMap();
                return;
            }
        } catch (AnalysisException e) {
            maps.clearVarMap();
            maps.clearStmtBlockMap();
            return;
        }

        // We create an entry block contains all Tai-e variables,
        // as well as the storing paths of virtual functions (if it's an init function)
        LLVMBasicBlockRef entryBlock = codeGen.addBasicBlock(llvmFunc, "entry");
        codeGen.setInsertBlock(entryBlock);
        List<Var> vars = ir.getVars();
        for (Var var : vars) {
            Type jvarType = var.getType();
            if (jvarType instanceof NullType) continue;
            LLVMTypeRef llvmVarType = tranType(jvarType, ClassStatus.DEP_FIELDS);
            String llvmVarName = StringUtil.getVarNameAsPtr(var);
            LLVMValueRef alloca = codeGen.buildAlloca(llvmVarType, llvmVarName);
            boolean ret = maps.setVarMap(var, alloca);
            as.assertTrue(ret, "The var {} has been duplicate translated.", var);
        }
        if (jmethod.getName().equals("<init>")) {
            JClass jclass = jmethod.getDeclaringClass();
            Var thisVar = ir.getThis();
            LLVMValueRef thisPtr = tranRValue(thisVar, tranType(jclass.getType(), ClassStatus.DEP_METHOD_DECL));
            for (JMethod owned : JavaUtil.getOwnedMethods(classHierarchy, jclass)) {
                Subsignature sig = owned.getSubsignature();
                List<JClass> toStores = synRes.getStoreContainers(jclass, sig, classHierarchy);
                for (JClass toStore : toStores) {
                    requireType(toStore.getType(), ClassStatus.DEP_FIELDS);
                    Optional<Integer> lastIndex = maps.getSlotIndexMap(toStore, sig);
                    as.assertTrue(lastIndex.isPresent(), "The container {} should have a slot for sig {}", toStore.getName(), sig);
                    LLVMValueRef gep = buildGEPtoContainerSlot(jclass, toStore, thisPtr, lastIndex.get());
                    requireType(owned.getDeclaringClass().getType(), ClassStatus.DEP_METHOD_DECL);
                    LLVMValueRef funcPtr = maps.getMethodMap(owned).get();
                    LLVMTypeRef tarFuncPtrType = LLVM.LLVMGetElementType(getValueType(gep));
                    codeGen.buildStore(gep, codeGen.buildTypeCast(funcPtr, tarFuncPtrType));
                }
            }

        }


        // We allocate an exit block for the exit stmt in CFG, which is added by Tai-e.
        Stmt exitStmt = cfg.getExit();
        as.assertTrue(exitStmt instanceof Nop, "The exit stmt is a nop.");
        LLVMBasicBlockRef exitBB = codeGen.addBasicBlock(llvmFunc, "exit");
        maps.setStmtBlockMap(exitStmt, exitBB);

        // Each of the normal blocks contains exactly ONE Tai-e statement
        List<Stmt> jstmts = ir.getStmts();
        for (Stmt jstmt : jstmts) {
            LLVMBasicBlockRef bb = codeGen.addBasicBlock(llvmFunc, "bb");
            maps.setStmtBlockMap(jstmt, bb);
        }
        // Statement translation:
        // In this process,
        // the edges related to control flow stmts will be constructed.
        for (Stmt jstmt : jstmts) {
            LLVMBasicBlockRef bb = maps.getStmtBlockMap(jstmt).get();
            codeGen.setInsertBlock(bb);
            List<LLVMValueRef> llvmInsts = this.tranStmt(jstmt, jmethod, cfg);

            List<String> llvmInstStrs = llvmInsts.stream().map(inst -> getLLVMStr(inst)).toList();
        }
        // The exit stmt is a nop.
        // So we should add an unreachable to the corresponding BB,
        // Such that there is a terminator.
        codeGen.setInsertBlock(exitBB);
        codeGen.buildUnreachable();

        // Handle those basic blocks without a terminator instructions
        for (Stmt jstmt : jstmts) {
            LLVMBasicBlockRef bb = maps.getStmtBlockMap(jstmt).get();
            LLVMValueRef lastInst = LLVM.LLVMGetLastInstruction(bb);
            if (LLVM.LLVMIsATerminatorInst(lastInst) != null) {
                continue;
            }
            Set<CFGEdge<Stmt>> outEdges = cfg.getOutEdgesOf(jstmt);
            if (outEdges.size() == 0) {
                codeGen.setInsertBlock(bb);
                codeGen.buildUnreachable();
                continue;
            }
            for (CFGEdge<Stmt> outEdge : outEdges) {
                CFGEdge.Kind outKind = outEdge.getKind();
                if (outKind == CFGEdge.Kind.FALL_THROUGH) {
                    codeGen.setInsertBlock(bb);
                    Stmt target = outEdge.target();
                    LLVMBasicBlockRef fallthrough = maps.getStmtBlockMap(target).get();
                    codeGen.buildUncondBr(fallthrough);
                } else if (outKind == CFGEdge.Kind.UNCAUGHT_EXCEPTION) {
                    codeGen.setInsertBlock(bb);
                    codeGen.buildUncondBr(exitBB);
                } else if (outKind == CFGEdge.Kind.CAUGHT_EXCEPTION) {
                    codeGen.setInsertBlock(bb);
                    Stmt target = outEdge.target();
                    LLVMBasicBlockRef handler = maps.getStmtBlockMap(target).get();
                    codeGen.buildUncondBr(handler);
                } else {
                    as.unreachable("The other kinds are unexpected. edge: {}. Last Inst: {}", outEdge, getLLVMStr(lastInst));
                }
                break;
            }
        }

        // Handle the exception entries
        HashMap<Stmt, List<Stmt>> end2Handlers = new HashMap<>();
        for (ExceptionEntry ee : ir.getExceptionEntries()) {
            // merge the handlers with the same end stmt
            Stmt endStmt = ir.getStmt(ee.end().getIndex() - 1);

            if (!end2Handlers.containsKey(endStmt)) {
                List<Stmt> handlers = new ArrayList<>();
                end2Handlers.put(endStmt, handlers);
            }
            end2Handlers.get(endStmt).add(ee.handler());
        }
        for (Map.Entry<Stmt, List<Stmt>> entry : end2Handlers.entrySet()) {
            Stmt theEnd = entry.getKey();
            List<Stmt> theHandlers = entry.getValue();
            LLVMBasicBlockRef endBlock = maps.getStmtBlockMap(theEnd).get();
            List<LLVMBasicBlockRef> handlerBlocks = theHandlers.stream().map(h -> maps.getStmtBlockMap(h).get()).toList();
            LLVMValueRef lastInst = LLVM.LLVMGetLastInstruction(endBlock);
            as.assertTrue(LLVM.LLVMIsATerminatorInst(lastInst) != null, "For jstmt {}, the last inst should be a terminator. Got {}", theEnd, getLLVMStr(lastInst));
            // put the original last inst in a new block
            LLVM.LLVMInstructionRemoveFromParent(lastInst);
            LLVMBasicBlockRef bb4LastInst = codeGen.addBasicBlock(llvmFunc, "bb");
            codeGen.setInsertBlock(bb4LastInst);
            codeGen.insertInst(lastInst);

            // create a switch instruction that dispatch the handlers & original target
            codeGen.setInsertBlock(endBlock);
            LLVMValueRef rolldice = codeGen.buildRolldice();
            List<Pair<LLVMValueRef, LLVMBasicBlockRef>> caseTargetPairs = new ArrayList<>();
            for (int i = 0; i < handlerBlocks.size(); i++) {
                LLVMValueRef caseVal = codeGen.buildConstInt(codeGen.buildIntType(32), i);
                LLVMBasicBlockRef handlerBlock = handlerBlocks.get(i);
                caseTargetPairs.add(new Pair<>(caseVal, handlerBlock));
            }
            codeGen.buildSwitch(rolldice, caseTargetPairs, bb4LastInst);
        }

        // connect the entry to the real entry stmt
        codeGen.setInsertBlock(entryBlock);
        Stmt cfgEntry = cfg.getEntry();
        as.assertTrue(cfgEntry instanceof Nop, "It's not real entry.");
        Set<CFGEdge<Stmt>> entryOuts = cfg.getOutEdgesOf(cfgEntry);
        as.assertTrue(entryOuts.size() == 1, "Only one out edge.");
        CFGEdge<Stmt> entryOut = (CFGEdge<Stmt>) entryOuts.toArray()[0];
        as.assertTrue(entryOut.getKind() == CFGEdge.Kind.ENTRY, "Entry kind");
        Stmt entryStmt = entryOut.target();
        if (!(entryStmt instanceof Nop)) {
            LLVMBasicBlockRef stmtEntryBlock = maps.getStmtBlockMap(entryStmt).get();
            codeGen.buildUncondBr(stmtEntryBlock);
        }

        for (Stmt jstmt : jstmts) {
            LLVMBasicBlockRef blockRef = maps.getStmtBlockMap(jstmt).get();
            LLVMValueRef lastOne = LLVM.LLVMGetLastInstruction(blockRef);
            as.assertTrue(lastOne != null, "The translated block is null for stmt {}", jstmt);
            as.assertTrue(LLVM.LLVMIsATerminatorInst(lastOne) != null, "for stmt {}, the last instruction is not terminator. Got: {}", jstmt, LLVMUtil.getLLVMStr(lastOne));

        }

        maps.clearVarMap();
        maps.clearStmtBlockMap();

    }

    @Nullable
    public List<LLVMValueRef> tranStmt(Stmt jstmt, JMethod jmethod, CFG<Stmt> cfg) {
        List<LLVMValueRef> resInsts = new ArrayList<>();
        codeGen.setDebugLocation(jstmt.getLineNumber(), jmethod.getDeclaringClass().getName());
        if (jstmt instanceof DefinitionStmt) { // Abstract
            if (jstmt instanceof AssignStmt) { // Abstract
                // We don't traverse each concrete assign types.
                // They are all translated to stores.

                LValue lvalue = ((AssignStmt<?, ?>) jstmt).getLValue();
                RValue rvalue = ((AssignStmt<?, ?>) jstmt).getRValue();

                Type ltype = lvalue.getType();
                Type rtype = rvalue.getType();

                if (ltype instanceof NullType) {
                    LLVMValueRef nop = codeGen.buildNop();
                    resInsts.add(nop);
                    return resInsts;
                }

                LLVMValueRef llvmPtr = tranLValue(lvalue);
                LLVMTypeRef llvmPtrElTy = getElementType(getValueType(llvmPtr));

                /*
                 * T-STORE:
                 *      Lval::T1
                 *      RVal::T2
                 *     T1 = (*T3)
                 * -------------------
                 *       T2 = T3
                 */
                // TODO: Evaluate implicit cast at stores.
                boolean enableImplicitCast;
                if (ltype instanceof ClassType &&
                        rtype instanceof ClassType &&
                        classHierarchy.isSubclass(
                                ((ClassType) ltype).getJClass(),
                                ((ClassType) rtype).getJClass())) {
                    // Enable implicit type conversion only if left is super type of right
                    enableImplicitCast = true;
                } else {
                    enableImplicitCast = false;
                }
                LLVMValueRef llvmValue = tranRValueCast(rvalue, llvmPtrElTy);
                LLVMValueRef store = codeGen.buildStore(llvmPtr, llvmValue);

                resInsts.addAll(List.of(llvmPtr, llvmValue, store));
                return resInsts;
            } else if (jstmt instanceof Invoke) {
                Var var = ((Invoke) jstmt).getLValue();
                InvokeExp invokeExp = ((Invoke) jstmt).getInvokeExp();
                if (var != null) {
                    /*
                     * T-Invoke-UnVoid:
                     *      Lval::T1
                     *      RVal::T2
                     *     T1 = (*T3)
                     * -------------------
                     *       T2 = T3
                     */
                    LLVMValueRef llvmPtr = tranLValue(var);
                    LLVMTypeRef llvmPtrType = getValueType(llvmPtr);
                    LLVMTypeRef llvmPtrElType = LLVM.LLVMGetElementType(llvmPtrType);

                    LLVMValueRef llvmCall = tranRValueCast(invokeExp, llvmPtrElType);

                    LLVMValueRef store = codeGen.buildStore(llvmPtr, llvmCall);

                    resInsts.addAll(List.of(llvmPtr, llvmCall, store));
                    return resInsts;
                } else {
                    Optional<LLVMValueRef> opllvmCall = tranRValue(invokeExp);
                    if (opllvmCall.isPresent()) {
                        resInsts.add(opllvmCall.get());
                    }
                    return resInsts;
                }
            }
        } else if (jstmt instanceof JumpStmt) { // Abstract
            if (jstmt instanceof SwitchStmt) { // Abstract
                // Uniformly handle LookupSwitch and TableSwitch
                Var var = ((SwitchStmt) jstmt).getVar();
                LLVMValueRef cond = tranRValue(var, codeGen.buildIntType(64));
                LLVMTypeRef condType = getValueType(cond);
                as.assertTrue(LLVM.LLVMGetTypeKind(condType) == LLVM.LLVMIntegerTypeKind, "The condType should be an integer. Got: {}", getLLVMStr(condType));

                List<Pair<Integer, Stmt>> caseTargets = ((SwitchStmt) jstmt).getCaseTargets();
                List<Pair<LLVMValueRef, LLVMBasicBlockRef>> llvmCaseTargets = new ArrayList<>();

                for (Pair<Integer, Stmt> caseTarget : caseTargets) {
                    Integer caseValue = caseTarget.first();
                    Stmt target = caseTarget.second();

                    LLVMValueRef llvmCase = codeGen.buildConstInt(condType, caseValue.longValue());
                    LLVMBasicBlockRef llvmBlock = maps.getStmtBlockMap(target).get();
                    llvmCaseTargets.add(new Pair(llvmCase, llvmBlock));
                }
                Stmt defaultStmt = ((SwitchStmt) jstmt).getDefaultTarget();
                LLVMBasicBlockRef defaultBlock = maps.getStmtBlockMap(defaultStmt).get();
                as.assertTrue(defaultBlock != null, "The default block should not be null");
                LLVMValueRef switchInst = codeGen.buildSwitch(cond, llvmCaseTargets, defaultBlock);
                as.assertTrue(switchInst != null, "It should not be null.");
                resInsts.add(switchInst);
                return resInsts;
            } else if (jstmt instanceof Goto) {
                Stmt targetStmt = ((Goto) jstmt).getTarget();
                LLVMBasicBlockRef targetBlock = maps.getStmtBlockMap(targetStmt).get();
                LLVMValueRef br = codeGen.buildUncondBr(targetBlock);
                as.assertTrue(br != null, "There should be a value");
                resInsts.add(br);
                return resInsts;
            } else if (jstmt instanceof If) {
                Set<CFGEdge<Stmt>> outEdges = cfg.getOutEdgesOf(jstmt);
                Stmt trueTarget = null;
                Stmt falseTarget = null;
                for (CFGEdge<Stmt> outEdge : outEdges) {
                    CFGEdge.Kind kind = outEdge.getKind();
                    if (kind == CFGEdge.Kind.IF_TRUE) {
                        as.assertTrue(trueTarget == null, "Only one true target.");
                        trueTarget = outEdge.target();
                        continue;
                    } else if (kind == CFGEdge.Kind.IF_FALSE) {
                        as.assertTrue(falseTarget == null, "Only one true target.");
                        falseTarget = outEdge.target();
                        continue;
                    }
                    as.unreachable("There shouldn't be other edge kind. Got: {}", outEdge);
                }

                as.assertTrue(trueTarget != null && falseTarget != null, "Both targets should be non-null. Got true: {}, false: {}", trueTarget, falseTarget);
                LLVMBasicBlockRef trueBlock = maps.getStmtBlockMap(trueTarget).get();
                LLVMBasicBlockRef falseBlock = maps.getStmtBlockMap(falseTarget).get();

                ConditionExp cond = ((If) jstmt).getCondition();
                LLVMValueRef condVal = tranRValueCast(cond, codeGen.buildIntType(1));
                LLVMValueRef br = codeGen.buildCondBr(condVal, trueBlock, falseBlock);
                as.assertTrue(br != null, "There should be a value");
                resInsts.add(br);
                return resInsts;
            }
        } else if (jstmt instanceof Return) {
            Var var = ((Return) jstmt).getValue();
            if (var == null) {
                LLVMValueRef ret = codeGen.buildRet(Optional.empty());
                resInsts.add(ret);
                return resInsts;
            } else {
                Type jretType = jmethod.getReturnType();
                LLVMTypeRef retType = tranType(jretType, ClassStatus.DEP_FIELDS);
                as.assertTrue(LLVM.LLVMGetTypeKind(retType) != LLVM.LLVMVoidTypeKind, "The none-void return stmt {} should not return void.", jstmt);
                LLVMValueRef retVal = tranRValueCast(var, retType);
                LLVMValueRef ret = codeGen.buildRet(Optional.of(retVal));
                resInsts.add(ret);
                return resInsts;
            }
        } else if (jstmt instanceof Nop) {
            LLVMValueRef nop = codeGen.buildNop();
            resInsts.add(nop);
            return resInsts;
        } else if (jstmt instanceof Monitor) {
            Var obj = ((Monitor) jstmt).getObjectRef();
            JClass objClass = world.getClassHierarchy().getClass("java.lang.Object");
            LLVMTypeRef objectType = tranType(objClass.getType(), ClassStatus.DEP_FIELDS);

            LLVMValueRef llvmObj = tranRValueCast(obj, objectType);

            if (((Monitor) jstmt).isEnter()) {
                LLVMValueRef enter = codeGen.buildMonitorEnter(llvmObj, objectType);
                return resInsts;
            } else {
                LLVMValueRef exit = codeGen.buildMonitorExit(llvmObj, objectType);
                return resInsts;
            }
        } else if (jstmt instanceof Catch) {
            Var catchedVar = ((Catch) jstmt).getExceptionRef();
            JClass exceptionClass = world.getClassHierarchy().getClass("java.lang.Exception");
            LLVMTypeRef exceptionType = tranType(exceptionClass.getType(), ClassStatus.DEP_FIELDS);

            LLVMValueRef catched = tranRValue(catchedVar, exceptionType);
            LLVMValueRef theCatch = codeGen.buildCatch(catched);
            return resInsts;
        } else if (jstmt instanceof Throw) {
            Var throwedVar = ((Throw) jstmt).getExceptionRef();
            JClass exceptionClass = world.getClassHierarchy().getClass("java.lang.Exception");
            LLVMTypeRef exceptionType = tranType(exceptionClass.getType(), ClassStatus.DEP_FIELDS);

            LLVMValueRef throwed = tranRValue(throwedVar, exceptionType);
            LLVMValueRef theThrow = codeGen.buildThrow(throwed);
            return resInsts;
        }
        as.unreachable("Unexpected statement: {}", jstmt);
        return null;
    }

    public Optional<LLVMValueRef> tranRValue(RValue jexp) {
        /* Translate RValue without a specified out Type.
         * So that we don't have a type assumption of the resulting LLVM value.
         * It returns null if the result is an "untyped null"
         */
        LLVMValueRef translateVal = tranRValueImpl(jexp, Optional.empty());
        return Optional.ofNullable(translateVal);
    }

    public LLVMValueRef tranRValue(RValue jexp, LLVMTypeRef defaultType) {
        /* Translate RValue with a default out Type.
         * The result must not be null
         */
        as.assertTrue(defaultType != null, "Need to specify an default type to in case of null value");

        LLVMValueRef translatedVal = tranRValueImpl(jexp, Optional.of(defaultType));  // pass in as default type
        as.assertTrue(translatedVal != null, "We can't obtain a null value when using a default out type.");
        return translatedVal;
    }

    public LLVMValueRef tranRValueCast(RValue jexp, LLVMTypeRef outType) {
        /* Translate RValue with a specified out Type.
         * The resulted value will be automatically cast to outType.
         */
        as.assertTrue(outType != null, "Need to specify an out type to get a LLVM value with this type.");

        LLVMValueRef translatedVal = tranRValueImpl(jexp, Optional.of(outType));  // pass in as default type
        as.assertTrue(translatedVal != null, "We can't obtain a null value when using a default out type.");

        return codeGen.buildTypeCast(translatedVal, outType);
    }

    @Nullable
    public LLVMValueRef tranRValueImpl(RValue jexp, Optional<LLVMTypeRef> defaultType) {
        /*
         * We specify one defaultType for typing. It requires the translated value of jexp should have a type.
         */
        if (jexp instanceof Literal) { // Interface
            if (jexp instanceof NumberLiteral) { // Interface
                if (jexp instanceof IntegerLiteral) { // Interface
                    if (jexp instanceof IntLiteral) {
                        Type jIntType = jexp.getType();
                        LLVMTypeRef llvmIntType = tranType(jIntType, null);
                        long intNumLong = ((IntLiteral) jexp).getNumber().longValue();
                        LLVMValueRef constInt = codeGen.buildConstInt(llvmIntType, intNumLong);
                        return constInt;
                    } else if (jexp instanceof LongLiteral) {
                        Type jlongType = jexp.getType();
                        LLVMTypeRef llvmLongType = tranType(jlongType, null);
                        long longNum = ((LongLiteral) jexp).getValue();
                        LLVMValueRef constInt = codeGen.buildConstInt(llvmLongType, longNum);
                        return constInt;
                    }
                } else if (jexp instanceof FloatingPointLiteral) { // Interface
                    if (jexp instanceof FloatLiteral) {
                        Type jfloatType = jexp.getType();
                        LLVMTypeRef llvmFloatType = tranType(jfloatType, null);
                        double floatNumDouble = ((FloatLiteral) jexp).getNumber().doubleValue();
                        LLVMValueRef constReal = codeGen.buildConstReal(llvmFloatType, floatNumDouble);
                        return constReal;
                    } else if (jexp instanceof DoubleLiteral) {
                        Type jdoubleType = jexp.getType();
                        LLVMTypeRef llvmDoubleType = tranType(jdoubleType, null);
                        double doubleNum = ((DoubleLiteral) jexp).getValue();
                        LLVMValueRef constReal = codeGen.buildConstReal(llvmDoubleType, doubleNum);
                        return constReal;
                    }
                }
            } else if (jexp instanceof ReferenceLiteral) { // Interface
                if (jexp instanceof NullLiteral) {
                    Type jtype = jexp.getType();
                    as.assertTrue(jtype instanceof NullType, "The type should be nulltype, so we don't know it.");
                    if (defaultType.isPresent()) {
                        LLVMValueRef llvmNull = codeGen.buildNull(defaultType.get());
                        return llvmNull;
                    } else {
                        return null;
                    }
                } else if (jexp instanceof StringLiteral) {
                    String str = ((StringLiteral) jexp).getString();
                    LLVMValueRef llvmStrVal = getOrTranStringLiteral(str);
                    return llvmStrVal;
                } else if (jexp instanceof ClassLiteral) {
                    JClass jclassClass = world.getClassHierarchy().getClass("java.lang.Class");
                    LLVMTypeRef javaClassType = tranType(jclassClass.getType(), ClassStatus.DEP_FIELDS);
                    Type theClass = ((ClassLiteral) jexp).getTypeValue();
                    LLVMTypeRef classType = tranType(theClass, ClassStatus.DEP_FIELDS);
                    LLVMValueRef classIntrinsic = codeGen.buildClassIntrinsic(classType, javaClassType);
                    return classIntrinsic;
                } else if (jexp instanceof MethodHandle) {
                    // TODO:
                    as.unreachable("It's unreachable");
                } else if (jexp instanceof MethodType) {
                    JClass jmethodTypeClass = world.getClassHierarchy().getClass("java.invoke.MethodType");
                    LLVMTypeRef javaMethodTypeClassType = tranType(jmethodTypeClass.getType(), ClassStatus.DEP_FIELDS);
                    Type jretType = ((MethodType) jexp).getReturnType();
                    List<Type> jparamTypes = ((MethodType) jexp).getParamTypes();
                    LLVMTypeRef retType = tranType(jretType, ClassStatus.DEP_FIELDS);
                    List<LLVMTypeRef> paramTypes = new ArrayList<>();
                    for (Type jparamType : jparamTypes) {
                        LLVMTypeRef paramType = tranType(jparamType, ClassStatus.DEP_FIELDS);
                        paramTypes.add(paramType);
                    }
                    LLVMValueRef methodType = codeGen.buildMethodType(retType, paramTypes, javaMethodTypeClassType);
                    return methodType;
                }
            }
        } else if (jexp instanceof FieldAccess) { // Abstract
            if (jexp instanceof StaticFieldAccess) {
                FieldRef fieldRef = ((StaticFieldAccess) jexp).getFieldRef();
                JField jfield = fieldRef.resolveNullable();
                as.assertTrue(jfield != null, "The static field access must can be handled");

                JClass declaringClass = jfield.getDeclaringClass();
                requireType(declaringClass.getType(), ClassStatus.DEP_FIELDS);

                LLVMValueRef ptr = getOrTranStaticField(jfield);
                LLVMValueRef load = codeGen.buildLoad(ptr, jfield.getName());
                return load;
            } else if (jexp instanceof InstanceFieldAccess) {
                Var baseVar = ((InstanceFieldAccess) jexp).getBase();
                FieldRef fieldRef = ((InstanceFieldAccess) jexp).getFieldRef();
                JField jfield = fieldRef.resolveNullable();
                as.assertTrue(jfield != null, "Unexpected unsolvable field {}.", jexp);
                LLVMValueRef ptr = resolveMemberField(jfield, baseVar);

                LLVMValueRef load = codeGen.buildLoad(ptr, jfield.getName());
                return load;
            }
        } else if (jexp instanceof UnaryExp) { // Interface
            if (jexp instanceof ArrayLengthExp) {
                Var arrayVar = ((ArrayLengthExp) jexp).getBase();
                Type resType = jexp.getType();

                LLVMValueRef llvmArrayPtr = tranRValueCast(arrayVar, codeGen.buildPointerType(codeGen.buildIntType(32)));
                return codeGen.buildLength(llvmArrayPtr);
            } else if (jexp instanceof NegExp) {
                Var var = ((NegExp) jexp).getValue();
                LLVMValueRef llvmVar = tranRValue(var).get();
                LLVMValueRef negVar = codeGen.buildNeg(llvmVar);
                return negVar;
            }
        } else if (jexp instanceof BinaryExp) { // Interface
            Type jresType = jexp.getType();
            LLVMTypeRef resType = tranType(jresType, null);

            Var left = ((BinaryExp) jexp).getOperand1();
            Var right = ((BinaryExp) jexp).getOperand2();

            if (jexp instanceof ArithmeticExp) {
                /*
                 * T-Arithmetic:
                 *     A = B op C
                 *  A::T  B::T1  C::T2
                 * -------------------
                 *     T = T1 = T2
                 */
                LLVMValueRef leftVal = tranRValueCast(left, resType);
                LLVMValueRef rightVal = tranRValueCast(right, resType);

                ArithmeticExp.Op op = ((ArithmeticExp) jexp).getOperator();

                String opStr = "";
                if (op.equals(ArithmeticExp.Op.ADD)) {
                    opStr = "+";
                } else if (op.equals(ArithmeticExp.Op.SUB)) {
                    opStr = "-";
                } else if (op.equals(ArithmeticExp.Op.MUL)) {
                    opStr = "*";
                } else if (op.equals(ArithmeticExp.Op.DIV)) {
                    opStr = "/";
                } else if (op.equals(ArithmeticExp.Op.REM)) {
                    opStr = "%";
                } else {
                    as.unreachable("Unexpected Op {}", op);
                }

                LLVMValueRef binOp = codeGen.buildBinaryOp(opStr, leftVal, rightVal, resType);
                return binOp;

            } else if (jexp instanceof BitwiseExp) {
                /*
                 * T-Bitwise:
                 *     A = B op C
                 *  A::T  B::T1  C::T2
                 * -------------------
                 *     T = T1 = T2
                 */
                LLVMValueRef leftVal = tranRValueCast(left, resType);
                LLVMValueRef rightVal = tranRValueCast(right, resType);

                BitwiseExp.Op op = ((BitwiseExp) jexp).getOperator();

                String opStr = "";
                if (op.equals(BitwiseExp.Op.AND)) {
                    opStr = "&";
                } else if (op.equals(BitwiseExp.Op.OR)) {
                    opStr = "|";
                } else if (op.equals(BitwiseExp.Op.XOR)) {
                    opStr = "^";
                } else {
                    as.unreachable("Unexpected Op {}", op);
                }

                LLVMValueRef binOp = codeGen.buildBinaryOp(opStr, leftVal, rightVal, resType);
                return binOp;

            } else if (jexp instanceof ComparisonExp) {
                /*
                 * T-Comparison:
                 *     A = B op C
                 *  A::T  B::T1  C::T2
                 * -------------------
                 *     T1 = T2
                 *     T = int
                 */
                LLVMTypeRef comparisonDefaultType = codeGen.buildIntType(32);
                LLVMValueRef leftVal = tranRValue(left, comparisonDefaultType);
                LLVMValueRef rightVal = tranRValue(right, comparisonDefaultType);


                ComparisonExp.Op op = ((ComparisonExp) jexp).getOperator();

                String opStr = "";
                if (op.equals(ComparisonExp.Op.CMP)) {
                    opStr = "cmp";
                } else if (op.equals(ComparisonExp.Op.CMPG)) {
                    opStr = "cmpg";
                } else if (op.equals(ComparisonExp.Op.CMPL)) {
                    opStr = "cmpl";
                } else {
                    as.unreachable("Unexpected Op {}", op);
                }

                LLVMValueRef binOp = codeGen.buildBinaryOp(opStr, leftVal, rightVal, resType);
                LLVMTypeRef binOpType = getValueType(binOp);
                as.assertTrue(LLVM.LLVMGetTypeKind(binOpType) == LLVM.LLVMIntegerTypeKind,
                        "The result type of value {} should be integer.", getLLVMStr(binOp));
                return binOp;

            } else if (jexp instanceof ConditionExp) {
                /*
                 * T-Condition:
                 *     A = B op C
                 *  A::T  B::T1  C::T2
                 * -------------------
                 *     T1 = T2
                 *     T = bool
                 */

                LLVMTypeRef conditionDefaultType = codeGen.buildIntType(32);
                LLVMValueRef leftVal = tranRValue(left, conditionDefaultType);
                LLVMValueRef rightVal = tranRValue(right, conditionDefaultType);

                ConditionExp.Op op = ((ConditionExp) jexp).getOperator();

                String opStr = "";
                if (op.equals(ConditionExp.Op.EQ)) {
                    opStr = "==";
                } else if (op.equals(ConditionExp.Op.GE)) {
                    opStr = ">=";
                } else if (op.equals(ConditionExp.Op.LT)) {
                    opStr = "<";
                } else if (op.equals(ConditionExp.Op.GT)) {
                    opStr = ">";
                } else if (op.equals(ConditionExp.Op.LE)) {
                    opStr = "<=";
                } else if (op.equals(ConditionExp.Op.NE)) {
                    opStr = "!=";
                } else {
                    as.unreachable("Unexpected Op {}", op);
                }

                LLVMValueRef binOp = codeGen.buildBinaryOp(opStr, leftVal, rightVal, resType);
                LLVMTypeRef binOpType = getValueType(binOp);
                as.assertTrue(LLVM.LLVMGetTypeKind(binOpType) == LLVM.LLVMIntegerTypeKind && LLVM.LLVMGetIntTypeWidth(binOpType) == 1,
                        "The result type of value {} should be integer.", getLLVMStr(binOp));
                return binOp;
            } else if (jexp instanceof ShiftExp) {
                /*
                 * T-Shift:
                 *     A = B op C
                 *  A::T  B::T1  C::T2
                 * -------------------
                 *     T = T1 = T2
                 */
                LLVMValueRef leftVal = tranRValueCast(left, resType);
                LLVMValueRef rightVal = tranRValueCast(right, resType);
                ShiftExp.Op op = ((ShiftExp) jexp).getOperator();
                String opStr = "";
                if (op.equals(ShiftExp.Op.SHL)) {
                    opStr = "shl";
                } else if (op.equals(ShiftExp.Op.SHR)) {
                    opStr = "shr";
                } else if (op.equals(ShiftExp.Op.USHR)) {
                    opStr = "ushr";
                } else {
                    as.unreachable("Unexpected Op {} {}", op, jexp);
                }

                LLVMValueRef binOp = codeGen.buildBinaryOp(opStr, leftVal, rightVal, resType);
                return binOp;
            }
        } else if (jexp instanceof NewExp) { // Interface
            if (jexp instanceof NewInstance) {
                Type objType = jexp.getType();
                LLVMTypeRef llvmObjType = tranTypeAlloc(objType, ClassStatus.DEP_FIELDS);
                LLVMValueRef object = codeGen.buildMalloc(llvmObjType);
                return object;
            } else if (jexp instanceof NewArray) {
                ArrayType jarrayType = ((NewArray) jexp).getType();
                LLVMTypeRef newedType = tranType(jarrayType, ClassStatus.DEP_FIELDS);
                Type jbaseType = jarrayType.baseType();
                as.assertFalse(jbaseType instanceof ArrayType, "Unexpected base type {}", jbaseType);

                LLVMTypeRef llvmBaseType = tranTypeAlloc(jbaseType, ClassStatus.DEP_FIELDS);
                LLVMValueRef llvmBaseSizeConst = codeGen.buildSizeOf(llvmBaseType);

                Var lengthVar = ((NewArray) jexp).getLength();
                LLVMValueRef length = tranRValueCast((RValue) lengthVar, codeGen.buildIntType(32));
                LLVMValueRef object = codeGen.buildNewArray(llvmBaseSizeConst, List.of(length), newedType);
                return object;

            } else if (jexp instanceof NewMultiArray) {
                ArrayType jarrayType = ((NewMultiArray) jexp).getType();
                LLVMTypeRef newedType = tranType(jarrayType, ClassStatus.DEP_FIELDS);
                Type jbaseType = jarrayType.baseType();
                as.assertFalse(jbaseType instanceof ArrayType, "Unexpected base type {}", jbaseType);

                LLVMTypeRef llvmBaseType = tranTypeAlloc(jbaseType, ClassStatus.DEP_FIELDS);
                LLVMValueRef llvmBaseSizeConst = codeGen.buildSizeOf(llvmBaseType);

                List<Var> lengthVars = ((NewMultiArray) jexp).getLengths();
                List<LLVMValueRef> lengths = new ArrayList<>();
                for (int d = 0; d < lengthVars.size(); d++) {
                    LLVMValueRef theLength = tranRValueCast((RValue) lengthVars.get(d), codeGen.buildIntType(32));
                    lengths.add(theLength);
                }
                LLVMValueRef object = codeGen.buildNewArray(llvmBaseSizeConst, lengths, newedType);
                return object;
            }
        } else if (jexp instanceof InvokeExp) { // Abstract
            if (jexp instanceof InvokeStatic) {
                MethodRef methodRef = ((InvokeStatic) jexp).getMethodRef();
                JMethod jcallee = methodRef.resolveNullable();
                as.assertTrue(jcallee != null, "Invokestatic must can be resolved.");
                LLVMValueRef callee = getOrTranMethodDecl(jcallee);
                as.assertTrue(LLVM.LLVMCountParams(callee) == ((InvokeStatic) jexp).getArgCount(),
                        "Argument number doesn't match. LLVM func: {}, static invoke: {}", getLLVMStr(callee), jexp);
                List<LLVMValueRef> args = new ArrayList<>();

                List<Var> jargs = ((InvokeStatic) jexp).getArgs();
                for (int i = 0; i < jargs.size(); i++) {
                    LLVMValueRef llvmIthParam = LLVM.LLVMGetParam(callee, i);
                    Var jarg = jargs.get(i);
                    LLVMValueRef llvmArg = tranRValueCast(jarg, getValueType(llvmIthParam));
                    args.add(llvmArg);
                }
                LLVMValueRef call = codeGen.buildCall(callee, args);
                return call;
            } else if (jexp instanceof InvokeDynamic) {
                // TODO: really implement InvokeDynamic
                Type jresType = jexp.getType();
                LLVMTypeRef resType = tranType(jresType, ClassStatus.DEP_FIELDS);
                LLVMValueRef res = codeGen.buildNull(resType);
                return res;
            } else if (jexp instanceof InvokeInstanceExp) { // Abstract
                Var baseVar = ((InvokeInstanceExp) jexp).getBase();
                MethodRef methodRef = ((InvokeInstanceExp) jexp).getMethodRef();
                JClass receiver = methodRef.getDeclaringClass();
                Subsignature sig = methodRef.getSubsignature();
                LLVMValueRef callee = resolveMethod(baseVar, sig, receiver);

                /*
                 * T-InvokeInstance:
                 *     F(B1, B2...)
                 *   F::T1->T2  B::T3 B2...::T4
                 * ---------------------------
                 *     T3 = T1   T4 = T2 ...
                 */
                LLVMTypeRef funcType = getFuncType(callee);
                as.assertTrue(LLVM.LLVMCountParamTypes(funcType) == ((InvokeInstanceExp) jexp).getArgCount() + 1,
                        "Argument number doesn't match. LLVM func: {} with {}, instance invoke: {} with {}",
                        getLLVMStr(callee), LLVM.LLVMCountParamTypes(funcType),
                        jexp, ((InvokeInstanceExp) jexp).getArgCount() + 1);
                List<LLVMTypeRef> paramTypes = getParamTypes(funcType);
                LLVMTypeRef llvm0thParamTy = paramTypes.get(0);
                LLVMValueRef llvmThis = tranRValueCast(baseVar, llvm0thParamTy);
                List<LLVMValueRef> args = new ArrayList<>();
                args.add(llvmThis);
                List<Var> jargs = ((InvokeInstanceExp) jexp).getArgs();
                for (int i = 1; i < jargs.size() + 1; i++) {
                    LLVMTypeRef llvmIthParamTy = paramTypes.get(i);
                    Var jarg = jargs.get(i - 1);
                    LLVMValueRef llvmArg = tranRValueCast(jarg, llvmIthParamTy);
                    args.add(llvmArg);
                }
                LLVMValueRef call = codeGen.buildCall(callee, args);
                return call;
            }
        } else if (jexp instanceof Var) {
            if (jexp.getType() instanceof NullType) {
                as.assertTrue(defaultType.isPresent(), "We need the outType to generate a 'typed' null value");
                LLVMValueRef typedNullVal = codeGen.buildNull(defaultType.get());
                return typedNullVal;
            } else {
                Optional<LLVMValueRef> opvarPtr = maps.getVarMap((Var) jexp);
                as.assertTrue(opvarPtr.isPresent(), "The variable {} has not been correctly handled", (Var) jexp);
                LLVMValueRef ptr = opvarPtr.get();
                LLVMValueRef llvmVal = codeGen.buildLoad(ptr, StringUtil.getVarNameAsLoad((Var) jexp));
                return llvmVal;
            }
        } else if (jexp instanceof ArrayAccess) {
            /*
             * T-R-ARRAY:
             *   A::T1,  T1 = T2[], T2 => F2
             *        A[n] =R> B
             *  -------------------------
             *    T1 => F1, F1 = *(F2[])
             *    A =R> B1    B1::F1
             * ----------------------------
             *          B :: F2
             */

            Var baseVar = ((ArrayAccess) jexp).getBase();
            Var indexVar = ((ArrayAccess) jexp).getIndex();

            Type jelementType = ((ArrayType) baseVar.getType()).elementType();   // :: T2
            LLVMTypeRef elementType = tranType(jelementType, ClassStatus.DEP_FIELDS);   // ::F2

            LLVMValueRef base = tranRValueCast(baseVar, codeGen.buildPointerType(codeGen.buildArrayType(elementType, 0)));     // ::F1

            LLVMValueRef index1 = codeGen.buildConstInt(codeGen.buildIntType(32), 0);
            LLVMValueRef index2 = tranRValueCast(indexVar, codeGen.buildIntType(32));
            LLVMValueRef gep = codeGen.buildGEP(base, List.of(index1, index2));
            LLVMValueRef load = codeGen.buildLoad(gep, StringUtil.getVarNameAsLoad(baseVar));
            return load;

        } else if (jexp instanceof CastExp) {
            Var var = ((CastExp) jexp).getValue();
            Type castType = ((CastExp) jexp).getCastType();
            LLVMTypeRef targetType = tranType(castType, ClassStatus.DEP_FIELDS);
            LLVMValueRef llvmVal = tranRValueCast(var, targetType);
            return llvmVal;
        } else if (jexp instanceof InstanceOfExp) {
            JClass objClass = world.getClassHierarchy().getClass("java.lang.Object");
            LLVMTypeRef objectType = tranType(objClass.getType(), ClassStatus.DEP_FIELDS);

            Type jcheckType = ((InstanceOfExp) jexp).getCheckedType();
            LLVMTypeRef checkType = tranType(jcheckType, ClassStatus.DEP_FIELDS);

            Var var = ((InstanceOfExp) jexp).getValue();
            LLVMValueRef checkValue = tranRValueCast(var, objectType);

            LLVMValueRef instanceOf = codeGen.buildInstanceOf(checkValue, checkType, objectType);
            return instanceOf;
        }
        as.unimplemented();
        return null;
    }


    public LLVMValueRef tranLValue(LValue jexp) {
        if (jexp instanceof FieldAccess) { // Abstract
            if (jexp instanceof StaticFieldAccess) {
                FieldRef fieldRef = ((StaticFieldAccess) jexp).getFieldRef();
                JField jfield = fieldRef.resolveNullable();
                if (jfield != null) {
                    LLVMValueRef ptr = getOrTranStaticField(jfield);
                    return ptr;
                }
            } else if (jexp instanceof InstanceFieldAccess) {
                Var baseVar = ((InstanceFieldAccess) jexp).getBase();
                FieldRef fieldRef = ((InstanceFieldAccess) jexp).getFieldRef();
                JField jfield = fieldRef.resolveNullable();
                as.assertTrue(jfield != null, "Unexpected unsolvable field {}.", jexp);
                LLVMValueRef ptr = resolveMemberField(jfield, baseVar);
                return ptr;
            }
        } else if (jexp instanceof Var) {
            // We have this assertion because we don't want to return null.
            as.assertFalse(jexp.getType() instanceof NullType, "It's not meaningful to have a null typed LValue: {}.", jexp);

            Optional<LLVMValueRef> opVarPtr = maps.getVarMap((Var) jexp);
            as.assertTrue(opVarPtr.isPresent(), "The variable {} has not been correctly handled", (Var) jexp);
            LLVMValueRef ptr = opVarPtr.get();
            return ptr;
        } else if (jexp instanceof ArrayAccess) {
            /*
             * T-L-ARRAY:
             *   A::T1,  T1 = T2[], T2 => F2
             *        A[n] =R> B
             *  -------------------------
             *    T1 => F1, F1 = *(F2[])
             *    A =R> B1    B1::F1
             * ----------------------------
             *          B :: *F2
             */

            Var baseVar = ((ArrayAccess) jexp).getBase();
            Var indexVar = ((ArrayAccess) jexp).getIndex();

            Type jelementType = ((ArrayType) baseVar.getType()).elementType();   // :: T2
            LLVMTypeRef elementType = tranType(jelementType, ClassStatus.DEP_FIELDS);   // ::F2

            LLVMValueRef base = tranRValueCast(baseVar, codeGen.buildPointerType(codeGen.buildArrayType(elementType, 0)));   // ::F1

            LLVMValueRef index1 = codeGen.buildConstInt(codeGen.buildIntType(32), 0);
            LLVMValueRef index2 = tranRValueCast(indexVar, codeGen.buildIntType(32));
            List<LLVMValueRef> indices = List.of(index1, index2);
            LLVMValueRef gep = codeGen.buildGEP(base, indices);
            return gep;
        }
        as.unimplemented();
        return null;
    }

    /*
     * Resolution of OOP features
     */
    public LLVMValueRef resolveMemberField(JField jfield, Var baseVar) {
        Type baseType = baseVar.getType();
        as.assertTrue(baseType instanceof ClassType, "The base var {} should be of classType", baseVar);

        JClass baseClass = ((ClassType) baseType).getJClass();
        JClass tarClass = jfield.getDeclaringClass();
        as.assertTrue(classHierarchy.isSubclass(tarClass, baseClass), "target class should be super of base. Target: {}. Base: {}", tarClass, baseClass);

        LLVMTypeRef llvmTarClass = tranType(tarClass.getType(), ClassStatus.DEP_FIELDS);
        LLVMValueRef base = tranRValueCast(baseVar, llvmTarClass);

        Integer index = getOrTranMemberField(jfield);
        LLVMValueRef ptr = codeGen.buildGEP(
                base,
                List.of(
                        codeGen.buildConstInt(codeGen.buildIntType(32), 0),
                        codeGen.buildConstInt(codeGen.buildIntType(32), index.intValue())
                )
        );
        return ptr;
    }

    public LLVMValueRef buildGEPtoContainerSlot(JClass jclass, JClass container, LLVMValueRef gepBase, Integer slotIndex) {
        List<JClass> trace = JavaUtil.getTraceBetween(classHierarchy, jclass, container);
        List<Integer> indexes = new ArrayList<>();
        indexes.add(0);
        JClass lastClass = jclass;
        for (int i = 1; i < trace.size(); i++) {
            JClass curClass = trace.get(i);
            Integer index = null;
            requireType(lastClass.getType(), ClassStatus.DEP_FIELDS);
            if (curClass == lastClass.getSuperClass()) {
                index = 0;
            } else {
                index = maps.getInterfaceIndexMap(lastClass, curClass).get();
            }
            indexes.add(index);
            lastClass = curClass;
        }
        indexes.add(slotIndex);
        List<LLVMValueRef> gepIndexes = indexes.stream().map(
                i -> codeGen.buildConstInt(codeGen.buildIntType(32), i)).toList();
        return codeGen.buildGEP(gepBase, gepIndexes);
    }

    public LLVMValueRef resolveMethod(Var base, Subsignature sig, JClass receiver) {
        /*
         * Virtual call resolution
         */
        Type baseType = base.getType();
        if (baseType instanceof ArrayType) {
            JClass objClass = world.getClassHierarchy().getClass("java.lang.Object");
            baseType = objClass.getType();
        }
        as.assertTrue(baseType instanceof ClassType, "The base var should be ClassType. Got {}. On sig: {}. ", baseType, sig);
        JClass jclass = ((ClassType) baseType).getJClass();
        JClass container = synRes.getLoadContainer(jclass, sig, classHierarchy);
        if (container == null) { // direct call
            if (!receiver.isInterface()) {
                // if it's a class:
                // then this direct call must be DEFINED within itself or its parents
                JClass cur = receiver;
                while (cur != null) {
                    JMethod direct = cur.getDeclaredMethod(sig);
                    if (direct != null) return getOrTranMethodDecl(direct);
                    cur = cur.getSuperClass();
                }
            } else {
                // if it's an interface
                // it means that there is exactly one class that implements this method
                Queue<JClass> worklist = new LinkedList<>();
                worklist.add(receiver);
                while (!worklist.isEmpty()) {
                    JClass cur = worklist.poll();
                    if (cur.isInterface()) {
                        worklist.addAll(classHierarchy.getDirectSubinterfacesOf(cur));
                        worklist.addAll(classHierarchy.getDirectImplementorsOf(cur));
                    } else {
                        JClass cur2 = cur;
                        while (cur2 != null) {
                            JMethod direct = cur2.getDeclaredMethod(sig);
                            if (direct != null) return getOrTranMethodDecl(direct);
                            cur2 = cur2.getSuperClass();
                        }
                    }

                }
            }

            as.unreachable("Direct method not found for class: {}, receiver: {}, sig: {}", jclass, receiver, sig);
        }

        LLVMValueRef llvmVar = tranRValueCast(base, tranType(baseType, ClassStatus.DEP_FIELDS));
        requireType(container.getType(), ClassStatus.DEP_FIELDS);
        LLVMValueRef funcPtrPtr = buildGEPtoContainerSlot(jclass, container, llvmVar, maps.getSlotIndexMap(container, sig).get());
        LLVMValueRef funcPtr = codeGen.buildLoad(funcPtrPtr, "funcPtr");
        return funcPtr;
    }
}
