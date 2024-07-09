package prism.jellyfish;


import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.config.AnalysisConfig;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import pascal.taie.language.classes.*;
import pascal.taie.language.type.*;


import java.util.*;
import java.util.stream.Collectors;


import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;

import pascal.taie.util.collection.Pair;
import prism.jellyfish.util.AssertUtil;
import prism.llvm.LLVMCodeGen;
import prism.jellyfish.util.StringUtil;


import javax.annotation.Nullable;

import static java.util.List.of;
import static prism.llvm.LLVMUtil.getElementType;
import static prism.llvm.LLVMUtil.getValueType;
import static prism.llvm.LLVMUtil.getLLVMStr;


public class JellyFish extends ProgramAnalysis<Void> {
    public static final String ID = "jelly-fish";
    private static final Logger logger = LogManager.getLogger(JellyFish.class);
    private static final AssertUtil as = new AssertUtil(logger);

    World world;
    ClassHierarchy classHierarchy;
    LLVMCodeGen codeGen;
    Mappings maps;


    public JellyFish(AnalysisConfig config) {
        super(config);
        logger.info("Jellyfish is a transpiler from Tai-e IR to LLVM IR.");
        this.world = World.get();
        this.classHierarchy = world.getClassHierarchy();
        this.codeGen = new LLVMCodeGen();
        this.maps = new Mappings();
    }

    @Override
    public Void analyze() {
        // Pass 1
        List<JClass> jclasses = classHierarchy.applicationClasses().collect(Collectors.toList());
        for (JClass jclass : jclasses) {
            String className = jclass.getName();
            String moduleName = jclass.getModuleName();
            String simpleName = jclass.getSimpleName();
            logger.info("Found class:name: {}, module name: {}, simple name:{}", className, moduleName, simpleName);

            // Class
            LLVMTypeRef llvmClass = getOrTranClass(jclass);
        }


        // Pass 2
        for (JClass jclass : maps.getAllClasses()) {
            // Class fields: fill in the types.
            this.tranClassFields(jclass);

            // Method Declarations
            Collection<JMethod> methods = jclass.getDeclaredMethods();
            for (JMethod jmethod : methods) {
                LLVMValueRef llvmMethod = this.tranMethod(jmethod);
            }
        }

        // Pass 3
        for (JMethod jmethod : maps.getAllMethods()) {
            this.tranMethodBody(jmethod);
        }

        codeGen.generate();
        return null;
    }

    /*
     * Mappings between Java and LLVM
     */
    public LLVMTypeRef getOrTranClass(JClass jclass) {
        Optional<LLVMTypeRef> llvmClass = maps.getClassMap(jclass);
        if (llvmClass.isPresent()) {
            LLVMTypeRef existClass = llvmClass.get();
            return existClass;
        } else {
            LLVMTypeRef newClass = this.tranClass(jclass);
            return newClass;
        }
    }

    public LLVMValueRef getOrTranMethod(JMethod jmethod) {
        Optional<LLVMValueRef> llvmMethod = maps.getMethodMap(jmethod);
        if (llvmMethod.isPresent()) {
            LLVMValueRef existMethod = llvmMethod.get();
            return existMethod;
        } else {
            LLVMValueRef newMethod = this.tranMethod(jmethod);
            return newMethod;
        }
    }

    public LLVMValueRef getOrTranStringLiteral(String str) {
        // TODO: support string pool
//        Optional<LLVMValueRef> opStrVal = maps.getStringPoolMap(str);
//        if(opStrVal.isPresent()) {
//            // Already in string pool
//            return opStrVal.get();
//        }
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
        Optional<LLVMValueRef> opInitMethod = maps.getMethodMap(jinitMethod);
        as.assertTrue(opInitMethod.isPresent(), "The init method should have been handled.");
        LLVMValueRef llvmInitMethod = opInitMethod.get();

        // 1.
        LLVMValueRef llvmStrConst = codeGen.buildConstString(str);
        LLVMTypeRef byteArrayType = getValueType(LLVM.LLVMGetParam(llvmInitMethod, 1));
        LLVMValueRef llvmStrConst2 = codeGen.buildTypeCast(llvmStrConst, byteArrayType);

        // 2.
        LLVMTypeRef llvmStringClass = tranTypeAlloc(stringClass.getType());
        LLVMValueRef llvmStrPtr = codeGen.buildMalloc(llvmStringClass);

        // 3.
        codeGen.buildCall(llvmInitMethod, List.of(llvmStrPtr, llvmStrConst2));
        return llvmStrPtr;
    }

    /*
     * Translations from Java to LLVM
     */

    public LLVMTypeRef tranClass(JClass jclass) {
        String className = StringUtil.getClassName(jclass);

        LLVMTypeRef classType = codeGen.buildNamedStruct(className);

        // A placeholder value to let the type stay in bitcode.
        String placeHolderValName = String.format("placeholder.%s", className);
        LLVMValueRef phValue = codeGen.addGlobalVariable(classType, placeHolderValName);
        LLVM.LLVMSetLinkage(phValue, LLVM.LLVMExternalLinkage);

        // Update mapping
        boolean ret = maps.setClassMap(jclass, classType);
        as.assertTrue(ret, "The jclass {} has been duplicate translated.", jclass);

        // After update: also translate the "relevant classes" by different types of reference
        // 1. Field reference
        Collection<JField> fields = jclass.getDeclaredFields();
        for (JField field : fields) {
            Type ftype = field.getType();
            if (ftype instanceof ClassType) {
                JClass fclass = ((ClassType) ftype).getJClass();
                LLVMTypeRef fllvmClass = getOrTranClass(fclass);
            }
        }

        // 2. Super-class reference
        List<JClass> superClasses = new ArrayList<>();
        JClass sclass = jclass.getSuperClass();
        while (sclass != null) {
            superClasses.add(sclass);
            sclass = sclass.getSuperClass();
        }

        for (JClass superClass : superClasses) {
            LLVMTypeRef llvmSClass = getOrTranClass(superClass);
        }

        // 3. Interface reference
        Collection<JClass> jinterfaces = jclass.getInterfaces();
        for (JClass jinterface : jinterfaces) {
            LLVMTypeRef llvmInterface = getOrTranClass(jinterface);
        }
        return classType;
    }

    public void tranClassFields(JClass jclass) {
        Optional<LLVMTypeRef> opllvmClass = maps.getClassMap(jclass);
        as.assertTrue(opllvmClass.isPresent(), "The class declaration of {} should have been translated.", jclass);
        LLVMTypeRef llvmClass = maps.getClassMap(jclass).get();
        Collection<JField> fields = jclass.getDeclaredFields();

        List<LLVMTypeRef> fieldTypes = new ArrayList<>();
        for (JField field : fields) {
            String fieldName = field.getName();
            Type ftype = field.getType();
            LLVMTypeRef fllvmType = tranType(ftype);
            if (field.isStatic()) {
                String staticFieldName = StringUtil.getStaticFieldName(jclass, field);
                LLVMValueRef fieldVar = codeGen.addGlobalVariable(fllvmType, staticFieldName);
                boolean ret = maps.setStaticFieldMap(field, fieldVar);
                as.assertTrue(ret, "The jfield {} has been duplicate translated.", field);
                continue;
            }
            fieldTypes.add(fllvmType);
        }
        codeGen.setStructFields(llvmClass, fieldTypes);
        return;
    }

    public LLVMTypeRef tranTypeAlloc(Type jType) {
        /*
         * Translate this type for allocation.
         * So the reference type will be not be transformed to pointer.
         */
        LLVMTypeRef type1 = tranType(jType);
        if (jType instanceof ReferenceType) {
            as.assertTrue(LLVM.LLVMGetTypeKind(type1) == LLVM.LLVMPointerTypeKind, "The type should be a pointer type. Got {}", getLLVMStr(type1));
            return LLVM.LLVMGetElementType(type1);
        } else {
            return type1;
        }
    }

    public LLVMTypeRef tranType(Type jType) {
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
                // A[][] => *[*[i32 * 0] * 0]
                Type jBaseType = ((ArrayType) jType).baseType();
                int dimension = ((ArrayType) jType).dimensions();

                LLVMTypeRef baseType = this.tranType(jBaseType);
                int arraySize = 0; // in java, array sizes are unknown.

                LLVMTypeRef curArrayPtrType = codeGen.buildPointerType(codeGen.buildArrayType(baseType, arraySize));
                for (int d = 1; d < dimension; d++) {
                    curArrayPtrType = codeGen.buildPointerType(codeGen.buildArrayType(curArrayPtrType, arraySize));
                }
                return curArrayPtrType;
            } else if (jType instanceof ClassType) {
                JClass jclass = ((ClassType) jType).getJClass();
                LLVMTypeRef llvmStruct = getOrTranClass(jclass);
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

    public LLVMValueRef tranMethod(JMethod jmethod) {
        JClass jclass = jmethod.getDeclaringClass();
        String methodName = StringUtil.getMethodName(jclass, jmethod);
        List<LLVMTypeRef> paramTypes = new ArrayList<>();
        if (!jmethod.isStatic()) {
            ClassType classType = jclass.getType();
            LLVMTypeRef llvmClassType = tranType(classType);
            paramTypes.add(llvmClassType);
        }
        for (Type jType : jmethod.getParamTypes()) {
            LLVMTypeRef type = tranType(jType);
            paramTypes.add(type);
        }
        Type jretType = jmethod.getReturnType();
        LLVMTypeRef retType = tranType(jretType);
        LLVMTypeRef funcType = codeGen.buildFunctionType(retType, paramTypes);
        LLVMValueRef func = codeGen.addFunction(funcType, methodName);
        boolean ret = maps.setMethodMap(jmethod, func);
        as.assertTrue(ret, "The method {} has been duplicate translated.", jmethod);

        return func;
    }

    public void tranMethodBody(JMethod jmethod) {
        if (!jmethod.getDeclaringClass().isApplication()) {
            // Debug use: Skip non application class
            return;
        }
        if (!jmethod.getName().equals("<clinit>")) {
            // Debug use: Skip non-class-init method
            return;
        }

        logger.info("*Method: {}. In Class: {}", jmethod.getName(), jmethod.getDeclaringClass());
        Optional<LLVMValueRef> opllvmFunc = maps.getMethodMap(jmethod);
        as.assertTrue(opllvmFunc.isPresent(), "The decl of jmethod {} should have be translated", jmethod);
        LLVMValueRef llvmFunc = opllvmFunc.get();

        // TODO: only one basic block.
        LLVMBasicBlockRef block = codeGen.addBasicBlock(llvmFunc, "");
        codeGen.setInsertBlock(block);

        IR ir = jmethod.getIR();
        List<Var> vars = ir.getVars();
        for (Var var : vars) {
            Type jvarType = var.getType();
            if (jvarType instanceof NullType) continue;
            LLVMTypeRef llvmVarType = tranType(jvarType);
            String llvmVarName = StringUtil.getVarNameAsPtr(var);
            LLVMValueRef alloca = codeGen.buildAlloca(llvmVarType, llvmVarName);
            boolean ret = maps.setVarMap(var, alloca);
            as.assertTrue(ret, "The var {} has been duplicate translated.", var);
        }

        List<Stmt> jstmts = ir.getStmts();
        for (Stmt jstmt : jstmts) {
            logger.info("**Stmt: {}", jstmt);
            List<LLVMValueRef> llvmInsts = this.tranStmt(jstmt, jmethod);
            List<String> llvmInstStrs = llvmInsts.stream().map(inst -> getLLVMStr(inst)).toList();
            for (String str : llvmInstStrs) logger.info("  => {}", str);
        }
        maps.clearVarMap();

    }

    @Nullable
    public List<LLVMValueRef> tranStmt(Stmt jstmt, JMethod jmethod) {
        List<LLVMValueRef> resInsts = new ArrayList<>();

        if (jstmt instanceof DefinitionStmt) { // Abstract
            if (jstmt instanceof AssignStmt) { // Abstract
                // We don't traverse each concrete assign types.
                // They are all translated to stores.

                LValue lvalue = ((AssignStmt<?, ?>) jstmt).getLValue();
                RValue rvalue = ((AssignStmt<?, ?>) jstmt).getRValue();

                if (lvalue.getType() instanceof NullType) {
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
                LLVMValueRef llvmValue = tranRValue(rvalue, llvmPtrElTy, false);
                LLVMValueRef store = codeGen.buildStore(llvmPtr, llvmValue);

                resInsts.addAll(of(llvmPtr, llvmValue, store));
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

                    resInsts.addAll(of(llvmPtr, llvmCall, store));
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
                if (jstmt instanceof LookupSwitch) {
                    // TODO:
                } else if (jstmt instanceof TableSwitch) {
                    // TODO:
                }
            } else if (jstmt instanceof Goto) {
                // TODO:
            } else if (jstmt instanceof If) {
                // TODO:
            }
        } else if (jstmt instanceof Return) {
            Var var = ((Return) jstmt).getValue();
            if (var == null) {
                LLVMValueRef ret = codeGen.buildRet(Optional.empty());
                resInsts.add(ret);
                return resInsts;
            } else {
                Type jretType = jmethod.getReturnType();
                LLVMTypeRef retType = tranType(jretType);
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
            // TODO:
        }
        as.unreachable("Unexpected statement: {}", jstmt);
        return null;
    }

    public LLVMValueRef tranRValue(RValue jexp, LLVMTypeRef outType, boolean autoCast) {
        /* Translate RValue with a specified out Type.
         * So that the resulting LLVM value would either be:
         * enforced with a type check (autoCast = false)
         * Automatically casted to the outType (autoCast = true)
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

    public Optional<LLVMValueRef> tranRValue(RValue jexp) {
        /* Translate RValue without a specified out Type.
         * So that we don't have a type assumption of the resulting LLVM value.
         * It returns null if the result is an "untyped null"
         */
        LLVMValueRef translateVal = tranRValueImpl(jexp, Optional.empty());
        return Optional.ofNullable(translateVal);
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
                        LLVMTypeRef llvmIntType = tranType(jIntType);
                        long intNumLong = ((IntLiteral) jexp).getNumber().longValue();
                        LLVMValueRef constInt = codeGen.buildConstInt(llvmIntType, intNumLong);
                        return constInt;
                    } else if (jexp instanceof LongLiteral) {
                        Type jlongType = jexp.getType();
                        LLVMTypeRef llvmLongType = tranType(jlongType);
                        long longNum = ((LongLiteral) jexp).getValue();
                        LLVMValueRef constInt = codeGen.buildConstInt(llvmLongType, longNum);
                        return constInt;
                    }
                } else if (jexp instanceof FloatingPointLiteral) { // Interface
                    if (jexp instanceof FloatLiteral) {
                        Type jfloatType = jexp.getType();
                        LLVMTypeRef llvmFloatType = tranType(jfloatType);
                        double floatNumDouble = ((FloatLiteral) jexp).getNumber().doubleValue();
                        LLVMValueRef constReal = codeGen.buildConstReal(llvmFloatType, floatNumDouble);
                        return constReal;
                    } else if (jexp instanceof DoubleLiteral) {
                        Type jdoubleType = jexp.getType();
                        LLVMTypeRef llvmDoubleType = tranType(jdoubleType);
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
                    // TODO:
                } else if (jexp instanceof MethodHandle) {
                    // TODO:
                } else if (jexp instanceof MethodType) {
                    // TODO:
                }
            }
        } else if (jexp instanceof FieldAccess) { // Abstract
            if (jexp instanceof StaticFieldAccess) {
                FieldRef fieldRef = ((StaticFieldAccess) jexp).getFieldRef();
                JField jfield = fieldRef.resolveNullable();
                if (jfield != null) {
                    Optional<LLVMValueRef> opfieldPtr = maps.getStaticFieldMap(jfield);
                    as.assertTrue(opfieldPtr.isPresent(), "The field {} should have been translated.", jfield);
                    LLVMValueRef ptr = opfieldPtr.get();
                    LLVMValueRef load = codeGen.buildLoad(ptr, jfield.getName());
                    return load;
                } else {
                    as.unreachable("The static field access {} contains null field", jexp);
                }
            } else if (jexp instanceof InstanceFieldAccess) {
                as.unimplemented();
                Var baseVar = ((InstanceFieldAccess) jexp).getBase();
                FieldRef fieldRef = ((InstanceFieldAccess) jexp).getFieldRef();
                JField jfield = fieldRef.resolveNullable();
                if (jfield != null) {
                    as.unimplemented();
                } else {
                    as.unreachable("The static field access {} contains null field", jexp);
                }
            }
        } else if (jexp instanceof UnaryExp) { // Interface
            if (jexp instanceof ArrayLengthExp) {
                Var arrayVar = ((ArrayLengthExp) jexp).getBase();
                Type resType = jexp.getType();

                LLVMValueRef llvmArrayPtr = tranRValue(arrayVar, codeGen.buildPointerType(codeGen.buildIntType(32)), true);
                return codeGen.buildLength(llvmArrayPtr);
            } else if (jexp instanceof NegExp) {
                // TODO:
            }
        } else if (jexp instanceof BinaryExp) { // Interface
            Type jresType = jexp.getType();
            LLVMTypeRef resType = tranType(jresType);

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
                Optional<LLVMValueRef> opleftVal = tranRValue(left);
                Optional<LLVMValueRef> oprightVal = tranRValue(right);
                LLVMTypeRef comparisonDefaultType = codeGen.buildIntType(32); // int
                Pair<LLVMValueRef, LLVMValueRef> unifiedOperands = codeGen.unifyValues(opleftVal, oprightVal, comparisonDefaultType);
                LLVMValueRef leftVal = unifiedOperands.first();
                LLVMValueRef rightVal = unifiedOperands.second();


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

                Optional<LLVMValueRef> opleftVal = tranRValue(left);
                Optional<LLVMValueRef> oprightVal = tranRValue(right);
                LLVMTypeRef conditionDefaultType = codeGen.buildIntType(32); // int
                Pair<LLVMValueRef, LLVMValueRef> unifiedOperands = codeGen.unifyValues(opleftVal, oprightVal, conditionDefaultType);
                LLVMValueRef leftVal = unifiedOperands.first();
                LLVMValueRef rightVal = unifiedOperands.second();

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
                LLVMTypeRef llvmObjType = tranTypeAlloc(objType);
                LLVMValueRef object = codeGen.buildMalloc(llvmObjType);
                return object;
            } else if (jexp instanceof NewArray) {
                ArrayType jarrayType = ((NewArray) jexp).getType();
                LLVMTypeRef newedType = tranType(jarrayType);
                Type jbaseType = jarrayType.baseType();
                as.assertFalse(jbaseType instanceof ArrayType, "Unexpected base type {}", jbaseType);

                LLVMTypeRef llvmBaseType = tranTypeAlloc(jbaseType);
                LLVMValueRef llvmBaseSizeConst = codeGen.buildSizeOf(llvmBaseType);

                Var lengthVar = ((NewArray) jexp).getLength();
                LLVMValueRef length = tranRValue((RValue) lengthVar, codeGen.buildIntType(32), true);
                LLVMValueRef object = codeGen.buildNewArray(llvmBaseSizeConst, List.of(length), newedType);
                return object;

            } else if (jexp instanceof NewMultiArray) {
                ArrayType jarrayType = ((NewMultiArray) jexp).getType();
                LLVMTypeRef newedType = tranType(jarrayType);
                Type jbaseType = jarrayType.baseType();
                as.assertFalse(jbaseType instanceof ArrayType, "Unexpected base type {}", jbaseType);

                LLVMTypeRef llvmBaseType = tranTypeAlloc(jbaseType);
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
                // TODO:
            } else if (jexp instanceof InvokeDynamic) {
                // TODO:
            } else if (jexp instanceof InvokeInstanceExp) { // Abstract
                if (jexp instanceof InvokeSpecial) {

                    Var thisVar = ((InvokeSpecial) jexp).getBase();

                    MethodRef methodRef = ((InvokeSpecial) jexp).getMethodRef();
                    JMethod callee = methodRef.resolveNullable();
                    if (callee != null) {
                        /*
                         * T-Invoke Special:
                         *     F(B1, B2...)
                         *   F::T1->T2  B::T3 B2...::T4
                         * ---------------------------
                         *     T3 = T1   T4 = T2
                         */
                        LLVMValueRef llvmCallee = getOrTranMethod(callee);
                        as.assertTrue(LLVM.LLVMCountParams(llvmCallee) == ((InvokeSpecial) jexp).getArgCount() + 1,
                                "Argument number doesn't match. LLVM func: {}, java invoke: {}", getLLVMStr(llvmCallee), jexp);

                        LLVMValueRef llvm0thParam = LLVM.LLVMGetParam(llvmCallee, 0);
                        LLVMValueRef llvmThis = tranRValue(thisVar, getValueType(llvm0thParam), true);
                        List<LLVMValueRef> args = new ArrayList<>();
                        args.add(llvmThis);

                        List<Var> jargs = ((InvokeSpecial) jexp).getArgs();
                        for (int i = 1; i < jargs.size() + 1; i++) {
                            LLVMValueRef llvmIthParam = LLVM.LLVMGetParam(llvmCallee, i);
                            Var jarg = jargs.get(i - 1);
                            LLVMValueRef llvmArg = tranRValue(jarg, getValueType(llvmIthParam), true);
                            args.add(llvmArg);
                        }
                        LLVMValueRef call = codeGen.buildCall(llvmCallee, args);
                        return call;
                    } else {
                        // TODO: unknown calling target
                        as.unimplemented();
                        return null;
                    }
                } else if (jexp instanceof InvokeInterface) {
                    // TODO:
                } else if (jexp instanceof InvokeVirtual) {
                    // TODO:
                }
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
            LLVMTypeRef elementType = tranType(jelementType);   // ::F2

            LLVMValueRef base = tranRValue(baseVar, codeGen.buildPointerType(codeGen.buildArrayType(elementType, 0)), true);     // ::F1

            LLVMValueRef index1 = codeGen.buildConstInt(codeGen.buildIntType(64), 0);
            LLVMValueRef index2 = tranRValue(indexVar, codeGen.buildIntType(64), true);
            LLVMValueRef gep = codeGen.buildGEP(base, List.of(index1, index2));
            LLVMValueRef load = codeGen.buildLoad(gep, StringUtil.getVarNameAsLoad(baseVar));
            return load;

        } else if (jexp instanceof CastExp) {
            // TODO:
        } else if (jexp instanceof InstanceOfExp) {
            // TODO:
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
                as.unimplemented();
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
            LLVMTypeRef elementType = tranType(jelementType);   // ::F2

            LLVMValueRef base = tranRValue(baseVar, codeGen.buildPointerType(codeGen.buildArrayType(elementType, 0)), true);   // ::F1

            LLVMValueRef index1 = codeGen.buildConstInt(codeGen.buildIntType(64), 0);
            LLVMValueRef index2 = tranRValue(indexVar, codeGen.buildIntType(64), true);
            List<LLVMValueRef> indices = List.of(index1, index2);
            LLVMValueRef gep = codeGen.buildGEP(base, indices);
            return gep;
        }
        as.unimplemented();
        return null;
    }

}
