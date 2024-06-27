package prism.jellyfish;

import pascal.taie.World;
import pascal.taie.analysis.ClassAnalysis;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.ir.IR;
import pascal.taie.config.AnalysisConfig;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;


public class JellyFish extends ProgramAnalysis<Void>  {
    public static final String ID = "jelly-fish";
    private static final Logger logger = LogManager.getLogger(JellyFish.class);

    public JellyFish(AnalysisConfig config) {
        super(config);
        logger.info("Jellyfish is a transpiler from Tai-e IR to LLVM IR.");
    }

    @Override
    public Void analyze() {
        logger.info("Jellyfish is a transpiler from Tai-e IR to LLVM IR...");
        return null;
    }

}
