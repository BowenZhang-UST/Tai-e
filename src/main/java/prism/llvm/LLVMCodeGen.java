package prism.llvm;
import org.bytedeco.llvm.global.LLVM;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

public class LLVMCodeGen {
    public LLVMCodeGen() {
        LLVMContextRef context = LLVM.LLVMContextCreate();
    }
}
