package prism.llvm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.llvm.LLVM.*;
import org.bytedeco.llvm.global.LLVM;
import prism.jellyfish.JellyFish;
import prism.jellyfish.util.ArrayBuilder;
import prism.jellyfish.util.AssertUtil;


import static prism.llvm.LLVMUtil.getValueType;
import static prism.llvm.LLVMUtil.getLLVMStr;

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
     * Literal Builders
     */

    public LLVMValueRef buildConstInt(LLVMTypeRef intType, long N) {
        LLVMValueRef constInt = LLVM.LLVMConstInt(intType, N, 1);
        return constInt;
    }

    public LLVMValueRef buildConstReal(LLVMTypeRef realType, double N) {
        LLVMValueRef constReal = LLVM.LLVMConstReal(realType, N);
        return constReal;
    }

    public LLVMValueRef buildNull(LLVMTypeRef type) {
        LLVMValueRef llvmNull = LLVM.LLVMConstNull(type);
        return llvmNull;
    }

    /*
     * Instruction Builders
     */

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

    public LLVMValueRef buildBinaryOp(String op, LLVMValueRef left, LLVMValueRef right, LLVMTypeRef resType) {
        // Typing:
        LLVMTypeRef lType = getValueType(left);
        LLVMTypeRef rType = getValueType(right);
        as.assertTrue(lType.equals(resType),
                "Typing: The left type of value {} doesn't match result type {}",
                getLLVMStr(left),
                getLLVMStr(resType));
        as.assertTrue(rType.equals(resType),
                "Typing: right left type of value {} doesn't match result type {}",
                getLLVMStr(right),
                getLLVMStr(resType));

        int resKind = LLVM.LLVMGetTypeKind(resType);

        if (op.equals("+")) {
            if (resKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef add = LLVM.LLVMBuildAdd(builder, left, right, "add");
                return add;
            } else if (resKind == LLVM.LLVMFloatTypeKind || resKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fadd = LLVM.LLVMBuildFAdd(builder, left, right, "fadd");
                return fadd;
            } else {
                as.unreachable("Typing: cannot create ADD operation for type {}", getLLVMStr(resType));
                return null;
            }
        } else if (op.equals("-")) {
            if (resKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef sub = LLVM.LLVMBuildSub(builder, left, right, "sub");
                return sub;
            } else if (resKind == LLVM.LLVMFloatTypeKind || resKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fsub = LLVM.LLVMBuildFSub(builder, left, right, "fsub");
                return fsub;
            } else {
                as.unreachable("Typing: cannot create SUB operation for type {}", getLLVMStr(resType));
                return null;
            }
        } else if (op.equals("*")) {
            if (resKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef mul = LLVM.LLVMBuildMul(builder, left, right, "mul");
                return mul;
            } else if (resKind == LLVM.LLVMFloatTypeKind || resKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fmul = LLVM.LLVMBuildFMul(builder, left, right, "fmul");
                return fmul;
            } else {
                as.unreachable("Typing: cannot create MUL operation for type {}", getLLVMStr(resType));
                return null;
            }
        } else if (op.equals("/")) {
            if (resKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef sdiv = LLVM.LLVMBuildSDiv(builder, left, right, "sdiv");
                return sdiv;
            } else if (resKind == LLVM.LLVMFloatTypeKind || resKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fdiv = LLVM.LLVMBuildFDiv(builder, left, right, "fdiv");
                return fdiv;
            } else {
                as.unreachable("Typing: cannot create DIV operation for type {}", getLLVMStr(resType));
                return null;
            }
        } else if (op.equals("%")) {
            if (resKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef srem = LLVM.LLVMBuildSRem(builder, left, right, "srem");
                return srem;
            } else if (resKind == LLVM.LLVMFloatTypeKind || resKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef frem = LLVM.LLVMBuildFRem(builder, left, right, "frem");
                return frem;
            } else {
                as.unreachable("Typing: cannot create REM operation for type {}", getLLVMStr(resType));
                return null;
            }
        } else {
            as.unreachable("Unexpected op {}", op);
            return null;
        }
    }

    public LLVMValueRef buildBitCast(LLVMValueRef val, LLVMTypeRef targetType) {
        LLVMValueRef cast = LLVM.LLVMBuildBitCast(builder, val, targetType, "bitcast");
        return cast;
    }

    public LLVMValueRef buildTypeCast(LLVMValueRef val, LLVMTypeRef targetType) {
        LLVMTypeRef sourceType = getValueType(val);
        if (sourceType.equals(targetType)) {
            return val;
        }
        int srcKind = LLVM.LLVMGetTypeKind(sourceType);
        int tgtKind = LLVM.LLVMGetTypeKind(targetType);

        if (srcKind == LLVM.LLVMPointerTypeKind && tgtKind == LLVM.LLVMPointerTypeKind) {
            LLVMValueRef cast = LLVM.LLVMBuildBitCast(builder, val, targetType, "ptr2ptr");
            return cast;
        } else if (srcKind == LLVM.LLVMIntegerTypeKind && tgtKind == LLVM.LLVMIntegerTypeKind) {
            LLVMValueRef cast = LLVM.LLVMBuildIntCast(builder, val, targetType, "int2int");
            return cast;
        } else if ((srcKind == LLVM.LLVMFloatTypeKind || srcKind == LLVM.LLVMDoubleTypeKind) &&
                (tgtKind == LLVM.LLVMFloatTypeKind || tgtKind == LLVM.LLVMDoubleTypeKind)) {
            LLVMValueRef cast = LLVM.LLVMBuildFPCast(builder, val, targetType, "real2real");
        } else if (srcKind == LLVM.LLVMIntegerTypeKind && (tgtKind == LLVM.LLVMFloatTypeKind || tgtKind == LLVM.LLVMDoubleTypeKind)) {
            LLVMValueRef cast = LLVM.LLVMBuildSIToFP(builder, val, targetType, "int2real");
            return cast;
        } else if ((srcKind == LLVM.LLVMFloatTypeKind || srcKind == LLVM.LLVMDoubleTypeKind) && tgtKind == LLVM.LLVMIntegerTypeKind) {
            LLVMValueRef cast = LLVM.LLVMBuildFPToSI(builder, val, targetType, "real2int");
            return cast;
        } else if (srcKind == LLVM.LLVMIntegerTypeKind && tgtKind == LLVM.LLVMPointerTypeKind) {
            LLVMValueRef cast = LLVM.LLVMBuildIntToPtr(builder, val, targetType, "int2ptr");
            return cast;
        } else if (srcKind == LLVM.LLVMPointerTypeKind || tgtKind == LLVM.LLVMIntegerTypeKind) {
            LLVMValueRef cast = LLVM.LLVMBuildPtrToInt(builder, val, targetType, "ptr2int");
            return cast;
        }
        as.unreachable("Unexpected source type: {}, target type {}", getLLVMStr(sourceType), getLLVMStr(targetType));
        return null;
    }

    public LLVMValueRef buildNop() {
        // TODO: change it to call.
        return LLVM.LLVMConstNull(LLVM.LLVMInt1Type());
    }

}
