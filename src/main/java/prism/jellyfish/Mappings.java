package prism.jellyfish;


import pascal.taie.language.classes.JClass;

import org.bytedeco.llvm.LLVM.LLVMTypeRef;

import java.util.HashMap;
import java.util.Optional;

public class Mappings {
    HashMap<JClass, LLVMTypeRef> classMap;

    public Mappings() {
        this.classMap = new HashMap<>();
    }

    public boolean setClassMap(JClass jclass, LLVMTypeRef llvmClass) {
        if (classMap.containsKey(jclass)) {
            return false; // possibly error
        }
        classMap.put(jclass, llvmClass);
        return true;
    }

    public Optional<LLVMTypeRef> getClassMap(JClass jclass) {
        return Optional.ofNullable(classMap.get(jclass));
    }

}
