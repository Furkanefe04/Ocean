public class ParallelSum {
    static class SumThread extends Thread {
        int[] arr;
        int start, end;
        long partialSum;

        SumThread(int[] arr, int start, int end) {
            this.arr = arr;
            this.start = start;
            this.end = end;
        }

        public void run() {
            long s = 0;
            for (int i = start; i < end; i++) {
                s += arr[i];
            }
            this.partialSum = s;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int size = 100000000;
        int[] arr = new int[size];
        for (int i = 0; i < size; i++) arr[i] = i;

        int numThreads = 8;
        SumThread[] threads = new SumThread[numThreads];
        int chunkSize = size / numThreads;

        long start = System.currentTimeMillis();

        for (int i = 0; i < numThreads; i++) {
            int startIdx = i * chunkSize;
            int endIdx = (i == numThreads - 1) ? size : (i + 1) * chunkSize;
            threads[i] = new SumThread(arr, startIdx, endIdx);
            threads[i].start();
        }

        long totalSum = 0;
        for (int i = 0; i < numThreads; i++) {
            threads[i].join();
            totalSum += threads[i].partialSum;
        }

        long end = System.currentTimeMillis();
        System.out.println("Java Parallel Sum: " + totalSum + ", Time: " + (end - start) + " ms");
    }
}
