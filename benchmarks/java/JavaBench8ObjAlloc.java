public class JavaBench8ObjAlloc {
    static class Point {
        final int x, y, z;
        Point(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
    public static void main(String[] args) {
        int n = 2_000_000;
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            Point p = new Point(i, i + 1, i + 2);
            sum += p.x + p.y + p.z;
        }
        long end = System.nanoTime();
        System.out.println("sum=" + sum + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
