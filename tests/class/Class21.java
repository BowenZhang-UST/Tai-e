// Test: String pool

class Class21A {
    static String s1;

    static {
        s1 = "hello";
    }
}

class Class21 extends Class21A {
    static String s2;
    static String s3;

    static {
        s2 = "hello";
        s3 = "hello";
    }

    public static void main(String[] args) {

    }
}

