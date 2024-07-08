package prism.llvm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.llvm.LLVM.*;
import org.bytedeco.llvm.global.LLVM;
import pascal.taie.util.collection.Pair;
import prism.jellyfish.util.ArrayBuilder;
import prism.jellyfish.util.AssertUtil;


import javax.annotation.Nullable;

import static prism.llvm.LLVMUtil.getValueType;
import static prism.llvm.LLVMUtil.getLLVMStr;

import java.util.ArrayList;
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

    public enum IntrinsicID {
        JELLYFISH_NEWARRAY("jellyfish.newarray"),
        JELLYFISH_LENGTH("jellyfish.length");
        private final String name;

        IntrinsicID(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

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
        byte[] errMsg = new byte[4096];
        int ret = LLVM.LLVMVerifyModule(module, LLVM.LLVMPrintMessageAction, errMsg);
        as.assertTrue(ret == 0, "Verify module failed: {}", String.valueOf(errMsg));

        LLVM.LLVMWriteBitcodeToFile(module, bcFile);
    }

    public static String getIntrinsicName(IntrinsicID ID, Object... params) {
        switch (ID) {
            case JELLYFISH_LENGTH: {
                return String.format("%s", ID.getName());
            }
            case JELLYFISH_NEWARRAY: {
                Integer dimension = (Integer) params[0];
                return String.format("%s.%d", ID.getName(), dimension);
            }
        }
        as.unreachable("Unexpected case {} {}", ID, params);
        return "";
    }

    public LLVMValueRef getOrCreateIntrinsic(IntrinsicID ID, Object... params) {
        String intrinsicName = getIntrinsicName(ID, params);
        LLVMValueRef intrinsic = LLVM.LLVMGetNamedFunction(module, intrinsicName);
        if (intrinsic != null) {
            return intrinsic;
        }
        switch (ID) {
            case JELLYFISH_LENGTH: {
                // i32 jellyfish.length(i32* arrayPtr)
                LLVMTypeRef jellyfishNewTy = buildFunctionType(
                        buildIntType(32),
                        List.of(buildPointerType(buildIntType(32)))
                );
                LLVMValueRef ret = this.addFunction(jellyfishNewTy, intrinsicName);
                return ret;
            }
            case JELLYFISH_NEWARRAY: {
                // i32* jellyfish.new(i32 baseSize, i32... lengths)
                List<LLVMTypeRef> paramTypes = new ArrayList<>();
                paramTypes.add(buildIntType(32));
                Integer dimension = (Integer) params[0];
                for (int d = 0; d < dimension; d++) {
                    paramTypes.add(buildIntType(32));
                }
                LLVMTypeRef jellyfishLengthTy = buildFunctionType(
                        buildIntType(32),
                        paramTypes
                );
                LLVMValueRef ret = this.addFunction(jellyfishLengthTy, intrinsicName);
                return ret;
            }
        }
        as.unreachable("Unexpected case: {} {}", ID, params);
        return null;
    }


    /*
     * Module manipulation operations
     * 1. Add IR elements into the module.
     * 2. Retrieve IR elements from the module.
     */

    public LLVMValueRef addGlobalVariable(LLVMTypeRef type, String name) {
        return LLVM.LLVMAddGlobal(module, type, name);
    }

    public LLVMValueRef addFunction(LLVMTypeRef funcType, String funcName) {
        LLVMValueRef func = LLVM.LLVMAddFunction(module, funcName, funcType);
        return func;
    }

    public LLVMBasicBlockRef addBasicBlock(LLVMValueRef func, String blockName) {
        LLVMBasicBlockRef blockRef = LLVM.LLVMAppendBasicBlockInContext(context, func, blockName);
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

    public LLVMTypeRef buildPointerType(LLVMTypeRef elementType) {
        LLVMTypeRef ptrType = LLVM.LLVMPointerType(elementType, 0);
        return ptrType;
    }

    public LLVMTypeRef buildVoidType() {
        return LLVM.LLVMVoidTypeInContext(context);
    }

    public LLVMTypeRef buildIntType(int bits) {
        return LLVM.LLVMIntTypeInContext(context, bits);
    }

    public LLVMTypeRef buildFloatType() {
        return LLVM.LLVMFloatTypeInContext(context);
    }

    public LLVMTypeRef buildDoubleType() {
        return LLVM.LLVMDoubleTypeInContext(context);
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

    public LLVMValueRef buildConstString(String str) {
        LLVMValueRef llvmStr = LLVM.LLVMBuildGlobalString(builder, str, "str");
        return llvmStr;
    }

    public LLVMValueRef buildSizeOf(LLVMTypeRef type) {
        LLVMValueRef sizeof = LLVM.LLVMSizeOf(type);
        as.assertTrue(sizeof != null, "Unexpected value");
        return sizeof;
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

    public LLVMValueRef buildRet(Optional<LLVMValueRef> retVal) {
        if (retVal.isEmpty()) {
            LLVMValueRef ret = LLVM.LLVMBuildRetVoid(builder);
            return ret;
        } else {
            LLVMValueRef ret = LLVM.LLVMBuildRet(builder, retVal.get());
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
        // TODO: change it to intrisic.
        return LLVM.LLVMConstNull(LLVM.LLVMInt1Type());
    }

    @Nullable
    public LLVMValueRef buildCall(LLVMValueRef func, List<LLVMValueRef> args) {

        as.assertTrue(args.size() == LLVM.LLVMCountParams(func),
                "The argument number doesn't match. Func: {}. Args: {}", getLLVMStr(func), args.stream().map(arg -> getLLVMStr(arg)).toList());

        ArrayBuilder<LLVMValueRef> argArray = new ArrayBuilder<>();
        for (int i = 0; i < args.size(); i++) {
            LLVMValueRef param = LLVM.LLVMGetParam(func, i);
            LLVMTypeRef paramType = getValueType(param);
            LLVMValueRef arg = args.get(i);
            LLVMTypeRef argType = getValueType(arg);

            as.assertTrue(paramType.equals(argType),
                    "The {}th parameter and argument don't match type. Arg: {}. Param: {}.",
                    i, getLLVMStr(arg), getLLVMStr(param));
            argArray.add(arg);
        }

        LLVMTypeRef funcPtrType = getValueType(func);
        as.assertTrue(LLVM.LLVMGetTypeKind(funcPtrType) == LLVM.LLVMPointerTypeKind, "Should be function pointer type");
        LLVMTypeRef retType = LLVM.LLVMGetReturnType(LLVM.LLVMGetElementType(funcPtrType));

        if (LLVM.LLVMGetTypeKind(retType) == LLVM.LLVMVoidTypeKind) {
            LLVMValueRef call = LLVM.LLVMBuildCall(builder, func, argArray.build(), argArray.length(), "");
            return null;
        } else {
            LLVMValueRef call = LLVM.LLVMBuildCall(builder, func, argArray.build(), argArray.length(), "call");
            return call;
        }

    }

    public LLVMValueRef buildMalloc(LLVMTypeRef type) {
        // TODO: maybe replace malloc with a special intrinsic "new".
        return LLVM.LLVMBuildMalloc(builder, type, "new");
    }

    public LLVMValueRef buildNewArray(LLVMValueRef baseSize, List<LLVMValueRef> lengths, LLVMTypeRef retType) {

        LLVMValueRef jellyfishNew = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_NEWARRAY, Integer.valueOf(lengths.size()));

        List<LLVMValueRef> params = new ArrayList<>();
        params.add(buildTypeCast(baseSize, buildIntType(32)));
        for (int d = 0; d < lengths.size(); d++) {
            LLVMValueRef theLength = buildTypeCast(lengths.get(d), buildIntType(32));
            params.add(theLength);
        }

        LLVMValueRef newedVal = buildCall(
                jellyfishNew,
                params
        );
        return buildTypeCast(newedVal, retType);
    }

    public LLVMValueRef buildLength(LLVMValueRef arrayPtr) {
        LLVMValueRef jellyfishLength = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_LENGTH);
        LLVMValueRef length = buildCall(
                jellyfishLength,
                List.of(
                        buildTypeCast(arrayPtr, buildPointerType(buildIntType(32)))
                )
        );
        return length;
    }


}
