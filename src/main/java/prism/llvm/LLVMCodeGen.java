package prism.llvm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.llvm.LLVM.*;
import org.bytedeco.llvm.global.LLVM;
import pascal.taie.util.collection.Pair;
import prism.jellyfish.util.ArrayBuilder;
import prism.jellyfish.util.AssertUtil;
import prism.jellyfish.util.StringUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static prism.llvm.LLVMUtil.*;


public class LLVMCodeGen {
    /*
     * Provide manipulations to a LLVM module.
     */
    String moduleName;
    String outputPrefix;
    LLVMContextRef context;
    LLVMModuleRef module;
    LLVMBuilderRef builder;

    private static final Logger logger = LogManager.getLogger(LLVMCodeGen.class);
    private static final AssertUtil as = new AssertUtil(logger);

    public LLVMCodeGen() {
        this.moduleName = "module";
        this.outputPrefix = "output/out";
        this.context = LLVM.LLVMContextCreate();
        this.module = LLVM.LLVMModuleCreateWithNameInContext(moduleName, context);
        this.builder = LLVM.LLVMCreateBuilderInContext(context);
        LLVM.LLVMInitializeCore(LLVM.LLVMGetGlobalPassRegistry());
        LLVM.LLVMInitializeScalarOpts(LLVM.LLVMGetGlobalPassRegistry());
    }

    /*
     * Top-level commands
     */

    public void verify() {
        byte[] errMsg = new byte[4096];
        int ret = LLVM.LLVMVerifyModule(module, LLVM.LLVMPrintMessageAction, errMsg);
        as.assertTrue(ret == 0, "Verify module failed: {}", String.valueOf(errMsg));
    }

    public void optimize() {
        LLVMPassManagerRef passManager = LLVM.LLVMCreatePassManager();
        LLVM.LLVMAddPromoteMemoryToRegisterPass(passManager);

        int res = LLVM.LLVMRunPassManager(passManager, module);
        as.assertTrue(res == 1, "Mem2reg optimization failed.");
        LLVM.LLVMDisposePassManager(passManager);
    }

    public void generate() {
        LLVM.LLVMWriteBitcodeToFile(module, outputPrefix + ".bc");
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

    public void insertInst(LLVMValueRef inst) {
        // Insert an instruction to the current insertion block
        LLVM.LLVMInsertIntoBuilder(builder, inst);

    }

    public void setDebugLocation(int line, String className) {
        // TODO:
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

    public LLVMValueRef buildUnreachable() {
        return LLVM.LLVMBuildUnreachable(builder);
    }

    public LLVMValueRef buildUncondBr(LLVMBasicBlockRef target) {
        LLVMValueRef br = LLVM.LLVMBuildBr(builder, target);
        return br;
    }

    public LLVMValueRef buildCondBr(LLVMValueRef cond, LLVMBasicBlockRef trueBlock, LLVMBasicBlockRef falseBlock) {
        LLVMValueRef br = LLVM.LLVMBuildCondBr(builder, cond, trueBlock, falseBlock);
        return br;
    }

    public LLVMValueRef buildSwitch(LLVMValueRef cond, List<Pair<LLVMValueRef, LLVMBasicBlockRef>> caseTargetPairs, LLVMBasicBlockRef defaultBlock) {
        LLVMValueRef switchInst = LLVM.LLVMBuildSwitch(builder, cond, defaultBlock, caseTargetPairs.size());

        for (Pair<LLVMValueRef, LLVMBasicBlockRef> caseTarget : caseTargetPairs) {
            LLVMValueRef theCase = caseTarget.first();
            LLVMBasicBlockRef theTarget = caseTarget.second();
            LLVM.LLVMAddCase(switchInst, theCase, theTarget);
        }
        return switchInst;
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

    public LLVMValueRef buildRet(Optional<LLVMValueRef> retVal) {
        if (retVal.isEmpty()) {
            LLVMValueRef ret = LLVM.LLVMBuildRetVoid(builder);
            return ret;
        } else {
            LLVMValueRef ret = LLVM.LLVMBuildRet(builder, retVal.get());
            return ret;
        }
    }


    public LLVMValueRef buildGEP(LLVMValueRef base, List<LLVMValueRef> indices) {
        ArrayBuilder<LLVMValueRef> indiceArray = new ArrayBuilder<>();
        for (LLVMValueRef indice : indices) {
            indiceArray.add(indice);
        }

        return LLVM.LLVMBuildGEP(builder, base, indiceArray.build(), indiceArray.length(), "gep");
    }

    public LLVMValueRef buildNeg(LLVMValueRef val) {
        LLVMTypeRef type = getValueType(val);
        int kind = LLVM.LLVMGetTypeKind(type);
        if (kind == LLVM.LLVMIntegerTypeKind) {
            return LLVM.LLVMBuildNeg(builder, val, "neg");
        } else if (kind == LLVM.LLVMFloatTypeKind || kind == LLVM.LLVMDoubleTypeKind) {
            return LLVM.LLVMBuildFNeg(builder, val, "fneg");
        } else {
            as.unreachable("Unexpected type: {}", getLLVMStr(type));
            return null;
        }

    }

    public LLVMValueRef buildBinaryOp(String op, LLVMValueRef left, LLVMValueRef right, LLVMTypeRef resType) {
        LLVMTypeRef lType = getValueType(left);
        LLVMTypeRef rType = getValueType(right);
        int leftOpKind = LLVM.LLVMGetTypeKind(lType);
        int rightOpKind = LLVM.LLVMGetTypeKind(rType);

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
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntEQ, left, right2, "inteq");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOEQ, left, right2, "realeq");
                return fcmp;
            } else if (operandKind == LLVM.LLVMPointerTypeKind) {
                LLVMValueRef ptreq = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntEQ, left, right2, "ptreq");
                return ptreq;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right2));
                return null;
            }
        } else if (op.equals(">")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSGT, left, right2, "intgt");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOGT, left, right2, "realgt");
                return fcmp;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right2));
                return null;
            }
        } else if (op.equals("<")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSLT, left, right2, "intlt");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOLT, left, right2, "reallt");
                return fcmp;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right2));
                return null;
            }
        } else if (op.equals("!=")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntNE, left, right2, "intne");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealONE, left, right2, "realne");
                return fcmp;
            } else if (operandKind == LLVM.LLVMPointerTypeKind) {
                LLVMValueRef ptreq = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntEQ, left, right2, "ptreq");
                return ptreq;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right2));
                return null;
            }
        } else if (op.equals("<=")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSLE, left, right2, "intle");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOLE, left, right2, "realle");
                return fcmp;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right2));
                return null;
            }
        } else if (op.equals(">=")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            if (operandKind == LLVM.LLVMIntegerTypeKind) {
                LLVMValueRef icmp = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSGE, left, right2, "intge");
                return icmp;
            } else if (operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind) {
                LLVMValueRef fcmp = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOGE, left, right2, "realge");
                return fcmp;
            } else {
                as.unreachable("Unexpected operand type: {}. Left val: {}. Right val: {}",
                        getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right2));
                return null;
            }
        } else if (op.equals("cmp")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            as.assertTrue(operandKind == LLVM.LLVMIntegerTypeKind,
                    "Typing: CMP should receive integer type. Got: {}. Left val: {}. Right val: {}",
                    getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right2));
            /*
             * Trans: cmp A B => (icmp ge A B) - (icmp le A B)
             */
            LLVMValueRef icmpge = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSGE, left, right2, "cmp");
            LLVMValueRef icmple = LLVM.LLVMBuildICmp(builder, LLVM.LLVMIntSLE, left, right2, "cmp");

            LLVMValueRef sub = LLVM.LLVMBuildSub(builder, buildTypeCast(icmpge, resType), buildTypeCast(icmple, resType), "cmp");
            return sub;
        } else if (op.equals("cmpg")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            as.assertTrue(operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind,
                    "Typing: CMPG should receive real type. Got: {}. Left val: {}. Right val: {}",
                    getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right2));
            /*
             * Trans: cmpg A B => (fcmp uge A B) - (fcmp ole A B)
             */
            LLVMValueRef fcmpuge = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealUGE, left, right2, "cmpg");
            LLVMValueRef fcmpole = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOLE, left, right2, "cmpg");

            LLVMValueRef fsub = LLVM.LLVMBuildSub(builder, buildTypeCast(fcmpuge, resType), buildTypeCast(fcmpole, resType), "cmpg");
            return fsub;
        } else if (op.equals("cmpl")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            as.assertTrue(operandKind == LLVM.LLVMFloatTypeKind || operandKind == LLVM.LLVMDoubleTypeKind,
                    "Typing: CMPL should receive real type. Got: {}. Left val: {}. Right val: {}",
                    getLLVMStr(operandType), getLLVMStr(left), getLLVMStr(right));
            /*
             * Trans: cmpl A B => (fcmp oge A B) - (fcmp ule A B)
             */
            LLVMValueRef fcmpoge = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealOGE, left, right2, "cmpl");
            LLVMValueRef fcmpule = LLVM.LLVMBuildFCmp(builder, LLVM.LLVMRealULE, left, right2, "cmpl");

            LLVMValueRef fsub = LLVM.LLVMBuildSub(builder, buildTypeCast(fcmpoge, resType), buildTypeCast(fcmpule, resType), "cmpl");
            return fsub;
        } else if (op.equals("shl")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            as.assertTrue(operandKind == LLVM.LLVMIntegerTypeKind, "The operand should have integer type. Got {}.", getLLVMStr(operandType));
            LLVMValueRef shl = LLVM.LLVMBuildShl(builder, left, right2, "shl");
            return shl;
        } else if (op.equals("shr")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            as.assertTrue(operandType != null, "The left and right op kind should be the same. Left op: {}. Right op: {}", getLLVMStr(left), getLLVMStr(right));
            as.assertTrue(operandKind == LLVM.LLVMIntegerTypeKind, "The operand should have integer type. Got {}.", getLLVMStr(operandType));
            LLVMValueRef shr = LLVM.LLVMBuildAShr(builder, left, right2, "shr");
            return shr;
        } else if (op.equals("ushr")) {
            int operandKind = leftOpKind;
            LLVMTypeRef operandType = lType;
            LLVMValueRef right2 = buildTypeCast(right, lType);

            as.assertTrue(operandKind == LLVM.LLVMIntegerTypeKind, "The operand should have integer type. Got {}.", getLLVMStr(operandType));
            LLVMValueRef ushr = LLVM.LLVMBuildLShr(builder, left, right2, "ushr");
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

    public LLVMValueRef buildNop() {
        // TODO: change it to intrisic.
        return LLVM.LLVMConstNull(LLVM.LLVMInt1Type());
    }

    @Nullable
    public LLVMValueRef buildCall(LLVMValueRef func, List<LLVMValueRef> args) {
        LLVMTypeRef funcType = getFuncType(func);
        List<LLVMTypeRef> paramTypes = getParamTypes(funcType);
        as.assertTrue(args.size() == LLVM.LLVMCountParamTypes(funcType),
                "The argument number doesn't match. Func: {}. Args: {}", getLLVMStr(func), args.stream().map(arg -> getLLVMStr(arg)).toList());

        ArrayBuilder<LLVMValueRef> argArray = new ArrayBuilder<>();
        for (int i = 0; i < args.size(); i++) {
            LLVMTypeRef paramType = paramTypes.get(i);

            LLVMValueRef arg = args.get(i);
            LLVMTypeRef argType = getValueType(arg);

            as.assertTrue(paramType.equals(argType),
                    "The {}th parameter and argument don't match type. Arg: {}. Param type: {}.",
                    i, getLLVMStr(arg), getLLVMStr(paramType));
            argArray.add(arg);
        }

        LLVMTypeRef retType = LLVM.LLVMGetReturnType(funcType);

        if (LLVM.LLVMGetTypeKind(retType) == LLVM.LLVMVoidTypeKind) {
            LLVMValueRef call = LLVM.LLVMBuildCall(builder, func, argArray.build(), argArray.length(), "");
            return null;
        } else {
            LLVMValueRef call = LLVM.LLVMBuildCall(builder, func, argArray.build(), argArray.length(), "call");
            return call;
        }
    }

    /*
     * Intrinsic builders
     */

    public enum IntrinsicID {
        JELLYFISH_NEWARRAY("jellyfish.newarray"),
        JELLYFISH_LENGTH("jellyfish.length"),
        JELLYFISH_MONITOR_ENTER("jellyfish.monitor.enter"),
        JELLYFISH_MONITOR_EXIT("jellyfish.monitor.exit"),
        JELLYFISH_INSTANCEOF("jellyfish.instanceof"),
        JELLYFISH_CLASS("jellyfish.class"),
        JELLYFISH_METHODTYPE("jellyfish.methodtype"),
        JELLYFISH_CATCH("jellyfish.catch"),
        JELLYFISH_THROW("jellyfish.throw"),
        JELLYFISH_ROLLDICE("jellyfish.rolldice");
        private final String name;

        IntrinsicID(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
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
            case JELLYFISH_MONITOR_ENTER: {
            }
            case JELLYFISH_MONITOR_EXIT: {
            }
            case JELLYFISH_ROLLDICE: {
                return String.format("%s", ID.getName());
            }
            case JELLYFISH_INSTANCEOF: {
            }
            case JELLYFISH_CLASS: {
            }
            case JELLYFISH_METHODTYPE: {
            }
            case JELLYFISH_CATCH: {
            }
            case JELLYFISH_THROW: {
                String uuid = (String) params[0];
                return String.format("%s.%s", ID.getName(), uuid);
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
            case JELLYFISH_MONITOR_ENTER: {
                // i32* jellyfish.monitorenter.[unique id](%java.lang.Object* val)
            }
            case JELLYFISH_MONITOR_EXIT: {
                // i32* jellyfish.monitorexit.[unique id](%java.lang.Object* val)
                LLVMTypeRef objType = (LLVMTypeRef) params[0];

                LLVMTypeRef jellyfishMonitorTy = buildFunctionType(
                        buildVoidType(),
                        List.of(objType)
                );
                LLVMValueRef ret = this.addFunction(jellyfishMonitorTy, intrinsicName);
                return ret;
            }
            case JELLYFISH_INSTANCEOF: {
                // i32* jellyfish.instanceof.[unique id](%java.lang.Object* val, [Type corresponding to specific Class] checkedType)
                LLVMTypeRef checkType = (LLVMTypeRef) params[1];
                LLVMTypeRef objectType = (LLVMTypeRef) params[2];

                LLVMTypeRef jellyfishInstanceOfTy = buildFunctionType(
                        buildIntType(1),
                        List.of(
                                objectType,
                                checkType
                        )
                );
                LLVMValueRef ret = this.addFunction(jellyfishInstanceOfTy, intrinsicName);
                return ret;
            }
            case JELLYFISH_CLASS: {
                // %java.lang.Class* jellyfish.class.[unique id]([Type corresponding to specific Class] val)
                LLVMTypeRef classType = (LLVMTypeRef) params[1];
                LLVMTypeRef javaClassType = (LLVMTypeRef) params[2];

                LLVMTypeRef jellyFishClassTy = buildFunctionType(
                        javaClassType,
                        List.of(
                                classType
                        )
                );
                LLVMValueRef ret = this.addFunction(jellyFishClassTy, intrinsicName);
                return ret;
            }
            case JELLYFISH_METHODTYPE: {
                //$java.invoke.MethodType* jellyfish.class.[unique id]([A function pointer type] func)
                LLVMTypeRef funcPtrType = (LLVMTypeRef) params[1];
                LLVMTypeRef javaMethodTypeTy = (LLVMTypeRef) params[2];

                LLVMTypeRef jellyfishMethodtypeTy = buildFunctionType(
                        javaMethodTypeTy,
                        List.of(funcPtrType)
                );
                LLVMValueRef ret = this.addFunction(jellyfishMethodtypeTy, intrinsicName);
                return ret;
            }
            case JELLYFISH_CATCH: {
                // void jellyfish.catch.[unique id]([A specific catched type] catched)
                LLVMTypeRef catchedType = (LLVMTypeRef) params[1];

                LLVMTypeRef jellyfishCatchTy = buildFunctionType(
                        buildVoidType(),
                        List.of(catchedType)
                );
                LLVMValueRef ret = this.addFunction(jellyfishCatchTy, intrinsicName);
                return ret;
            }
            case JELLYFISH_THROW: {
                // void jellyfish.throw.[unique id]([A specific throwed type] throwed)
                LLVMTypeRef throwedType = (LLVMTypeRef) params[1];

                LLVMTypeRef jellyfishCatchTy = buildFunctionType(
                        buildVoidType(),
                        List.of(throwedType)
                );
                LLVMValueRef ret = this.addFunction(jellyfishCatchTy, intrinsicName);
                return ret;
            }
            case JELLYFISH_ROLLDICE: {
                // i32 jellyfish.rolldice()
                LLVMTypeRef jellyfishRolldiceTy = buildFunctionType(
                        buildIntType(32),
                        List.of()
                );
                LLVMValueRef ret = this.addFunction(jellyfishRolldiceTy, intrinsicName);
                return ret;
            }
        }
        as.unreachable("Unexpected case: {} {}", ID, params);
        return null;
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


    public LLVMValueRef buildMonitorEnter(LLVMValueRef obj, LLVMTypeRef objectType) {

        LLVMValueRef jellyfishMonitor = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_MONITOR_ENTER, objectType);
        LLVMValueRef monitor = buildCall(
                jellyfishMonitor,
                List.of(
                        obj
                )
        );
        return monitor;
    }

    public LLVMValueRef buildMonitorExit(LLVMValueRef obj, LLVMTypeRef objectType) {

        LLVMValueRef jellyfishMonitor = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_MONITOR_EXIT, objectType);
        LLVMValueRef monitor = buildCall(
                jellyfishMonitor,
                List.of(
                        obj
                )
        );
        return monitor;
    }

    public LLVMValueRef buildInstanceOf(LLVMValueRef obj, LLVMTypeRef checkType, LLVMTypeRef objectType) {
        String uuid = StringUtil.getUUID();

        LLVMValueRef jellyfishInstanceOf = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_INSTANCEOF, uuid, checkType, objectType);
        LLVMValueRef instanceOf = buildCall(
                jellyfishInstanceOf,
                List.of(buildTypeCast(obj, objectType), buildNull(checkType))
        );
        return instanceOf;
    }

    public LLVMValueRef buildClassIntrinsic(LLVMTypeRef classType, LLVMTypeRef javaClassType) {
        String uuid = StringUtil.getUUID();

        LLVMValueRef jellyfishClass = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_CLASS, uuid, classType, javaClassType);
        LLVMValueRef classIntrinsic = buildCall(
                jellyfishClass,
                List.of(buildNull(classType))
        );
        return classIntrinsic;
    }

    public LLVMValueRef buildMethodType(LLVMTypeRef retType, List<LLVMTypeRef> paramTypes, LLVMTypeRef javaMethodTypeTy) {
        String uuid = StringUtil.getUUID();
        LLVMTypeRef funcPtrType = buildPointerType(
                buildFunctionType(
                        retType,
                        paramTypes
                )
        );
        LLVMValueRef jellyfishMethodType = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_METHODTYPE, uuid, funcPtrType, javaMethodTypeTy);
        LLVMValueRef methodType = buildCall(
                jellyfishMethodType,
                List.of(buildNull(funcPtrType))
        );
        return methodType;
    }

    public LLVMValueRef buildCatch(LLVMValueRef catched) {
        String uuid = StringUtil.getUUID();
        LLVMTypeRef type = getValueType(catched);

        LLVMValueRef jellyfishCatch = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_CATCH, uuid, type);
        LLVMValueRef theCatch = buildCall(
                jellyfishCatch,
                List.of(catched)
        );
        return theCatch;
    }

    public LLVMValueRef buildThrow(LLVMValueRef throwed) {
        String uuid = StringUtil.getUUID();
        LLVMTypeRef type = getValueType(throwed);

        LLVMValueRef jellyfishThrow = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_THROW, uuid, type);
        LLVMValueRef theThrow = buildCall(
                jellyfishThrow,
                List.of(throwed)
        );
        return theThrow;
    }

    public LLVMValueRef buildRolldice() {
        LLVMValueRef jellyfishRolldice = getOrCreateIntrinsic(IntrinsicID.JELLYFISH_ROLLDICE);
        LLVMValueRef theRolldice = buildCall(
                jellyfishRolldice,
                List.of()
        );
        return theRolldice;
    }


}
