package prism.jellyfish;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import prism.jellyfish.JellyFish.ClassStatus;
import prism.jellyfish.util.AssertUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class Mappings {
    private static final Logger logger = LogManager.getLogger(Mappings.class);
    private static final AssertUtil as = new AssertUtil(logger);

    public HashMap<JClass, LLVMTypeRef> classMap;
    public HashMap<LLVMTypeRef, JClass> reverseClassMap;
    public HashMap<JClass, ClassStatus> classStatusMap;
    public HashMap<JClass, Set<FieldRef>> classPhantomMemberFieldsMap;
    public HashMap<JMethod, LLVMValueRef> methodMap;
    public HashMap<Var, LLVMValueRef> varMap;
    public HashMap<Var, LLVMValueRef> paramMap;
    public HashMap<JField, LLVMValueRef> staticFieldMap;
    public HashMap<JField, Integer> memberFieldMap;
    public HashMap<FieldRef, Integer> phantomMemberFieldMap;
    public HashMap<String, Integer> slotIndexMap;
    public HashMap<String, Integer> interfaceIndexMap;
    public HashMap<String, LLVMValueRef> stringPoolMap;
    public HashMap<Stmt, LLVMBasicBlockRef> stmtBlockMap;

    public Mappings() {
        this.classMap = new HashMap<>();
        this.reverseClassMap = new HashMap<>();
        this.classStatusMap = new HashMap<>();
        this.classPhantomMemberFieldsMap = new HashMap<>();
        this.methodMap = new HashMap<>();
        this.varMap = new HashMap<>();
        this.staticFieldMap = new HashMap<>();
        this.memberFieldMap = new HashMap<>();
        this.phantomMemberFieldMap = new HashMap<>();
        this.interfaceIndexMap = new HashMap<>();
        this.slotIndexMap = new HashMap<>();
        this.stringPoolMap = new HashMap<>();
        this.stmtBlockMap = new HashMap<>();

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
     * Reverse Class map
     */
    public boolean setReverseClassMap(LLVMTypeRef llvmClass, JClass jclass) {
        return setMap(reverseClassMap, llvmClass, jclass);
    }

    public Optional<JClass> getReverseClassMap(LLVMTypeRef llvmClass) {
        return getFromMap(reverseClassMap, llvmClass);
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
     * Class phantom-member-fields map
     * records all the phantom fields of a class
     * Collected during the pre-analysis `analyzePhantomMemberFields()`
     * Used during `tranClassFields()`
     */
    public boolean setClassPhantomMemberFieldsMap(JClass jclass, Set<FieldRef> refs) {
        return setMap(classPhantomMemberFieldsMap, jclass, refs);
    }

    public Optional<Set<FieldRef>> getClassPhantomMemberFieldsMap(JClass jclass) {
        return getFromMap(classPhantomMemberFieldsMap, jclass);
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
     * Phantom member field map.
     */
    public boolean setPhantomMemberFieldMap(FieldRef ref, Integer index) {
        as.assertTrue(!ref.isStatic(), "The field {} should be member field", ref);
        return setMap(phantomMemberFieldMap, ref, index);
    }

    public Optional<Integer> getPhantomMemberFieldMap(FieldRef ref) {
        return getFromMap(phantomMemberFieldMap, ref);
    }

    /*
     * Slot index map. <className::sig> -> index of function pointer
     */
    public boolean setSlotIndexMap(JClass jclass, Subsignature sig, Integer index) {
        return setMap(slotIndexMap, jclass.getName() + "::" + sig.toString(), index);
    }

    public Optional<Integer> getSlotIndexMap(JClass jclass, Subsignature sig) {
        return getFromMap(slotIndexMap, jclass.getName() + "::" + sig.toString());
    }

    /*
     * Interface index map. <class::interface> -> index of interface
     */
    public boolean setInterfaceIndexMap(JClass jclass, JClass i, Integer index) {
        return setMap(interfaceIndexMap, jclass.getName() + "=>" + i.getName(), index);
    }

    public Optional<Integer> getInterfaceIndexMap(JClass jclass, JClass i) {
        return getFromMap(interfaceIndexMap, jclass.getName() + "=>" + i.getName());
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


}
