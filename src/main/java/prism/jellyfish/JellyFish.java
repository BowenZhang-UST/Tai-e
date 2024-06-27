package prism.jellyfish;



import pascal.taie.World;
import pascal.taie.analysis.ClassAnalysis;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.ir.IR;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.config.AnalysisConfig;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;

import java.util.stream.Stream;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Collection;


public class JellyFish extends ProgramAnalysis<Void>  {
    public static final String ID = "jelly-fish";
    private static final Logger logger = LogManager.getLogger(JellyFish.class);

    World world;
    ClassHierarchy classHierarchy;

    public JellyFish(AnalysisConfig config) {
        super(config);
        logger.info("Jellyfish is a transpiler from Tai-e IR to LLVM IR.");
        this.world =  World.get();
        classHierarchy = this.world.getClassHierarchy();

    }

    @Override
    public Void analyze() {

        List<JClass> jclasses = classHierarchy.applicationClasses().collect(Collectors.toList());
        for (JClass jclass : jclasses) {
            String className = jclass.getName();
            String moduleName = jclass.getModuleName();
            String simpleName = jclass.getSimpleName();
            logger.info("Found class:\n name: {}, module name: {}, simple name:{}", className, moduleName, simpleName);
            Collection<JMethod> methods = jclass.getDeclaredMethods();
            for(JMethod method : methods) {
                String methodName = method.getName();
                logger.info("    Method: {}", methodName);
                IR ir = method.getIR();
                for(Stmt stmt :ir.getStmts()) {
                    int ln = stmt.getLineNumber();
                    logger.info("        Stmt: {} ({})", stmt, ln);
                }
            }
            
        }

        return null;
    }

}
