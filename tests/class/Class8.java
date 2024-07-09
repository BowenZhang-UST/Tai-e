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
    static int f10;
    static int f11;
    static int f12;
    static int f13;

    static double f20;
    static double f21;
    static double f22;
    static double f23;
    static double f24;
    static double f25;
    static double f26;


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
        f10 = f1 << 126;
        f11 = f1 >> 127;
        f12 = f1 >>> 128;

        // neg
        f13 = -f1;

        f20 = 1.0;
        f21 = f20 + 1.0;
        f22 = f20 * 1.0;
        f23 = f20 / 1.0;
        f24 = f20 % 1.0;
        f25 = -f20;

    }

    public static void main(String[] args) {
    }

}
