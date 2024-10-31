package prism.jellyfish.synthesis;

import java.util.List;

public class JellyClass {
    private String kind;
    private String name;
    private String superName;
    private List<String> callableSigs;
    private List<String> interfaces;
    private List<JellyMethod> ownedMethods;

    public JellyClass(String kind, String name, String superName, List<String> callableSigs, List<String> interfaces, List<JellyMethod> ownedMethods) {
        this.kind = kind;
        this.name = name;
        this.superName = superName;
        this.callableSigs = callableSigs;
        this.interfaces = interfaces;
        this.ownedMethods = ownedMethods;
    }

}
