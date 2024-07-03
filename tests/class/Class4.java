// Tests: the types and names of class fields
class Class4A {
    Class4B f1;
}

class Class4B {
    Class4A f1;
}

public class Class4 {
    int f1 = 1;
    float f2;
    String f3;
    Class4A f4;
    Class4B f5;

    public static void main(String[] args) {
    }
}
