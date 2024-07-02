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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Collection;
import java.util.Optional;


import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;

import prism.jellyfish.util.AssertUtil;
import prism.llvm.LLVMCodeGen;
import prism.jellyfish.util.StringUtil;

import javax.swing.text.html.Option;

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
                LLVMTypeRef llvmCharnType = codeGen.buildIntType(16);
                return llvmCharnType;
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
                as.unreachable("Null type is not a real type");
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
        logger.info("Stmt: {}. {}", jstmt.getClass(), jstmt);

        if (jstmt instanceof DefinitionStmt) { // Abstract
            if (jstmt instanceof AssignStmt) { // Abstract
                if (jstmt instanceof AssignLiteral) {
                    Var var = ((AssignLiteral) jstmt).getLValue();
                    Literal lit = ((AssignLiteral) jstmt).getRValue();
                    LLVMValueRef ptr = tranLValue(var);
                    LLVMValueRef litVal = tranRValue(lit);
                    LLVMValueRef store = codeGen.buildStore(ptr, litVal);
                    return store;
                } else if (jstmt instanceof FieldStmt) { // Abstract
                    if (jstmt instanceof StoreField) {
                        Var var = ((StoreField) jstmt).getRValue();
                        LLVMValueRef varVal = tranRValue(var);

                        FieldAccess fieldAccess = ((StoreField) jstmt).getLValue();
                        LLVMValueRef ptr = tranLValue(fieldAccess);

                        LLVMValueRef store = codeGen.buildStore(ptr, varVal);
                        return store;
                    }
                }
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
        }
        as.unimplemented();
        return null;
    }

    public LLVMValueRef tranRValue(RValue jexp) {
        if (jexp instanceof Literal) { // Interface
            if (jexp instanceof IntLiteral) {
                Integer intNum = ((IntLiteral) jexp).getNumber();
                long intNumLong = intNum.longValue();
                Type jIntType = jexp.getType();
                LLVMTypeRef llvmIntType = tranType(jIntType);
                LLVMValueRef constInt = codeGen.buildConstInt(llvmIntType, intNumLong);
                return constInt;
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

                }
            }
        } else if (jexp instanceof Var) {
            Optional<LLVMValueRef> opvarPtr = maps.getVarMap((Var) jexp);
            as.assertTrue(opvarPtr.isPresent(), "The variable {} has not been correctly handled", (Var) jexp);
            LLVMValueRef ptr = opvarPtr.get();
            LLVMValueRef llvmVal = codeGen.buildLoad(ptr, StringUtil.getVarNameAsLoad((Var) jexp));
            return llvmVal;
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
            Optional<LLVMValueRef> opVarPtr = maps.getVarMap((Var) jexp);
            as.assertTrue(opVarPtr.isPresent(), "The variable {} has not been correctly handled", (Var) jexp);
            LLVMValueRef ptr = opVarPtr.get();
            return ptr;
        }
        as.unimplemented();
        return null;
    }

}
