public class ArrayBench {
    public static void main(String[] args) {
        int size = 100000000;
        int[] arr = new int[size];

        long start = System.currentTimeMillis();
        long sum = 0;
        for (int i = 0; i < size; i++) {
            arr[i] = i * 3 + 1;
            sum += arr[i];
        }
        long end = System.currentTimeMillis();
        
        System.out.println("Java Sum: " + sum + ", Time: " + (end - start) + " ms");
    }
}
