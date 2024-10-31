package prism.jellyfish.synthesis;

public class JellyMethod {
    private String sig;
    private String declaringClass;
    private String fullSig;

    public JellyMethod(String sig, String declaringClass, String fullSig) {
        this.sig = sig;
        this.declaringClass = declaringClass;
        this.fullSig = fullSig;
    }
}
