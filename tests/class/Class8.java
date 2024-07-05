// Tests: binary operations of integer and real types.
public class Class8 {
    static int f1;
    static int f2;
    static int f3;
    static int f4;
    static int f5;
    static int f6;
    static int f7;
    static int f8;
    static int f9;
    static boolean f10;
    static boolean f11;
    static boolean f12;
    static boolean f13;
    static boolean f14;
    static boolean f15;
    static int f16;
    static int f17;
    static int f18;


    static {
        f1 = 111;
        // arithmetic:
        f2 = f1 + 112;
        f3 = f1 - 113;
        f4 = f1 * 114;
        f5 = f1 / 115;
        f6 = f1 % 116;
        // bitwise:
        f7 = f1 & 117;
        f8 = f1 | 118;
        f9 = f1 ^ 119;
        // condition
        // shift
        f16 = f1 << 126;
        f17 = f1 >> 127;
        f18 = f1 >>> 128;
    }

    public static void main(String[] args) {
    }

}
