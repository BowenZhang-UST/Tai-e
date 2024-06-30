package prism.jellyfish;


import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.ir.IR;
import pascal.taie.ir.stmt.Stmt;
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
import prism.jellyfish.util.ArrayBuilder;
import prism.jellyfish.util.StringUtil;

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

        List<JClass> jclasses = classHierarchy.applicationClasses().collect(Collectors.toList());
        for (JClass jclass : jclasses) {
            String className = jclass.getName();
            String moduleName = jclass.getModuleName();
            String simpleName = jclass.getSimpleName();
            logger.info("Found class:\n name: {}, module name: {}, simple name:{}", className, moduleName, simpleName);

            LLVMTypeRef llvmClass = this.tranClass(jclass);


            Collection<JMethod> methods = jclass.getDeclaredMethods();
            for (JMethod jmethod : methods) {
                LLVMValueRef llvmMethod = this.tranMethod(jclass, jmethod);
                //     String methodName = method.getName();
                //     logger.info("    Method: {}", methodName);
                //     IR ir = method.getIR();
                //     for(Stmt stmt :ir.getStmts()) {
                //         int ln = stmt.getLineNumber();
                //         logger.info("        Stmt: {} ({})", stmt, ln);
                //     }
            }

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
            LLVMTypeRef newClass = tranClass(jclass);
            return newClass;
        }
    }

    /*
     * Translations from Java to LLVM
     */

    public LLVMTypeRef tranClass(JClass jclass) {
        String className = StringUtil.getClassName(jclass);

        LLVMTypeRef classType = codeGen.buildNamedStruct(className);

        // A place holder value to let the type stay in bitcode.
        String placeHolderValName = String.format("placeholder.%s", className);
        LLVMValueRef phValue = codeGen.addGlobalVariable(placeHolderValName, classType);
        LLVM.LLVMSetLinkage(phValue, LLVM.LLVMWeakODRLinkage);

        Collection<JField> fields = jclass.getDeclaredFields();
        // TODO: handle fields

        boolean ret = maps.setClassMap(jclass, classType);
        as.assertTrue(ret, String.format("The jclass %s has been duplicate translated.", className));

        return classType;
    }

    public LLVMValueRef tranMethod(JClass jclass, JMethod jmethod) {
        String methodName = StringUtil.getMethodName(jclass, jmethod);
        List<LLVMTypeRef> paramTypes = new ArrayList<>();
        for (Type jType : jmethod.getParamTypes()) {
            LLVMTypeRef type = tranType(jType);
            paramTypes.add(type);
        }
        Type jRetType = jmethod.getReturnType();
        LLVMTypeRef retType = tranType(jRetType);
        LLVMTypeRef funcType = codeGen.buildFunctionType(retType, paramTypes);
        LLVMValueRef func = codeGen.addFunction(methodName, funcType);
        return func;
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
                logger.info("Array, base:{}, element:{}, dimension:{}", jBaseType, jElementType, dimension);
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
        as.unreachable("All types should be considered except: " + jType);
        return null;
    }

}
