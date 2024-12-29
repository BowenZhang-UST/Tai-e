package prism.jellyfish;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.config.Options;
import pascal.taie.language.classes.JClass;
import prism.jellyfish.util.AssertUtil;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Scheduler extends ProgramAnalysis<Void> {
    public static final String ID = "jelly-scheduler";
    private static final Logger logger = LogManager.getLogger(Scheduler.class);
    private static final AssertUtil as = new AssertUtil(logger);

    private static final Level DEBUG_LEVEL = Level.INFO;

    public Scheduler(AnalysisConfig config) {
        super(config);
    }

    public Void analyze() {
        World world = World.get();
        Integer N = getOptions().getInt("threads");
        logger.info("Jellyfish Scheduler. Threads = {}", N);
        Options options = world.getOptions();
        List<List<String>> groups = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            List<String> group = new ArrayList<>();
            groups.add(group);
        }

        List<JClass> allClasses = world.getClassHierarchy().applicationClasses().toList();
        int count = 0;
        for (JClass jclass : allClasses) {
            String className = jclass.getName();
            groups.get(count % N).add(className);
            count++;
        }

        for (int i = 0; i < N; i++) {
            List<String> group = groups.get(i);
            String filePath = options.getOutputDir().toString() + "/groups/" + (i + 1);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
                for (String className : group) {
                    writer.write(className);
                    writer.newLine();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return null;
    }
}
