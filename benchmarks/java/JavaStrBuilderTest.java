package packagetest;

public class JavaStrBuilderTest {
    public static void main(String[] args) {
        long start = System.nanoTime();
        for (int i = 0; i < 100_000_000; i++) {
            StringBuilder builder = new StringBuilder();
            builder.append(Integer.MAX_VALUE).append("Furkan").append(true).append(Integer.MIN_VALUE);
        }
        long end = System.nanoTime();
        System.out.println("süre : " + ((end - start) / 1_000_000) + " ms");
    }
}
