// Test: the super class and interfaces should be included.

interface Interface3 {}

class Class3A {
    public static void main(String[] args) {}
}

class Class3B extends Class3A {
    public static void main(String[] args) {}
}

class Class3C extends Class3A {
    public static void main(String[] args) {}
}

public class Class3 extends Class3C implements Interface3 {
    public static void main(String[] args) {}
}
