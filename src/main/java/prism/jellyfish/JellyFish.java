package prism.jellyfish;


import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.config.AnalysisConfig;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.*;


import java.util.*;
import java.util.stream.Collectors;


import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;

import prism.jellyfish.util.AssertUtil;
import prism.llvm.LLVMCodeGen;
import prism.jellyfish.util.StringUtil;


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
//            logger.info("Found class:\n name: {}, module name: {}, simple name:{}", className, moduleName, simpleName);

            // Class
            LLVMTypeRef llvmClass = getOrTranClass(jclass);

            // Methods
            Collection<JMethod> methods = jclass.getDeclaredMethods();
            for (JMethod jmethod : methods) {
                LLVMValueRef llvmMethod = this.tranMethod(jmethod);
//                     String methodName = method.getName();
                //     logger.info("    Method: {}", methodName);
                //     IR ir = method.getIR();
                //     for(Stmt stmt :ir.getStmts()) {
                //         int ln = stmt.getLineNumber();
                //         logger.info("        Stmt: {} ({})", stmt, ln);
                //     }
            }
        }


        // Pass 2
        for (JClass jclass : maps.getAllClasses()) {
            // Class fields: fill in the types.
            this.tranClassFields(jclass);
        }
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

    /*
     * Translations from Java to LLVM
     */

    public LLVMTypeRef tranClass(JClass jclass) {
        String className = StringUtil.getClassName(jclass);
//        logger.info("Handle class: {}", className);

        LLVMTypeRef classType = codeGen.buildNamedStruct(className);

        // A placeholder value to let the type stay in bitcode.
        String placeHolderValName = String.format("placeholder.%s", className);
        LLVMValueRef phValue = codeGen.addGlobalVariable(classType, placeHolderValName);
        LLVM.LLVMSetLinkage(phValue, LLVM.LLVMWeakODRLinkage);

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
//        logger.info("    Superclasses: {}", superClasses);
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

    public LLVMTypeRef tranType(Type jType) {
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
                Type jBaseType = ((ArrayType) jType).baseType();
                Type jElementType = ((ArrayType) jType).elementType();
                int dimension = ((ArrayType) jType).dimensions();

                LLVMTypeRef baseType = this.tranType(jBaseType);
                int arraySize = 0; // in java, array sizes are unknown.

                LLVMTypeRef curArrayType = codeGen.buildArrayType(baseType, arraySize);
                for (int d = 1; d < dimension; d++) {
                    curArrayType = codeGen.buildArrayType(curArrayType, arraySize);
                }
                LLVMTypeRef llvmArrayType = curArrayType;
                return llvmArrayType;
            } else if (jType instanceof ClassType) {
                JClass jclass = ((ClassType) jType).getJClass();
                LLVMTypeRef llvmClass = getOrTranClass(jclass);
                return llvmClass;
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
            LLVMTypeRef llvmClassType = getOrTranClass(jclass);
            paramTypes.add(llvmClassType);
        }
        for (Type jType : jmethod.getParamTypes()) {
            LLVMTypeRef type = tranType(jType);
            paramTypes.add(type);
        }
        Type jRetType = jmethod.getReturnType();
        LLVMTypeRef retType = tranType(jRetType);
        LLVMTypeRef funcType = codeGen.buildFunctionType(retType, paramTypes);
        LLVMValueRef func = codeGen.addFunction(funcType, methodName);
        boolean ret = maps.setMethodMap(jmethod, func);
        as.assertTrue(ret, "The method {} has been duplicate translated.", jmethod);

        return func;
    }

    public void tranMethodBody(JMethod jmethod) {
        if (!jmethod.getName().equals("<clinit>")) { // Debug use
            return;
        }
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
            LLVMValueRef llvmInst = this.tranStmt(jstmt);
        }
        maps.clearVarMap();

    }

    public LLVMValueRef tranStmt(Stmt jstmt) {
        logger.info("**Stmt: {}. {}", jstmt.getClass(), jstmt);

        if (jstmt instanceof DefinitionStmt) { // Abstract
            if (jstmt instanceof AssignStmt) { // Abstract
                // We don't traverse each concrete assign types.
                // They are all translated to stores.

                LValue lvalue = ((AssignStmt<?, ?>) jstmt).getLValue();
                RValue rvalue = ((AssignStmt<?, ?>) jstmt).getRValue();

                if (lvalue.getType() instanceof NullType) {
                    return codeGen.buildNop();
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
                LLVMValueRef llvmValue = tranRValue(rvalue, llvmPtrElTy);

                LLVMTypeRef llvmValueType = getValueType(llvmValue);
                as.assertTrue(llvmPtrElTy.equals(llvmValueType),
                        "Typing: The pointer element type should == the value type.\n" +
                                "LVal: [{}]\n" +
                                "Ptr: [{}].\n" +
                                "RVal: [{}]\n " +
                                "Casted Val: [{}]",
                        lvalue,
                        getLLVMStr(llvmPtr),
                        rvalue,
                        getLLVMStr(llvmValueType));

                LLVMValueRef store = codeGen.buildStore(llvmPtr, llvmValue);
                return store;
            } else if (jstmt instanceof Invoke) {
                // TODO:
            }
        } else if (jstmt instanceof JumpStmt) { // Abstract
            if (jstmt instanceof SwitchStmt) { // Abstract
                if (jstmt instanceof LookupSwitch) {
                    // TODO:
                } else if (jstmt instanceof TableSwitch) {
                    // TODO:
                }
            } else if (jstmt instanceof Goto) {
                // TODO
            } else if (jstmt instanceof If) {
                // TODO:
            }
        } else if (jstmt instanceof Return) {
            Var var = ((Return) jstmt).getValue();
            if (var == null) {
                LLVMValueRef ret = codeGen.buildRet(null);
                return ret;
            } else {
                LLVMValueRef retVal = tranRValue(var);
                LLVMValueRef ret = codeGen.buildRet(retVal);
                return ret;
            }
        } else if (jstmt instanceof Nop) {
            LLVMValueRef nop = codeGen.buildNop();
            return nop;
        } else if (jstmt instanceof Monitor) {
            // TODO:
        }
        as.unimplemented();
        return null;
    }

    public LLVMValueRef tranRValue(RValue jexp, LLVMTypeRef... outTy) {
        as.assertTrue(outTy.length <= 1, "Only one outType for typing, got {}", Arrays.stream(outTy).toList());
        Optional<LLVMTypeRef> outType = Optional.ofNullable(null);
        if (outTy.length == 1) {
            outType = Optional.of(outTy[0]);
        }

        LLVMValueRef translatedVal = tranRValueImpl(jexp, outType);

        // Enforce typing rule at top-level.
        if (outType.isPresent()) {
            return codeGen.buildTypeCast(translatedVal, outType.get());
        } else {
            return translatedVal;
        }
    }

    public LLVMValueRef tranRValueImpl(RValue jexp, Optional<LLVMTypeRef> outType) {
        /*
         * We specify one outType for typing. It requires the translated value of jexp should have a type.
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
                    as.assertTrue(outType.isPresent(), "We should specify a type for null literal");
                    LLVMValueRef llvmNull = codeGen.buildNull(outType.get());
                    return llvmNull;
                } else if (jexp instanceof ClassLiteral) {
                    // TODO:
                } else if (jexp instanceof StringLiteral) {
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
                // TODO:
            } else if (jexp instanceof NegExp) {
                // TODO:
            }
        } else if (jexp instanceof BinaryExp) { // Interface
            if (jexp instanceof ArithmeticExp) {
                // TODO:
                Type jresType = jexp.getType();
                LLVMTypeRef resType = tranType(jresType);

                Var left = ((ArithmeticExp) jexp).getOperand1();
                Var right = ((ArithmeticExp) jexp).getOperand2();
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

                LLVMValueRef leftVal = tranRValue(left, resType);
                LLVMValueRef rightVal = tranRValue(right, resType);

                LLVMValueRef binOp = codeGen.buildBinaryOp(opStr, leftVal, rightVal, resType);
                return binOp;

            } else if (jexp instanceof BitwiseExp) {
                // TODO:
            } else if (jexp instanceof ComparisonExp) {
                // TODO:
            } else if (jexp instanceof ConditionExp) {
                // TODO:
            } else if (jexp instanceof ShiftExp) {
                // TODO:
            }
        } else if (jexp instanceof NewExp) { // Interface
            if (jexp instanceof NewArray) {
                // TODO:
            } else if (jexp instanceof NewInstance) {
                // TODO:
            } else if (jexp instanceof NewMultiArray) {
                // TODO:
            }
        } else if (jexp instanceof InvokeExp) {
            // TODO:
            if (jexp instanceof InvokeStatic) {
                // TODO:
            } else if (jexp instanceof InvokeInstanceExp) { // Abstract
                if (jexp instanceof InvokeDynamic) {
                    // TODO:
                } else if (jexp instanceof InvokeInterface) {
                    // TODO:
                } else if (jexp instanceof InvokeVirtual) {
                    // TODO:
                }
            }
        } else if (jexp instanceof Var) {
            if (jexp.getType() instanceof NullType) {
                as.assertTrue(outType.isPresent(), "We need the outType to generate a 'typed' null value");
                LLVMValueRef typedNullVal = codeGen.buildNull(outType.get());
                return typedNullVal;
            } else {
                Optional<LLVMValueRef> opvarPtr = maps.getVarMap((Var) jexp);
                as.assertTrue(opvarPtr.isPresent(), "The variable {} has not been correctly handled", (Var) jexp);
                LLVMValueRef ptr = opvarPtr.get();
                LLVMValueRef llvmVal = codeGen.buildLoad(ptr, StringUtil.getVarNameAsLoad((Var) jexp));
                return llvmVal;
            }
        } else if (jexp instanceof ArrayAccess) {
            // TODO:
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
            // TODO:
        }
        as.unimplemented();
        return null;
    }

}
