package prism.llvm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;
import prism.jellyfish.util.AssertUtil;

import java.util.ArrayList;
import java.util.List;

public class LLVMUtil {
    private static final Logger logger = LogManager.getLogger(LLVMUtil.class);
    private static final AssertUtil as = new AssertUtil(logger);

    public static String getLLVMStr(LLVMTypeRef type) {
        as.assertTrue(type != null, "The type should not be null");
        String str = LLVM.LLVMPrintTypeToString(type).getString();
        return str;
    }

    public static String getLLVMStr(LLVMValueRef val) {
        as.assertTrue(val != null, "The value should not be null");
        String str = LLVM.LLVMPrintValueToString(val).getString();
        return str;
    }


    public static LLVMTypeRef getValueType(LLVMValueRef val) {
        as.assertTrue(val != null, "The value should not be null");
        return LLVM.LLVMTypeOf(val);
    }

    public static LLVMTypeRef getElementType(LLVMTypeRef type) {
        as.assertTrue(type != null, "The value should not be null");
        return LLVM.LLVMGetElementType(type);
    }

    public static List<LLVMTypeRef> getParamTypes(LLVMTypeRef funcType) {
        List<LLVMTypeRef> ret = new ArrayList<>();
        int paramCount = LLVM.LLVMCountParamTypes(funcType);
        PointerPointer<LLVMTypeRef> pp = new PointerPointer<>(new LLVMTypeRef[paramCount]);
        LLVM.LLVMGetParamTypes(funcType, pp);
        for (int i = 0; i < paramCount; i++) {
            Pointer p = pp.get(i);
            ret.add(new LLVMTypeRef(p));
        }
        return ret;
    }

    public static LLVMTypeRef getFuncType(LLVMValueRef func) {
        /*
         * func represents either a **function** or a **function pointer value**
         */
        LLVMTypeRef type = getValueType(func);
        int kind = LLVM.LLVMGetTypeKind(type);
        as.assertTrue(kind == LLVM.LLVMPointerTypeKind, "It should have a pointer kind. Got: {}", getLLVMStr(type));
        LLVMTypeRef funcType = LLVM.LLVMGetElementType(type);
        as.assertTrue(LLVM.LLVMGetTypeKind(funcType) == LLVM.LLVMFunctionTypeKind, "The pointer should point to a function. Got: {}", getLLVMStr(funcType));
        return funcType;
    }

    public static boolean isOpaqueStruct(LLVMTypeRef struct) {
        int ret = LLVM.LLVMIsOpaqueStruct(struct);
        return ret == 1 ? true : false;
    }


}
