#include <iostream>
#include <chrono>

using namespace std;

int countPrimes(int max) {
    int count = 0;
    for (int i = 2; i <= max; i++) {
        int isPrime = 1;
        for (int j = 2; j * j <= i; j++) {
            if (i % j == 0) {
                isPrime = 0;
                break;
            }
        }
        if (isPrime == 1) count++;
    }
    return count;
}

int main() {
    auto start = chrono::high_resolution_clock::now();
    int count = countPrimes(10000000);
    auto end = chrono::high_resolution_clock::now();
    int dur = chrono::duration_cast<chrono::milliseconds>(end - start).count();
    cout << "==============================================" << endl;
    cout << "  C++ HARD PRIME (10M) BENCHMARK" << endl;
    cout << "  Sure: " << dur << " ms" << endl;
    cout << "  Hesap: " << count << endl;
    cout << "==============================================" << endl;
    return 0;
}
