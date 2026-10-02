public class Primes {
    static int countPrimes(int limit) {
        int count = 0;
        for (int i = 2; i <= limit; i++) {
            boolean isPrime = true;
            for (int j = 2; j * j <= i; j++) {
                if (i % j == 0) {
                    isPrime = false;
                    break;
                }
            }
            if (isPrime) count++;
        }
        return count;
    }
    public static void main(String[] args) {
        int limit = 2000000;
        long start = System.currentTimeMillis();
        int count = countPrimes(limit);
        long end = System.currentTimeMillis();
        System.out.println("Java Count: " + count + ", Time: " + (end - start) + " ms");
    }
}
