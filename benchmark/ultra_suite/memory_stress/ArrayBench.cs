using System;
using System.Diagnostics;

class ArrayBench {
    static void Main() {
        int size = 100000000;
        int[] arr = new int[size];

        Stopwatch sw = Stopwatch.StartNew();
        long sum = 0;
        for (int i = 0; i < size; i++) {
            arr[i] = i * 3 + 1;
            sum += arr[i];
        }
        sw.Stop();
        
        Console.WriteLine("CSHARP Sum: " + sum + ", Time: " + sw.ElapsedMilliseconds + " ms");
    }
}
