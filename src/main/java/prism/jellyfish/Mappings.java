package prism.jellyfish;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import pascal.taie.ir.exp.Exp;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JClass;

import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import prism.jellyfish.util.AssertUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public class Mappings {
    private static final Logger logger = LogManager.getLogger(Mappings.class);
    private static final AssertUtil as = new AssertUtil(logger);

    public HashMap<JClass, LLVMTypeRef> classMap;
    public HashMap<JMethod, LLVMValueRef> methodMap;
    public HashMap<Var, LLVMValueRef> varMap;
    public HashMap<JField, LLVMValueRef> staticFieldMap;

    public Mappings() {
        this.classMap = new HashMap<>();
        this.methodMap = new HashMap<>();
        this.varMap = new HashMap<>();
        this.staticFieldMap = new HashMap<>();
    }

    private <K, V> boolean setMap(HashMap<K, V> m, K key, V value) {
        if (m.containsKey(key)) {
            return false;
        }
        m.put(key, value);
        return true;
    }

    private <K, V> Optional<V> getFromMap(HashMap<K, V> m, K key) {
        return Optional.ofNullable(m.get(key));
    }

    private <K, V> List<K> getAllKeys(HashMap<K, V> m) {
        return List.copyOf(m.keySet());
    }

    private <K, V> void clearMap(HashMap<K, V> m) {
        m.clear();
    }

    /*
     * Class map
     */
    public boolean setClassMap(JClass jclass, LLVMTypeRef llvmClass) {
        return setMap(classMap, jclass, llvmClass);
    }

    public Optional<LLVMTypeRef> getClassMap(JClass jclass) {
        return getFromMap(classMap, jclass);
    }

    public List<JClass> getAllClasses() {
        return getAllKeys(classMap);
    }

    /*
     * Method map
     */
    public boolean setMethodMap(JMethod jmethod, LLVMValueRef llvmMethod) {
        return setMap(methodMap, jmethod, llvmMethod);
    }

    public Optional<LLVMValueRef> getMethodMap(JMethod jmethod) {
        return getFromMap(methodMap, jmethod);
    }

    public List<JMethod> getAllMethods() {
        return getAllKeys(methodMap);
    }

    /*
     * Var map.
     * It is cleared for each method.
     */
    public boolean setVarMap(Var var, LLVMValueRef llvmVal) {
        return setMap(varMap, var, llvmVal);
    }

    public Optional<LLVMValueRef> getVarMap(Var var) {
        return getFromMap(varMap, var);
    }

    public List<Var> getAllVars() {
        return getAllKeys(varMap);
    }

    public void clearVarMap() {
        clearMap(varMap);
    }

    /*
     * Field map.
     */
    public boolean setStaticFieldMap(JField jfield, LLVMValueRef llvmVal) {
        as.assertTrue(jfield.isStatic(), String.format("The field %s should be static.", jfield));
        return setMap(staticFieldMap, jfield, llvmVal);
    }

    public Optional<LLVMValueRef> getStaticFieldMap(JField jfield) {
        return getFromMap(staticFieldMap, jfield);
    }


}
