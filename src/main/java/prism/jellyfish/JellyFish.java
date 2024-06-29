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
import pascal.taie.language.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Collection;


import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;

import prism.llvm.LLVMCodeGen;
import prism.jellyfish.util.ArrayBuilder;
import prism.jellyfish.util.StringUtil;

public class JellyFish extends ProgramAnalysis<Void>  {
    public static final String ID = "jelly-fish";
    private static final Logger logger = LogManager.getLogger(JellyFish.class);

    World world;
    ClassHierarchy classHierarchy;
    LLVMCodeGen codeGen;


    public JellyFish(AnalysisConfig config) {
        super(config);
        logger.info("Jellyfish is a transpiler from Tai-e IR to LLVM IR.");
        this.world =  World.get();
        this.classHierarchy = world.getClassHierarchy();
        this.codeGen = new LLVMCodeGen();
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
            for(JMethod jmethod : methods) {
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


    public LLVMTypeRef tranClass(JClass jclass) {
        String className = StringUtil.getClassName(jclass);

        LLVMTypeRef classType = codeGen.buildNamedStruct(className);

        // A place holder value to let the type stay in bitcode.
        String placeHolderValName = String.format("placeholder.%s", className);
        LLVMValueRef phValue = codeGen.addGlobalVariable(placeHolderValName, classType);
        LLVM.LLVMSetLinkage(phValue, LLVM.LLVMWeakODRLinkage);

        Collection<JField> fields = jclass.getDeclaredFields();
        // TODO: handle fields

        return classType;
    }

    public LLVMValueRef tranMethod(JClass jclass, JMethod jmethod) {
        String methodName = StringUtil.getMethodName(jclass, jmethod);
        List<LLVMTypeRef> paramTypes = new ArrayList<>();
        for(Type jType: jmethod.getParamTypes()) {
            LLVMTypeRef type = codeGen.buildIntType(64);
            paramTypes.add(type);
        }
        LLVMTypeRef retType = codeGen.buildIntType(64);
        LLVMTypeRef funcType = codeGen.buildFunctionType(retType, paramTypes);
        LLVMValueRef func = codeGen.addFunction(methodName, funcType);
        return func;
    }

}
