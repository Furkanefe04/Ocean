using System;
using System.IO;
using System.Diagnostics;

class IOBench {
    static void Main() {
        long totalSize = 1024L * 1024 * 1024; // 1GB
        int bufferSize = 64 * 1024; // 64KB
        byte[] buffer = new byte[bufferSize];
        string filename = "io_test_csharp.bin";

        Stopwatch sw = Stopwatch.StartNew();

        // Write
        using (FileStream fs = new FileStream(filename, FileMode.Create)) {
            for (long i = 0; i < totalSize; i += bufferSize) {
                fs.Write(buffer, 0, bufferSize);
            }
        }

        // Read
        long totalRead = 0;
        using (FileStream fs = new FileStream(filename, FileMode.Open)) {
            while (fs.Read(buffer, 0, bufferSize) > 0) {
                totalRead += bufferSize;
            }
        }

        sw.Stop();
        Console.WriteLine("CSHARP IO Total: " + totalRead + " bytes, Time: " + sw.ElapsedMilliseconds + " ms");

        File.Delete(filename);
    }
}
