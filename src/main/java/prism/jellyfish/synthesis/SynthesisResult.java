package prism.jellyfish.synthesis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import prism.jellyfish.util.AssertUtil;

import java.util.List;
import java.util.Map;

public class SynthesisResult {
    private Map<String, List<String>> memoryLayout;
    private Map<String, Map<String, String>> loadingPaths;
    private Map<String, Map<String, List<String>>> storingPaths;

    private static final Logger logger = LogManager.getLogger(SynthesisResult.class);
    private static final AssertUtil as = new AssertUtil(logger);

    public boolean shouldContainSlot(JClass jclass, JMethod jmethod) {
        String className = jclass.getName();
        String sig = jmethod.getSubsignature().toString();
        as.assertTrue(memoryLayout.containsKey(className), "Should contain the class as key");
        return memoryLayout.get(className).contains(sig);
    }

    public JClass getLoadContainer(JClass jclass, Subsignature subSig, ClassHierarchy ch) {
        String className = jclass.getName();
        String sig = subSig.toString();
        as.assertTrue(loadingPaths.containsKey(className), "Should contain the class {} for sig {}", className, sig);
        Map<String, String> sig2Container = loadingPaths.get(className);
        if (!sig2Container.containsKey(sig)) { // direct call
            return null;
        }
        return ch.getClass(sig2Container.get(sig));
    }

}
