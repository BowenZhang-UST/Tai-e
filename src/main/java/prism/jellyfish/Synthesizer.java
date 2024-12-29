package prism.jellyfish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import prism.jellyfish.synthesis.JellyClass;
import prism.jellyfish.synthesis.JellyMethod;
import prism.jellyfish.util.AssertUtil;
import prism.jellyfish.util.JavaUtil;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class Synthesizer extends ProgramAnalysis<Void> {
    public static final String ID = "jelly-synthesizer";
    private static final Logger logger = LogManager.getLogger(Synthesizer.class);
    private static final AssertUtil as = new AssertUtil(logger);

    private static final Level DEBUG_LEVEL = Level.INFO;

    World world;
    private ClassHierarchy classHierarchy;


    public Synthesizer(AnalysisConfig config) {
        super(config);
        this.world = World.get();
        this.classHierarchy = world.getClassHierarchy();
    }

    public Void analyze() {
        logger.info("Jellyfish Synthesizer");
        persistClassInfo();
        triggerSynthesizer();
        return null;
    }

    private boolean persistClassInfo() {
        List<JClass> jclasses = classHierarchy.allClasses().toList();
        List<JellyClass> jellyClasses = new ArrayList<>();
        for (JClass jclass : jclasses) {
            List<String> callableSigs = JavaUtil.getCallableSignatures(jclass);
            List<JMethod> ownedMethods = JavaUtil.getOwnedMethods(classHierarchy, jclass);
            String kindName = jclass.isInterface() ? "interface" : "class";
            String className = jclass.getName();
            String superName = jclass.getSuperClass() == null ? "" : jclass.getSuperClass().getName();
            List<String> interfaces = jclass.getInterfaces().stream().map(JClass::getName).toList();
            List<JellyMethod> jellyMethods = ownedMethods.stream().map(m -> new JellyMethod(m.getSubsignature().toString(), m.getDeclaringClass().getName(), m.getSignature())).toList();
            JellyClass jellyClass = new JellyClass(kindName, className, superName, callableSigs, interfaces, jellyMethods);
            jellyClasses.add(jellyClass);
        }
        try (FileWriter writer = new FileWriter("output/oo.json")) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(jellyClasses, writer);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean triggerSynthesizer() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("python3", "chironex/python/main.py", "-i", "output/oo.json", "-o", "output/oo.o.json", "-g", "slot", "--parallel");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info(line);
                }
            }
            int retCode = process.waitFor();
            as.assertTrue(retCode == 0, "Error when triggering the synthesizer");
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }


}
