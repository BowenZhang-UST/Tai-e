// Test: null propagation

class Class10 {
    static Object f1;
    static String f2;
    static Object f3;

    static {
        f1 = null;
        f2 = null;
        f3 = f1;
    }

    public static void main(String[] args) {
    }
}
