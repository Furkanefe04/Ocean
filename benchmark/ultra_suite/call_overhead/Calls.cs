using System;
using System.Diagnostics;
using System.Runtime.CompilerServices;

class Calls {
    [MethodImpl(MethodImplOptions.NoInlining)]
    static int Add(int a, int b) {
        return a + b;
    }

    static void Main() {
        long limit = 1000000000;
        Stopwatch sw = Stopwatch.StartNew();
        
        long sum = 0;
        for (long i = 0; i < limit; i++) {
            sum += Add((int)i, 1);
        }
        
        sw.Stop();
        Console.WriteLine("CSHARP Calls Sum: " + sum + ", Time: " + sw.ElapsedMilliseconds + " ms");
    }
}
