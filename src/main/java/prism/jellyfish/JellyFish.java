package prism.jellyfish;


import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.cfg.CFGEdge;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.*;
import pascal.taie.language.type.*;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.collection.Pair;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.util.*;
import java.util.stream.Collectors;

import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;

import prism.llvm.LLVMCodeGen;
import prism.jellyfish.util.AssertUtil;
import prism.jellyfish.util.StringUtil;

import static prism.llvm.LLVMUtil.getElementType;
import static prism.llvm.LLVMUtil.getValueType;
import static prism.llvm.LLVMUtil.getLLVMStr;
import static prism.llvm.LLVMUtil.getParamTypes;
import static prism.llvm.LLVMUtil.getFuncType;

import javax.annotation.Nullable;


public class JellyFish extends ProgramAnalysis<Void> {
    public static final String ID = "jelly-fish";
    private static final Logger logger = LogManager.getLogger(JellyFish.class);
    private static final AssertUtil as = new AssertUtil(logger);

    World world;
    ClassHierarchy classHierarchy;
    LLVMCodeGen codeGen;
    Mappings maps;
    AnalysisConfig config;


    public JellyFish(AnalysisConfig config) {
        super(config);
        logger.info("Jellyfish is a transpiler from Tai-e IR to LLVM IR.");
        this.config = config;
        this.world = World.get();
        this.classHierarchy = world.getClassHierarchy();
        this.codeGen = new LLVMCodeGen();
        this.maps = new Mappings();
    }

    @Override
    public Void analyze() {
        List<JClass> jclasses = classHierarchy.applicationClasses().toList();
//        List<JClass> jclasses = classHierarchy.allClasses().toList();
        for (JClass jclass : jclasses) {
            String className = jclass.getName();
            String moduleName = jclass.getModuleName();
            String simpleName = jclass.getSimpleName();
            logger.info("Init class: name: {}, module name: {}, simple name:{}", className, moduleName, simpleName);
            // Class
            LLVMTypeRef llvmClass = getOrTranClass(jclass, ClassDepID.DEP_METHOD_DEF);
        }
        codeGen.generate();
        return null;
    }


    /*
     * Mappings between Java and LLVM
     */
    public enum ClassDepID {
        DEP_DECL(0),
        DEP_FIELDS(1),
        DEP_METHOD_DECL(2),
        DEP_METHOD_DEF(3);
        private final int ord;

        ClassDepID(int ord) {
            this.ord = ord;
        }

        public int getOrd() {
            return ord;
        }
    }

    public LLVMTypeRef getOrTranClass(JClass jclass, ClassDepID dep) {
        Optional<LLVMTypeRef> llvmClass = maps.getClassMap(jclass);
        LLVMTypeRef theClass;
        ClassDepID curDep = null;
        if (llvmClass.isPresent()) {
            LLVMTypeRef existClass = llvmClass.get();
            theClass = existClass;

            curDep = maps.getClassStatusMap(jclass).get();
        } else {
            LLVMTypeRef newClass = this.tranClassDecl(jclass);
            boolean ret = maps.setClassMap(jclass, newClass);
            as.assertTrue(ret, "The jclass {} has been duplicate translated.", jclass);
            theClass = newClass;

            ret = maps.setClassStatusMap(jclass, ClassDepID.DEP_DECL);
            as.assertTrue(ret, "The status of jclass {} has been duplicate updated.", jclass);
            curDep = ClassDepID.DEP_DECL;
        }

        for (int i = curDep.getOrd() + 1; i <= dep.getOrd(); i++) {
            if (i == ClassDepID.DEP_DECL.getOrd()) {
                continue;
            } else if (i == ClassDepID.DEP_FIELDS.getOrd()) {
                this.tranClassFields(jclass);
                maps.setClassStatusMap(jclass, ClassDepID.DEP_FIELDS);
            } else if (i == ClassDepID.DEP_METHOD_DECL.getOrd()) {
                Collection<JMethod> methods = jclass.getDeclaredMethods();
                for (JMethod jmethod : methods) {
                    LLVMValueRef llvmMethod = getOrTranMethodDecl(jmethod);
                }
                maps.setClassStatusMap(jclass, ClassDepID.DEP_METHOD_DECL);
            } else if (i == ClassDepID.DEP_METHOD_DEF.getOrd()) {
                Collection<JMethod> methods = jclass.getDeclaredMethods();
                for (JMethod jmethod : methods) {
                    this.tranMethodBody(jmethod);
                }
                maps.setClassStatusMap(jclass, ClassDepID.DEP_METHOD_DEF);
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
        LLVMTypeRef llvmStringClass = tranTypeAlloc(stringClass.getType(), ClassDepID.DEP_FIELDS);
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
        // After update: also translate the "relevant classes" by different types of reference
//        // 1. Field reference
//        Collection<JField> fields = jclass.getDeclaredFields();
//        for (JField field : fields) {
//            Type ftype = field.getType();
//            if (ftype instanceof ClassType) {
//                JClass fclass = ((ClassType) ftype).getJClass();
//                LLVMTypeRef fllvmClass = getOrTranClass(fclass);
//            }
//        }
//
//        // 2. Super class reference
//        List<JClass> superClasses = new ArrayList<>();
//        JClass supClass = jclass.getSuperClass();
//        while (supClass != null) {
//            superClasses.add(supClass);
//            supClass = supClass.getSuperClass();
//        }
//        for (JClass superClass : superClasses) {
//            LLVMTypeRef llvmSClass = getOrTranClass(superClass);
//        }
//
//        // 3. Interface reference
//        Collection<JClass> jinterfaces = jclass.getInterfaces();
//        for (JClass jinterface : jinterfaces) {
//            LLVMTypeRef llvmInterface = getOrTranClass(jinterface);
//        }

    }

    public void tranClassFields(JClass jclass) {
        Optional<LLVMTypeRef> opllvmClass = maps.getClassMap(jclass);
        as.assertTrue(opllvmClass.isPresent(), "The class declaration of {} should have been translated.", jclass);
        LLVMTypeRef llvmClass = opllvmClass.get();

        JClass objClass = world.getClassHierarchy().getClass("java.lang.Object");
        LLVMTypeRef llvmObjClass = tranType(objClass.getType(), ClassDepID.DEP_DECL);

        List<LLVMTypeRef> fieldTypes = new ArrayList<>();
        // 1. "super field"
        JClass sclass = jclass.getSuperClass();
        if (sclass != null) {
            LLVMTypeRef llvmSuperClass = tranTypeAlloc(sclass.getType(), ClassDepID.DEP_DECL);
            fieldTypes.add(llvmSuperClass);
        } else {
            fieldTypes.add(llvmObjClass);
        }
        // 2. "sub pointer"
        fieldTypes.add(llvmObjClass);

        // 3. Virtual method Fields
        Collection<JMethod> methods = jclass.getDeclaredMethods();
        for (JMethod method : methods) {
            if (!isRootVirtualMethod(method)) {
                continue;
            }
            LLVMTypeRef funcType = tranMethodType(method);
            LLVMTypeRef funcPtrType = codeGen.buildPointerType(funcType);

            boolean ret = maps.setVirtualMethodMap(method, fieldTypes.size());
            as.assertTrue(ret, "The method {} has been duplicate translated", method);
            fieldTypes.add(funcPtrType);
        }

        // 4. Static and Member Fields
        Collection<JField> fields = jclass.getDeclaredFields();
        for (JField field : fields) {
            String fieldName = field.getName();
            Type ftype = field.getType();
            LLVMTypeRef fllvmType = tranType(ftype, ClassDepID.DEP_DECL);
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

    public LLVMTypeRef tranTypeAlloc(Type jType, ClassDepID dep) {
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
            LLVMTypeRef llvmClassType = tranType(classType, ClassDepID.DEP_DECL);
            paramTypes.add(llvmClassType);
        }
        for (Type jType : jmethod.getParamTypes()) {
            LLVMTypeRef type = tranType(jType, ClassDepID.DEP_DECL);
            paramTypes.add(type);
        }
        Type jretType = jmethod.getReturnType();
        LLVMTypeRef retType = tranType(jretType, ClassDepID.DEP_DECL);
        LLVMTypeRef funcType = codeGen.buildFunctionType(retType, paramTypes);
        return funcType;
    }

    public LLVMTypeRef tranType(Type jType, ClassDepID dep) {
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
        // TODO: enable all methods after the tool is mature
//        if (!jmethod.getName().equals("<clinit>")) {
//            // Debug use: Skip non-class-init method
//            return;
//        }

        logger.info("*Method: {}. In Class: {}", jmethod.getName(), jmethod.getDeclaringClass());
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

        // We create an entry block contains all Tai-e variables
        LLVMBasicBlockRef entryBlock = codeGen.addBasicBlock(llvmFunc, "entry");
        codeGen.setInsertBlock(entryBlock);
        List<Var> vars = ir.getVars();
        for (Var var : vars) {
            Type jvarType = var.getType();
            if (jvarType instanceof NullType) continue;
            LLVMTypeRef llvmVarType = tranType(jvarType, ClassDepID.DEP_FIELDS);
            String llvmVarName = StringUtil.getVarNameAsPtr(var);
            LLVMValueRef alloca = codeGen.buildAlloca(llvmVarType, llvmVarName);
            boolean ret = maps.setVarMap(var, alloca);
            as.assertTrue(ret, "The var {} has been duplicate translated.", var);
        }


        // We allocate an exit block for the exit stmt in CFG, which is added by Tai-e.
        Stmt exitStmt = cfg.getExit();
        LLVMBasicBlockRef exitBB = codeGen.addBasicBlock(llvmFunc, "exit");
        maps.setStmtBlockMap(exitStmt, exitBB);
        codeGen.setInsertBlock(exitBB);
        codeGen.buildUnreachable();

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
            logger.info("**Stmt: {}", jstmt);
            LLVMBasicBlockRef bb = maps.getStmtBlockMap(jstmt).get();
            codeGen.setInsertBlock(bb);
            List<LLVMValueRef> llvmInsts = this.tranStmt(jstmt, jmethod, cfg);

            List<String> llvmInstStrs = llvmInsts.stream().map(inst -> getLLVMStr(inst)).toList();
            for (String str : llvmInstStrs) logger.info("  => {}", str);
        }

        // Handle the normal basic blocks without a terminator instructions
        for (Stmt jstmt : jstmts) {
            LLVMBasicBlockRef bb = maps.getStmtBlockMap(jstmt).get();
            LLVMValueRef lastInst = LLVM.LLVMGetLastInstruction(bb);
            if (LLVM.LLVMIsATerminatorInst(lastInst) != null) {
                continue;
            }
            Set<CFGEdge<Stmt>> outEdges = cfg.getOutEdgesOf(jstmt);
            for (CFGEdge<Stmt> outEdge : outEdges) {
                CFGEdge.Kind outKind = outEdge.getKind();
                if (outKind == CFGEdge.Kind.FALL_THROUGH) {
                    codeGen.setInsertBlock(bb);
                    Stmt target = outEdge.target();
                    LLVMBasicBlockRef fallthrough = maps.getStmtBlockMap(target).get();
                    codeGen.buildUncondBr(fallthrough);
                } else if (outKind == CFGEdge.Kind.CAUGHT_EXCEPTION) {
                    codeGen.setInsertBlock(bb);
                    Stmt target = outEdge.target();
                    LLVMBasicBlockRef handler = maps.getStmtBlockMap(target).get();
                    codeGen.buildUncondBr(handler);
                } else if (outKind == CFGEdge.Kind.UNCAUGHT_EXCEPTION) {
                    codeGen.buildUncondBr(exitBB);
                } else {
                    as.unreachable("The other kinds are unexpected. edge: {}. Last Inst: {}", outEdge, getLLVMStr(lastInst));
                }
            }
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

        maps.clearVarMap();
        maps.clearStmtBlockMap();

    }

    @Nullable
    public List<LLVMValueRef> tranStmt(Stmt jstmt, JMethod jmethod, CFG<Stmt> cfg) {
        List<LLVMValueRef> resInsts = new ArrayList<>();

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
                LLVMValueRef llvmValue = tranRValue(rvalue, llvmPtrElTy, true);
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

                    LLVMValueRef llvmCall = tranRValue(invokeExp, llvmPtrElType, false);

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
                LLVMValueRef condVal = tranRValue(cond, codeGen.buildIntType(1), true);
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
                LLVMTypeRef retType = tranType(jretType, ClassDepID.DEP_FIELDS);
                as.assertTrue(LLVM.LLVMGetTypeKind(retType) != LLVM.LLVMVoidTypeKind, "The none-void return stmt {} should not return void.", jstmt);
                LLVMValueRef retVal = tranRValue(var, retType, false);
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
            LLVMTypeRef objectType = tranType(objClass.getType(), ClassDepID.DEP_FIELDS);

            LLVMValueRef llvmObj = tranRValue(obj, objectType, true);

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
            LLVMTypeRef exceptionType = tranType(exceptionClass.getType(), ClassDepID.DEP_FIELDS);

            LLVMValueRef catched = tranRValue(catchedVar, exceptionType);
            LLVMValueRef theCatch = codeGen.buildCatch(catched);
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

    public LLVMValueRef tranRValue(RValue jexp, LLVMTypeRef outType, boolean autoCast) {
        /* Translate RValue with a specified out Type.
         * So that the resulting LLVM value would either be:
         * enforced with a type check (autoCast = false)
         * Automatically casted to the outType (autoCast = true)
         * Also, we would use it as the default type.
         */
        as.assertTrue(outType != null, "Need to specify an out type to get a LLVM value with this type.");

        LLVMValueRef translatedVal = tranRValueImpl(jexp, Optional.of(outType));  // pass in as default type
        as.assertTrue(translatedVal != null, "We can't obtain a null value when using a default out type.");

        if (autoCast) {
            return codeGen.buildTypeCast(translatedVal, outType);  // convert if needed.
        } else {
            LLVMTypeRef valType = getValueType(translatedVal);
            as.assertTrue(valType.equals(outType),
                    "Typing: the translated type should == outType.\n" +
                            " Translated Val: {}. Out Type: {}", getLLVMStr(translatedVal), getLLVMStr(outType));
            return translatedVal;
        }
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
                    LLVMTypeRef javaClassType = tranType(jclassClass.getType(), ClassDepID.DEP_FIELDS);
                    Type theClass = ((ClassLiteral) jexp).getTypeValue();
                    LLVMTypeRef classType = tranType(theClass, ClassDepID.DEP_FIELDS);
                    LLVMValueRef classIntrinsic = codeGen.buildClassIntrinsic(classType, javaClassType);
                    return classIntrinsic;
                } else if (jexp instanceof MethodHandle) {
                    // TODO:
                    as.unreachable("It's unreachable");
                } else if (jexp instanceof MethodType) {
                    JClass jmethodTypeClass = world.getClassHierarchy().getClass("java.invoke.MethodType");
                    LLVMTypeRef javaMethodTypeClassType = tranType(jmethodTypeClass.getType(), ClassDepID.DEP_FIELDS);
                    Type jretType = ((MethodType) jexp).getReturnType();
                    List<Type> jparamTypes = ((MethodType) jexp).getParamTypes();
                    LLVMTypeRef retType = tranType(jretType, ClassDepID.DEP_FIELDS);
                    List<LLVMTypeRef> paramTypes = new ArrayList<>();
                    for (Type jparamType : jparamTypes) {
                        LLVMTypeRef paramType = tranType(jparamType, ClassDepID.DEP_FIELDS);
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
                LLVMTypeRef llvmDeclClass = tranType(declaringClass.getType(), ClassDepID.DEP_FIELDS);

                Optional<LLVMValueRef> opfieldPtr = maps.getStaticFieldMap(jfield);
                as.assertTrue(opfieldPtr.isPresent(), "The field {} should have been translated.", jfield);
                LLVMValueRef ptr = opfieldPtr.get();
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

                LLVMValueRef llvmArrayPtr = tranRValue(arrayVar, codeGen.buildPointerType(codeGen.buildIntType(32)), true);
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
                LLVMValueRef leftVal = tranRValue(left, resType, true);
                LLVMValueRef rightVal = tranRValue(right, resType, true);

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
                LLVMValueRef leftVal = tranRValue(left, resType, true);
                LLVMValueRef rightVal = tranRValue(right, resType, true);

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
                LLVMValueRef leftVal = tranRValue(left, resType, true);
                LLVMValueRef rightVal = tranRValue(right, resType, true);
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
                LLVMTypeRef llvmObjType = tranTypeAlloc(objType, ClassDepID.DEP_FIELDS);
                LLVMValueRef object = codeGen.buildMalloc(llvmObjType);
                return object;
            } else if (jexp instanceof NewArray) {
                ArrayType jarrayType = ((NewArray) jexp).getType();
                LLVMTypeRef newedType = tranType(jarrayType, ClassDepID.DEP_FIELDS);
                Type jbaseType = jarrayType.baseType();
                as.assertFalse(jbaseType instanceof ArrayType, "Unexpected base type {}", jbaseType);

                LLVMTypeRef llvmBaseType = tranTypeAlloc(jbaseType, ClassDepID.DEP_FIELDS);
                LLVMValueRef llvmBaseSizeConst = codeGen.buildSizeOf(llvmBaseType);

                Var lengthVar = ((NewArray) jexp).getLength();
                LLVMValueRef length = tranRValue((RValue) lengthVar, codeGen.buildIntType(32), true);
                LLVMValueRef object = codeGen.buildNewArray(llvmBaseSizeConst, List.of(length), newedType);
                return object;

            } else if (jexp instanceof NewMultiArray) {
                ArrayType jarrayType = ((NewMultiArray) jexp).getType();
                LLVMTypeRef newedType = tranType(jarrayType, ClassDepID.DEP_FIELDS);
                Type jbaseType = jarrayType.baseType();
                as.assertFalse(jbaseType instanceof ArrayType, "Unexpected base type {}", jbaseType);

                LLVMTypeRef llvmBaseType = tranTypeAlloc(jbaseType, ClassDepID.DEP_FIELDS);
                LLVMValueRef llvmBaseSizeConst = codeGen.buildSizeOf(llvmBaseType);

                List<Var> lengthVars = ((NewMultiArray) jexp).getLengths();
                List<LLVMValueRef> lengths = new ArrayList<>();
                for (int d = 0; d < lengthVars.size(); d++) {
                    LLVMValueRef theLength = tranRValue((RValue) lengthVars.get(d), codeGen.buildIntType(32), true);
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
                    LLVMValueRef llvmArg = tranRValue(jarg, getValueType(llvmIthParam), true);
                    args.add(llvmArg);
                }
                LLVMValueRef call = codeGen.buildCall(callee, args);
                return call;
            } else if (jexp instanceof InvokeDynamic) {
                // TODO: really implement InvokeDynamic
                Type jresType = jexp.getType();
                LLVMTypeRef resType = tranType(jresType, ClassDepID.DEP_FIELDS);
                LLVMValueRef res = codeGen.buildNull(resType);
                return res;
            } else if (jexp instanceof InvokeInstanceExp) { // Abstract
                Var baseVar = ((InvokeInstanceExp) jexp).getBase();
                MethodRef methodRef = ((InvokeInstanceExp) jexp).getMethodRef();
                Subsignature sig = methodRef.getSubsignature();
                LLVMValueRef callee = resolveMethod(baseVar, sig);

                /*
                 * T-InvokeInstance:
                 *     F(B1, B2...)
                 *   F::T1->T2  B::T3 B2...::T4
                 * ---------------------------
                 *     T3 = T1   T4 = T2
                 */
                LLVMTypeRef funcType = getFuncType(callee);
                as.assertTrue(LLVM.LLVMCountParamTypes(funcType) == ((InvokeInstanceExp) jexp).getArgCount() + 1,
                        "Argument number doesn't match. LLVM func: {} with {}, instance invoke: {} with {}",
                        getLLVMStr(callee), LLVM.LLVMCountParamTypes(funcType),
                        jexp, ((InvokeInstanceExp) jexp).getArgCount() + 1);
                List<LLVMTypeRef> paramTypes = getParamTypes(funcType);
                LLVMTypeRef llvm0thParamTy = paramTypes.get(0);
                LLVMValueRef llvmThis = tranRValue(baseVar, llvm0thParamTy, true);
                List<LLVMValueRef> args = new ArrayList<>();
                args.add(llvmThis);
                List<Var> jargs = ((InvokeInstanceExp) jexp).getArgs();
                for (int i = 1; i < jargs.size() + 1; i++) {
                    LLVMTypeRef llvmIthParamTy = paramTypes.get(i);
                    Var jarg = jargs.get(i - 1);
                    LLVMValueRef llvmArg = tranRValue(jarg, llvmIthParamTy, true);
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
            LLVMTypeRef elementType = tranType(jelementType, ClassDepID.DEP_FIELDS);   // ::F2

            LLVMValueRef base = tranRValue(baseVar, codeGen.buildPointerType(codeGen.buildArrayType(elementType, 0)), true);     // ::F1

            LLVMValueRef index1 = codeGen.buildConstInt(codeGen.buildIntType(32), 0);
            LLVMValueRef index2 = tranRValue(indexVar, codeGen.buildIntType(32), true);
            LLVMValueRef gep = codeGen.buildGEP(base, List.of(index1, index2));
            LLVMValueRef load = codeGen.buildLoad(gep, StringUtil.getVarNameAsLoad(baseVar));
            return load;

        } else if (jexp instanceof CastExp) {
            Var var = ((CastExp) jexp).getValue();
            Type castType = ((CastExp) jexp).getCastType();
            LLVMTypeRef targetType = tranType(castType, ClassDepID.DEP_FIELDS);
            LLVMValueRef llvmVal = tranRValue(var, targetType, true);
            return llvmVal;
        } else if (jexp instanceof InstanceOfExp) {
            JClass objClass = world.getClassHierarchy().getClass("java.lang.Object");
            LLVMTypeRef objectType = tranType(objClass.getType(), ClassDepID.DEP_FIELDS);

            Type jcheckType = ((InstanceOfExp) jexp).getCheckedType();
            LLVMTypeRef checkType = tranType(jcheckType, ClassDepID.DEP_FIELDS);

            Var var = ((InstanceOfExp) jexp).getValue();
            LLVMValueRef checkValue = tranRValue(var, objectType, true);

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
                    Optional<LLVMValueRef> opfieldPtr = maps.getStaticFieldMap(jfield);
                    as.assertTrue(opfieldPtr.isPresent(), "The field {} should have been translated.", jfield);
                    LLVMValueRef ptr = opfieldPtr.get();
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
            LLVMTypeRef elementType = tranType(jelementType, ClassDepID.DEP_FIELDS);   // ::F2

            LLVMValueRef base = tranRValue(baseVar, codeGen.buildPointerType(codeGen.buildArrayType(elementType, 0)), true);   // ::F1

            LLVMValueRef index1 = codeGen.buildConstInt(codeGen.buildIntType(32), 0);
            LLVMValueRef index2 = tranRValue(indexVar, codeGen.buildIntType(32), true);
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

        LLVMTypeRef llvmTarClass = tranType(tarClass.getType(), ClassDepID.DEP_FIELDS);
        LLVMValueRef base = tranRValue(baseVar, llvmTarClass, true);

        Optional<Integer> opfieldIndex = maps.getMemberFieldMap(jfield);
        as.assertTrue(opfieldIndex.isPresent(), "The field {} should have been translated", jfield);
        int index = opfieldIndex.get().intValue();
        LLVMValueRef ptr = codeGen.buildGEP(
                base,
                List.of(
                        codeGen.buildConstInt(codeGen.buildIntType(32), 0),
                        codeGen.buildConstInt(codeGen.buildIntType(32), index)
                )
        );
        return ptr;
    }

    public boolean isRootVirtualMethod(JMethod jmethod) {
        if (!isVirtualMethod(jmethod)) {
            return false;
        }

        JClass declClass = jmethod.getDeclaringClass();
        Subsignature sig = jmethod.getSubsignature();
        JClass curSuperClass = declClass.getSuperClass();
        while (curSuperClass != null) {
            JMethod smethod = curSuperClass.getDeclaredMethod(sig);
            if (smethod != null) {
                return false;
            }
            curSuperClass = curSuperClass.getSuperClass();
        }
        return true;
    }

    public boolean isVirtualMethod(JMethod jmethod) {
        if (jmethod.isStatic() || jmethod.isConstructor()) {
            return false;
        }
        JClass declClass = jmethod.getDeclaringClass();
        Subsignature sig = jmethod.getSubsignature();
        Collection<JClass> subClasses = classHierarchy.getAllSubclassesOf(declClass);
        for (JClass subClass : subClasses) {
            if (subClass.equals(declClass)) {
                continue;
            }
            JMethod candidate = subClass.getDeclaredMethod(sig);
            if (candidate != null) {
                return true;
            }
        }
        return false;
    }

    public LLVMValueRef resolveMethod(Var base, Subsignature sig) {
        Type baseType = base.getType();
        as.assertTrue(baseType instanceof ClassType, "The base var should be ClassType. Got {}.", baseType);
        JClass baseClass = ((ClassType) baseType).getJClass();

        JClass curClass = baseClass;
        JMethod nearestMethod = null;
        while (curClass != null) {
            JMethod declaredMethod = curClass.getDeclaredMethod(sig);
            if (declaredMethod != null) {
                nearestMethod = declaredMethod;
                break;
            }
            curClass = curClass.getSuperClass();
        }
        as.assertTrue(nearestMethod != null, "There should at least one method for sig: {}.", sig);

        // Check if it's a direct call:
        if (!isVirtualMethod(nearestMethod)) {
            return getOrTranMethodDecl(nearestMethod);
        }

        // Otherwise, translate the virtual call by finding the root virtual method:
        JClass curClass2 = baseClass;
        Integer indexFuncPtr = null;
        int steps = 0;
        while (curClass2 != null) {
            JMethod candidate = curClass2.getDeclaredMethod(sig);
            if (candidate != null) {
                Optional<Integer> opIndex = maps.getVirtualMethodMap(candidate);
                if (opIndex.isPresent()) {
                    indexFuncPtr = opIndex.get();
                    break;
                }
            }
            curClass2 = curClass2.getSuperClass();
            steps += 1;
        }
        as.assertTrue(curClass2 != null && indexFuncPtr != null,
                "Unexpected class hierarchy. The virtual method is not found. Base class: {}, sig: {}", baseClass, sig);
        JClass tarClass = curClass2;

        LLVMValueRef llvmVar = tranRValue(base, tranType(baseType, ClassDepID.DEP_METHOD_DECL), false);
        List<LLVMValueRef> indexes = new ArrayList<>();

        for (int i = 0; i < steps + 1; i++) {
            indexes.add(
                    codeGen.buildConstInt(
                            codeGen.buildIntType(32),
                            0
                    )
            );
        }
        indexes.add(codeGen.buildConstInt(
                codeGen.buildIntType(32),
                indexFuncPtr.intValue()
        ));

        LLVMValueRef funcPtrPtr = codeGen.buildGEP(llvmVar, indexes);
        LLVMValueRef funcPtr = codeGen.buildLoad(funcPtrPtr, "funcPtr");
        return funcPtr;
    }

}
