// Test: Instance Field Access

class Class20A {
    int f1 = 1;
    float f2 = 1;
}

class Class20B extends Class20A {
    int f1 = 2;
    float f2 = 2;
}

class Class20C extends Class20A {
}

class Class20 {
    static int f10;
    static int f11;
    static int f12;
    static float f20;
    static float f21;
    static float f22;

    static {
        Class20A o1 = (Class20A) new Class20B();
        Class20B o2 = new Class20B();
        Class20C o3 = new Class20C();
        f10 = o1.f1;
        f11 = o2.f1;
        f12 = o3.f1;
        f20 = o1.f2;
        f21 = o2.f2;
        f22 = o3.f2;
    }

    public static void main(String[] args) {

    }
}
