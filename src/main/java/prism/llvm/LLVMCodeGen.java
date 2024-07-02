package prism.llvm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.llvm.LLVM.*;
import org.bytedeco.llvm.global.LLVM;
import prism.jellyfish.JellyFish;
import prism.jellyfish.util.ArrayBuilder;
import prism.jellyfish.util.AssertUtil;

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

    private static final Logger logger = LogManager.getLogger(LLVMCodeGen.class);
    private static final AssertUtil as = new AssertUtil(logger);

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

    public LLVMValueRef addGlobalVariable(LLVMTypeRef type, String name) {
        return LLVM.LLVMAddGlobal(module, type, name);
    }

    public LLVMValueRef addFunction(LLVMTypeRef funcType, String funcName) {
        LLVMValueRef func = LLVM.LLVMAddFunction(module, funcName, funcType);
        return func;
    }

    public LLVMBasicBlockRef addBasicBlock(LLVMValueRef func, String blockName) {
        LLVMBasicBlockRef blockRef = LLVM.LLVMAppendBasicBlock(func, blockName);
        return blockRef;
    }

    public void setInsertBlock(LLVMBasicBlockRef block) {
        LLVM.LLVMPositionBuilderAtEnd(builder, block);
    }

    public void addInst(LLVMBasicBlockRef block, LLVMValueRef inst) {
        // Add instruction to the current inserting basic block
        LLVMBasicBlockRef insertBlock = LLVM.LLVMGetInsertBlock(builder);
        as.assertTrue(insertBlock == block, "The inserting block does not match the specified one.");
        LLVM.LLVMInsertIntoBuilder(builder, inst);
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

    /*
     * Value Builders
     */

    public LLVMValueRef buildConstInt(LLVMTypeRef intType, long N) {
        LLVMValueRef constInt = LLVM.LLVMConstInt(intType, N, 1);
        return constInt;
    }

    public LLVMValueRef buildAlloca(LLVMTypeRef type, String name) {
        LLVMValueRef alloca = LLVM.LLVMBuildAlloca(builder, type, name);
        return alloca;
    }

    public LLVMValueRef buildStore(LLVMValueRef ptr, LLVMValueRef value) {
        LLVMValueRef store = LLVM.LLVMBuildStore(builder, value, ptr);
        return store;
    }

    public LLVMValueRef buildLoad(LLVMValueRef ptr, String name) {
        LLVMValueRef load = LLVM.LLVMBuildLoad(builder, ptr, name);
        return load;
    }

    public LLVMValueRef buildRet(LLVMValueRef retVal) {
        if (retVal == null) {
            LLVMValueRef ret = LLVM.LLVMBuildRetVoid(builder);
            return ret;
        } else {
            LLVMValueRef ret = LLVM.LLVMBuildRet(builder, retVal);
            return ret;
        }
    }


}
