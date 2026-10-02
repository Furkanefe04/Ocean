package ocean.stdlib;

/**
 * Advanced mathematical helper functions for Ocean stdlib.
 */
public class OceanMath {

    public static final double E = Math.E;
    public static final double PI = Math.PI;

    public static double random() {
        return Math.random();
    }

    public static long factorial(int n) {
        if (n < 0) throw new IllegalArgumentException("Factorial undefined for negative numbers");
        long res = 1;
        for (int i = 2; i <= n; i++) {
            res *= i;
        }
        return res;
    }

    public static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    public static long lcm(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return Math.abs(a * (b / gcd(a, b)));
    }

    public static boolean isPrime(long n) {
        if (n <= 1) return false;
        if (n <= 3) return true;
        if (n % 2 == 0 || n % 3 == 0) return false;
        for (long i = 5; i * i <= n; i += 6) {
            if (n % i == 0 || n % (i + 2) == 0) return false;
        }
        return true;
    }

    public static double clamp(double val, double min, double max) {
        if (val < min) return min;
        return Math.min(val, max);
    }

    public static int clamp(int val, int min, int max) {
        if (val < min) return min;
        return Math.min(val, max);
    }

    public static double lerp(double start, double stop, double amt) {
        return start + (stop - start) * amt;
    }

    public static double sqrt(double x) {
        return Math.sqrt(x);
    }

    public static int abs(int a) {
        return Math.abs(a);
    }

    public static long abs(long a) {
        return Math.abs(a);
    }

    public static double abs(double a) {
        return Math.abs(a);
    }

    public static int max(int a, int b) {
        return Math.max(a, b);
    }

    public static long max(long a, long b) {
        return Math.max(a, b);
    }

    public static double max(double a, double b) {
        return Math.max(a, b);
    }

    public static int min(int a, int b) {
        return Math.min(a, b);
    }

    public static long min(long a, long b) {
        return Math.min(a, b);
    }

    public static double min(double a, double b) {
        return Math.min(a, b);
    }

    public static double pow(double a, double b) {
        return Math.pow(a, b);
    }

    public static double floor(double x) {
        return Math.floor(x);
    }

    public static double ceil(double x) {
        return Math.ceil(x);
    }

    public static long round(double x) {
        return Math.round(x);
    }

    public static int round(float x) {
        return Math.round(x);
    }

    public static double sin(double x) {
        return Math.sin(x);
    }

    public static double cos(double x) {
        return Math.cos(x);
    }

    public static double tan(double x) {
        return Math.tan(x);
    }

    public static double atan2(double y, double x) {
        return Math.atan2(y, x);
    }

    public static double log(double x) {
        return Math.log(x);
    }

    public static double log10(double x) {
        return Math.log10(x);
    }

    public static double exp(double x) {
        return Math.exp(x);
    }

    public static double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }

    public static double asin(double x) {
        return Math.asin(x);
    }

    public static double acos(double x) {
        return Math.acos(x);
    }

    public static double atan(double x) {
        return Math.atan(x);
    }

    public static double sinh(double x) {
        return Math.sinh(x);
    }

    public static double cosh(double x) {
        return Math.cosh(x);
    }

    public static double tanh(double x) {
        return Math.tanh(x);
    }

    public static double hypot(double x, double y) {
        return Math.hypot(x, y);
    }

    public static double cbrt(double x) {
        return Math.cbrt(x);
    }

    public static double toDegrees(double rad) {
        return Math.toDegrees(rad);
    }

    public static double toRadians(double deg) {
        return Math.toRadians(deg);
    }

    public static double signum(double x) {
        return Math.signum(x);
    }

    public static float signum(float x) {
        return Math.signum(x);
    }

    public static double copySign(double magnitude, double sign) {
        return Math.copySign(magnitude, sign);
    }
}
