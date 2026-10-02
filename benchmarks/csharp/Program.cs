using System;
using System.Collections.Generic;
using System.Diagnostics;

class Program
{
    static void Main()
    {
        int N = 10000000;
        int ROUNDS = 5;

        Console.WriteLine($"=== C# List<int> Benchmark (N={N}, Rounds={ROUNDS}) ===");

        Stopwatch initSw = Stopwatch.StartNew();
        List<int> list = new List<int>(N);
        for (int i = 0; i < N; i++)
        {
            list.Add(i % 1000);
        }
        initSw.Stop();
        Console.WriteLine($"List Initialization Time: {initSw.ElapsedMilliseconds} ms");

        long totalSum = 0;
        Stopwatch totalExecSw = Stopwatch.StartNew();

        for (int r = 0; r < ROUNDS; r++)
        {
            Stopwatch roundSw = Stopwatch.StartNew();

            // 1. Indexed updates
            for (int i = 0; i < N; i++)
            {
                int updated = (list[i] * 3 + 7) % 10000;
                list[i] = updated;
            }

            // 2. Foreach iteration sum
            long sum = 0;
            foreach (int val in list)
            {
                sum += val;
            }
            totalSum += sum;

            roundSw.Stop();
            Console.WriteLine($"  Round {r + 1}: {roundSw.ElapsedMilliseconds} ms (Checksum: {sum})");
        }

        totalExecSw.Stop();
        Console.WriteLine("----------------------------------------------");
        Console.WriteLine($"Total Execution Time: {totalExecSw.ElapsedMilliseconds} ms (Avg: {totalExecSw.ElapsedMilliseconds / ROUNDS} ms/round)");
        Console.WriteLine($"Final Checksum: {totalSum}");
    }
}