using System;
using System.Threading;
using System.Diagnostics;
using System.Collections.Generic;

class ParallelSum {
    class ThreadData {
        public int[] arr;
        public int start, end;
        public long partialSum;
    }

    static void SumChunk(object obj) {
        ThreadData data = (ThreadData)obj;
        long s = 0;
        for (int i = data.start; i < data.end; i++) {
            s += data.arr[i];
        }
        data.partialSum = s;
    }

    static void Main() {
        int size = 100000000;
        int[] arr = new int[size];
        for (int i = 0; i < size; i++) arr[i] = i;

        int numThreads = 8;
        Thread[] threads = new Thread[numThreads];
        ThreadData[] data = new ThreadData[numThreads];
        int chunkSize = size / numThreads;

        Stopwatch sw = Stopwatch.StartNew();

        for (int i = 0; i < numThreads; i++) {
            data[i] = new ThreadData {
                arr = arr,
                start = i * chunkSize,
                end = (i == numThreads - 1) ? size : (i + 1) * chunkSize
            };
            threads[i] = new Thread(SumChunk);
            threads[i].Start(data[i]);
        }

        long totalSum = 0;
        for (int i = 0; i < numThreads; i++) {
            threads[i].Join();
            totalSum += data[i].partialSum;
        }

        sw.Stop();
        Console.WriteLine("CSHARP Parallel Sum: " + totalSum + ", Time: " + sw.ElapsedMilliseconds + " ms");
    }
}
