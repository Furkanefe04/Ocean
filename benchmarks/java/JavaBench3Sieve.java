public class JavaBench3Sieve {
    public static void main(String[] args) {
        int limit = 2_000_000;
        boolean[] sieve = new boolean[limit + 1];
        for (int i = 2; i <= limit; i++) sieve[i] = true;
        long start = System.nanoTime();
        for (int i = 2; i <= limit; i++) {
            if (sieve[i]) {
                for (int j = i + i; j <= limit; j += i) sieve[j] = false;
            }
        }
        int count = 0;
        for (int i = 2; i <= limit; i++) if (sieve[i]) count++;
        long end = System.nanoTime();
        System.out.println("primes(2M)=" + count + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
