#include <iostream>
#include <vector>
#include <chrono>

using namespace std;

int countPrimes(int max) {
    vector<int> isPrime(max, 0);
    isPrime[0] = 1; isPrime[1] = 1;
    int p = 2;
    while (p * p <= max) {
        if (isPrime[p] == 0) {
            int i = p * p;
            while (i < max) { isPrime[i] = 1; i += p; }
        }
        p++;
    }
    int count = 0;
        for (int i = 2; i < max; i++) { if (isPrime[i] == 0) count++; }
    return count;
}

int main() {
    auto start = chrono::high_resolution_clock::now();
    int count = countPrimes(20000000);
    auto end = chrono::high_resolution_clock::now();
    int dur = chrono::duration_cast<chrono::milliseconds>(end - start).count();
    cout << "==============================================" << endl;
    cout << "  C++ SIEVE(20M) BENCHMARK" << endl;
    cout << "  Sure: " << dur << " ms" << endl;
    cout << "  Hesap: " << count << endl;
    cout << "==============================================" << endl;
    return 0;
}
