import java.lang.invoke.MethodType;

class Class18 {
    static MethodType f1;

    static {
        f1 = MethodType.methodType(int.class, String.class);
    }

    public static void main(String[] args) {
    }
}
