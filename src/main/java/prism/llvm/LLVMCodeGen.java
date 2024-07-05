package prism.llvm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.llvm.LLVM.*;
import org.bytedeco.llvm.global.LLVM;
import pascal.taie.util.collection.Pair;
import prism.jellyfish.JellyFish;
import prism.jellyfish.util.ArrayBuilder;
import prism.jellyfish.util.AssertUtil;


import static prism.llvm.LLVMUtil.getValueType;
import static prism.llvm.LLVMUtil.getLLVMStr;

import java.util.List;
import java.util.Optional;


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
        LLVMTypeRef lType = getValueType(left);
        LLVMTypeRef rType = getValueType(right);
        int leftOpKind = LLVM.LLVMGetTypeKind(lType);
        int rightOpKind = LLVM.LLVMGetTypeKind(rType);

        LLVMTypeRef operandType = lType.equals(rType) ? lType : null;
        Integer operandKind = lType.equals(rType) ? leftOpKind : null;


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
        } else if (op.equals("&")) {
            as.assertTrue(resKind == LLVM.LLVMIntegerTypeKind, "Typing: The result type should be integer. Got {}", getLLVMStr(resType));
            LLVMValueRef and = LLVM.LLVMBuildAnd(builder, left, right, "and");
            return and;
        } else if (op.equals("|")) {
            as.assertTrue(resKind == LLVM.LLVMIntegerTypeKind, "Typing: The result type should be integer. Got {}", getLLVMStr(resType));
            LLVMValueRef or = LLVM.LLVMBuildOr(builder, left, right, "or");
            return or;
        } else if (op.equals("^")) {
            as.assertTrue(resKind == LLVM.LLVMIntegerTypeKind, "Typing: The result type should be integer. Got {}", getLLVMStr(resType));
            LLVMValueRef xor = LLVM.LLVMBuildXor(builder, left, right, "xor");
            return xor;
        } else if (op.equals("==")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntEQ, left, right, "inteq");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOEQ, left, right, "realeq");
                return fcmp;
            } else if (operandKind == LLVM.LLVMPointerTypeKind) {
                LLVMValueRef ptreq = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntEQ, left, right, "ptreq");
                return ptreq;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
                return null;
            }
        } else if (op.equals(">")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSGT, left, right, "intgt");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOGT, left, right, "realgt");
                return fcmp;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
                return null;
            }
        } else if (op.equals("<")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSLT, left, right, "intlt");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOLT, left, right, "reallt");
                return fcmp;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
                return null;
            }
        } else if (op.equals("!=")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntNE, left, right, "intne");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealONE, left, right, "realne");
                return fcmp;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
                return null;
            }
        } else if (op.equals("<=")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSLE, left, right, "intle");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOLE, left, right, "realle");
                return fcmp;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
                return null;
            }
        } else if (op.equals(">=")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSGE, left, right, "intge");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOGE, left, right, "realge");
                return fcmp;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
                return null;
            }
        } else if (op.equals("cmp")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            as.assertTrue(operandKind == LLVM.LLVMIntegerTypeKind,
                    "Typing: CMP should receive integer type. Got: {}. Left val: {}. Right val: {}",
                    getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
            /*
             * Trans: cmp A B => (icmp ge A B) - (icmp le A B)
             */
            LLVMValueRef icmpge = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSGE, left, right, "cmp");
            LLVMValueRef icmple = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSLE, left, right, "cmp");

            LLVMValueRef sub = LLVM.LLVMBuildSub(builder, buildTypeCast(icmpge, resType), buildTypeCast(icmple, resType), "cmp");
            return sub;
        } else if (op.equals("cmpg")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            as.assertTrue(operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind,
                    "Typing: CMPG should receive real type. Got: {}. Left val: {}. Right val: {}",
                    getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
            /*
             * Trans: cmpg A B => (fcmp uge A B) - (fcmp ole A B)
             */
            LLVMValueRef fcmpuge = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealUGE, left, right, "cmpg");
            LLVMValueRef fcmpole = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOLE, left, right, "cmpg");

            LLVMValueRef fsub = LLVM.LLVMBuildFSub(builder, buildTypeCast(fcmpuge, resType), buildTypeCast(fcmpole, resType), "cmpg");
            return fsub;
        } else if (op.equals("cmpl")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            as.assertTrue(operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind,
                    "Typing: CMPL should receive real type. Got: {}. Left val: {}. Right val: {}",
                    getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
            /*
             * Trans: cmpl A B => (fcmp oge A B) - (fcmp ule A B)
             */
            LLVMValueRef fcmpoge = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOGE, left, right, "cmpl");
            LLVMValueRef fcmpule = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealULE, left, right, "cmpl");

            LLVMValueRef fsub = LLVM.LLVMBuildFSub(builder, buildTypeCast(fcmpoge, resType), buildTypeCast(fcmpule, resType), "cmpl");
            return fsub;
        } else if (op.equals("shl")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            as.assertTrue(operandKind == LLVM.LLVMIntegerTypeKind, "The operand should have integer type. Got {}.", getLLVMStr(operandType));
            LLVMValueRef shl = LLVM.LLVMBuildShl(builder, left, right, "shl");
            return shl;
        } else if (op.equals("shr")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            as.assertTrue(operandKind == LLVM.LLVMIntegerTypeKind, "The operand should have integer type. Got {}.", getLLVMStr(operandType));
            LLVMValueRef shr = LLVM.LLVMBuildAShr(builder, left, right, "shr");
            return shr;
        } else if (op.equals("ushr")) {
            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            as.assertTrue(operandKind == LLVM.LLVMIntegerTypeKind, "The operand should have integer type. Got {}.", getLLVMStr(operandType));
            LLVMValueRef ushr = LLVM.LLVMBuildLShr(builder, left, right, "ushr");
            return ushr;
        } else {
            as.unreachable("Unexpected op {}", op);
            return null;
        }
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

    public Pair<LLVMValueRef, LLVMValueRef> unifyValues(Optional<LLVMValueRef> val1, Optional<LLVMValueRef> val2, LLVMTypeRef defaultType) {
        as.assertTrue(defaultType != null, "There should be a defaultType in case both val1 & val2 are nulls");
        if (val1.isPresent() && val2.isPresent()) {
            return new Pair<>(val1.get(), val2.get());
        } else if (val1.isEmpty() && val2.isEmpty()) {
            LLVMValueRef nullVal1 = buildNull(defaultType);
            LLVMValueRef nullVal2 = buildNull(defaultType);
            return new Pair<>(nullVal1, nullVal2);
        } else if (val1.isEmpty()) {
            LLVMTypeRef valType2 = getValueType(val2.get());
            LLVMValueRef nullVal1 = buildNull(valType2);
            return new Pair<>(nullVal1, val2.get());
        } else if (val2.isEmpty()) {
            LLVMTypeRef valType1 = getValueType(val1.get());
            LLVMValueRef nullVal2 = buildNull(valType1);
            return new Pair<>(val1.get(), nullVal2);
        } else {
            as.unreachable("Unreachable");
            return null;
        }

    }

    public LLVMValueRef buildNop() {
        // TODO: change it to call.
        return LLVM.LLVMConstNull(LLVM.LLVMInt1Type());
    }

}
