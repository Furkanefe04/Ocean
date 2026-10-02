#include <stdio.h>
#include <stdlib.h>
#include <time.h>

int main() {
    long long totalSize = 1024LL * 1024 * 1024; // 1GB
    int bufferSize = 64 * 1024; // 64KB
    char *buffer = (char *)malloc(bufferSize);
    for (int i = 0; i < bufferSize; i++) buffer[i] = (char)(i % 256);

    const char *filename = "io_test_bench.bin";
    clock_t start = clock();

    // Write
    FILE *f = fopen(filename, "wb");
    if (!f) return 1;
    for (long long i = 0; i < totalSize; i += bufferSize) {
        fwrite(buffer, 1, bufferSize, f);
    }
    fclose(f);

    // Read
    f = fopen(filename, "rb");
    if (!f) return 1;
    long long totalRead = 0;
    while (fread(buffer, 1, bufferSize, f) > 0) {
        totalRead += bufferSize;
    }
    fclose(f);

    clock_t end = clock();
    double ms = ((double)(end - start) / CLOCKS_PER_SEC) * 1000;
    printf("C IO Total: %lld bytes, Time: %.1f ms\n", totalRead, ms);

    remove(filename);
    free(buffer);
    return 0;
}
