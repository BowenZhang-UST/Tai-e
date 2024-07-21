// Test: Instance Field Access


class Class20 {
    int f1;
    String f2;
    static int f3;
    static String f4;


    static {
        Class20 c = new Class20();
        f3 = c.f1;
        f4 = c.f2;
        c.f1 = f3;
        c.f2 = f4;

    }

    public static void main(String[] args) {
    }
}
