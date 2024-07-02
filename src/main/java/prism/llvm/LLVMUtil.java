package prism.llvm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;
import prism.jellyfish.util.AssertUtil;
import prism.jellyfish.util.StringUtil;

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
}
