public class JavaBench11ReusedBuilder {
    public static void main(String[] args) {
        int n = 5_000_000;
        long start = System.nanoTime();
        long totalLength = 0;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < n; i++) {
            builder.append("Furkan").append(100);
            String s = builder.toString();
            totalLength += s.length();
            builder.setLength(0); // clear equivalent for StringBuilder
        }
        long end = System.nanoTime();
        System.out.println("total_len=" + totalLength + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
