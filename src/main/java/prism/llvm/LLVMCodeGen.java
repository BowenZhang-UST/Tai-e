package prism.llvm;

import org.bytedeco.llvm.global.LLVM;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import prism.jellyfish.util.ArrayBuilder;

import java.lang.reflect.Array;
import java.util.List;


public class LLVMCodeGen {
    /*
     * Provide manipulations to a LLVM module.
     */
    String moduleName;
    String bcFile;
    LLVMContextRef context;
    LLVMModuleRef module;
    LLVMBuilderRef builder;

    public LLVMCodeGen() {
        this.moduleName = "module";
        this.bcFile = "out.bc";
        this.context = LLVM.LLVMContextCreate();
        this.module = LLVM.LLVMModuleCreateWithNameInContext(moduleName, context);
        this.builder = LLVM.LLVMCreateBuilderInContext(context);
    }

    /*
     * Top-level commands
     */
    public void generate() {
        LLVM.LLVMWriteBitcodeToFile(module, bcFile);
    }


    /*
     * Module manipulation operations
     * 1. Add IR elements into the module.
     * 2. Retrieve IR elements from the module.
     */
    // public addClass(LLVMTypeRef classType) {
    //     module.
    // }

    public LLVMValueRef addGlobalVariable(String name, LLVMTypeRef type) {
        return LLVM.LLVMAddGlobal(module, type, name);
    }

    public LLVMValueRef addFunction(String funcName, LLVMTypeRef funcType) {
        LLVMValueRef func = LLVM.LLVMAddFunction(module, funcName, funcType);
        return func;
    }

    /*
     * Builders:
     * build a certain IR element
     * using the elements in the module.
     * This element will later be added to the module.
     */


    /*
     * Type Builders
     */
    public LLVMTypeRef buildFunctionType(LLVMTypeRef retType, List<LLVMTypeRef> paramTypes) {
        ArrayBuilder<LLVMTypeRef> paramTypeArray = new ArrayBuilder<>(paramTypes);
        LLVMTypeRef funcType = LLVM.LLVMFunctionType(retType, paramTypeArray.build(), paramTypeArray.length(), 0);
        return funcType;
    }

    public LLVMTypeRef buildNamedStruct(String name) {
        return LLVM.LLVMStructCreateNamed(context, name);
    }

    public void setStructFields(LLVMTypeRef struct, List<LLVMTypeRef> fieldTypes) {
        ArrayBuilder<LLVMTypeRef> fieldTypeArray = new ArrayBuilder<>(fieldTypes);
        LLVM.LLVMStructSetBody(struct, fieldTypeArray.build(), fieldTypeArray.length(), 0);
        return;
    }

    public LLVMTypeRef buildVoidType() {
        return LLVM.LLVMVoidType();
    }

    public LLVMTypeRef buildIntType(int bits) {
        return LLVM.LLVMIntType(bits);
    }

    public LLVMTypeRef buildFloatType() {
        return LLVM.LLVMFloatType();
    }

    public LLVMTypeRef buildDoubleType() {
        return LLVM.LLVMDoubleType();
    }

    public LLVMTypeRef buildArrayType(LLVMTypeRef baseType, int length) {
        return LLVM.LLVMArrayType(baseType, length);
    }


}
