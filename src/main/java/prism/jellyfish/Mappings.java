package prism.jellyfish;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;

import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import prism.jellyfish.util.AssertUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import prism.jellyfish.JellyFish.ClassStatus;

public class Mappings {
    private static final Logger logger = LogManager.getLogger(Mappings.class);
    private static final AssertUtil as = new AssertUtil(logger);

    public HashMap<JClass, LLVMTypeRef> classMap;
    public HashMap<JClass, ClassStatus> classStatusMap;
    public HashMap<JMethod, LLVMValueRef> methodMap;
    public HashMap<Var, LLVMValueRef> varMap;
    public HashMap<JField, LLVMValueRef> staticFieldMap;
    public HashMap<JField, Integer> memberFieldMap;
    public HashMap<JMethod, Integer> virtualMethodMap;
    public HashMap<String, LLVMValueRef> stringPoolMap;
    public HashMap<Stmt, LLVMBasicBlockRef> stmtBlockMap;
    public HashMap<JClass, List<String>> classSigMap;
    public HashMap<JClass, List<JMethod>> classMethodMap;

    public Mappings() {
        this.classMap = new HashMap<>();
        this.classStatusMap = new HashMap<>();
        this.methodMap = new HashMap<>();
        this.varMap = new HashMap<>();
        this.staticFieldMap = new HashMap<>();
        this.memberFieldMap = new HashMap<>();
        this.virtualMethodMap = new HashMap<>();
        this.stringPoolMap = new HashMap<>();
        this.stmtBlockMap = new HashMap<>();
        this.classSigMap = new HashMap<>();
        this.classMethodMap = new HashMap<>();

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
     * Class status map
     */
    public boolean setClassStatusMap(JClass jclass, ClassStatus id) {
        Optional<ClassStatus> oldID = getFromMap(classStatusMap, jclass);
        if (oldID.isPresent()) {
            as.assertTrue(oldID.get().getOrd() < id.getOrd(), "The status should be monotone. Old: {}, New: {}", oldID.get(), id);
        } else {
            as.assertTrue(id == ClassStatus.DEP_DECL, "The first update should be DEP_DECL. Got {}.", id);
        }

        classStatusMap.put(jclass, id);
        return true;
    }

    public Optional<ClassStatus> getClassStatusMap(JClass jclass) {
        return getFromMap(classStatusMap, jclass);
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
     * Static field map.
     */
    public boolean setStaticFieldMap(JField jfield, LLVMValueRef llvmVal) {
        as.assertTrue(jfield.isStatic(), "The field {} should be static.", jfield);
        return setMap(staticFieldMap, jfield, llvmVal);
    }

    public Optional<LLVMValueRef> getStaticFieldMap(JField jfield) {
        return getFromMap(staticFieldMap, jfield);
    }

    /*
     * Member field map.
     */
    public boolean setMemberFieldMap(JField jfield, Integer index) {
        as.assertTrue(!jfield.isStatic(), "The field {} should be member field", jfield);
        return setMap(memberFieldMap, jfield, index);
    }

    public Optional<Integer> getMemberFieldMap(JField jfield) {
        return getFromMap(memberFieldMap, jfield);
    }

    /*
     * Virtual method map.
     */
    public boolean setVirtualMethodMap(JMethod jmethod, Integer index) {
        return setMap(virtualMethodMap, jmethod, index);
    }

    public Optional<Integer> getVirtualMethodMap(JMethod jmethod) {
        return getFromMap(virtualMethodMap, jmethod);
    }

    /*
     * String Pool:
     * String => A pointer to a string class object (which is a pointer to struct).
     */
    public boolean setStringPoolMap(String str, LLVMValueRef llvmVal) {
        return setMap(stringPoolMap, str, llvmVal);
    }

    public Optional<LLVMValueRef> getStringPoolMap(String str) {
        return getFromMap(stringPoolMap, str);
    }

    /*
     * Statement-block map.
     * Cleared for each method.
     */
    public boolean setStmtBlockMap(Stmt stmt, LLVMBasicBlockRef block) {
        return setMap(stmtBlockMap, stmt, block);
    }

    public Optional<LLVMBasicBlockRef> getStmtBlockMap(Stmt stmt) {
        return getFromMap(stmtBlockMap, stmt);
    }

    public void clearStmtBlockMap() {
        clearMap(stmtBlockMap);
    }
    /*
     * Class-method signatures map.
     */
    public boolean setClassSigMap(JClass jclass, List<String> sigs) {
        return setMap(classSigMap, jclass, sigs);
    }
    public Optional<List<String>> getClassSigMap(JClass jclass) {
        return getFromMap(classSigMap, jclass);
    }

    /*
     * Class-owned methods map.
     */
    public boolean setClassMethodMap(JClass jclass, List<JMethod> methods) {
        return setMap(classMethodMap, jclass, methods);
    }
    public Optional<List<JMethod>> getClassMethodMap(JClass jclass) {
        return getFromMap(classMethodMap, jclass);
    }

}
