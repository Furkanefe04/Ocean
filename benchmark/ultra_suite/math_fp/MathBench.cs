using System;
using System.Diagnostics;

class MathBench {
    static void Main() {
        int limit = 100000000;
        Stopwatch sw = Stopwatch.StartNew();

        double res = 0.0;
        for (int i = 0; i < limit; i++) {
            res += Math.Sin(i) * Math.Cos(i) + Math.Sqrt(i);
        }

        sw.Stop();
        Console.WriteLine("CSHARP Math Res: " + res + ", Time: " + sw.ElapsedMilliseconds + " ms");
    }
}
