public class MathBench {
    public static void main(String[] args) {
        int limit = 100000000;
        long start = System.currentTimeMillis();

        double res = 0.0;
        for (int i = 0; i < limit; i++) {
            res += Math.sin(i) * Math.cos(i) + Math.sqrt(i);
        }

        long end = System.currentTimeMillis();
        System.out.println("Java Math Res: " + res + ", Time: " + (end - start) + " ms");
    }
}
