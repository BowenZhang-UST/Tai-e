package prism.jellyfish;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.language.classes.JClass;

import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import prism.jellyfish.util.AssertUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Optional;

public class Mappings {
    private static final Logger logger = LogManager.getLogger(Mappings.class);
    private static final AssertUtil as = new AssertUtil(logger);

    public HashMap<JClass, LLVMTypeRef> classMap;

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

    public List<JClass> getAllClasses() {
        logger.info("Get all classes: {}", classMap.keySet());
        return List.copyOf(classMap.keySet());
    }

}
