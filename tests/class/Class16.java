// Test: Branch and switch

class Class16 {
    static int f1;
    static int f2;
    static int f3;
    static int f4;
    static int f5;

    static {
        if (f1 > 0) {
            f2 = 11;
        }

        if (f1 > 10) {
            f3 = 21;
        } else {
            f3 = 22;
        }

        switch (f1) {
            case 1001: {
                f4 = 31;
            }
            case 10001: {
                f4 = 32;
            }
            case 100001: {
                f4 = 33;
            }
            default: {
                f4 = 34;
            }
        }

        switch (f1) {
            case 40: {
                f5 = 50;
            }
            case 41: {
                f5 = 51;
            }
            case 42: {
                f5 = 52;
            }
            default: {
                f5 = 53;
            }
        }

    }

    public static void main(String[] args) {

    }

}
