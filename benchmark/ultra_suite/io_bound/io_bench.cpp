#include <iostream>
#include <fstream>
#include <vector>
#include <chrono>
#include <cstdio>

int main() {
    long long totalSize = 1024LL * 1024 * 1024; // 1GB
    int bufferSize = 64 * 1024; // 64KB
    std::vector<char> buffer(bufferSize, 0);

    const char *filename = "io_test_cpp.bin";
    auto start = std::chrono::high_resolution_clock::now();

    // Write
    std::ofstream out(filename, std::ios::binary);
    for (long long i = 0; i < totalSize; i += bufferSize) {
        out.write(buffer.data(), bufferSize);
    }
    out.close();

    // Read
    std::ifstream in(filename, std::ios::binary);
    long long totalRead = 0;
    while (in.read(buffer.data(), bufferSize)) {
        totalRead += bufferSize;
    }
    in.close();

    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    std::cout << "CPP IO Total: " << totalRead << " bytes, Time: " << ms << " ms" << std::endl;

    std::remove(filename);
    return 0;
}
