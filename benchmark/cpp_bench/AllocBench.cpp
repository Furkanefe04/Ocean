#include <iostream>
#include <chrono>

class Node {
public:
    int a;
    int b;
};

int main() {
    auto start = std::chrono::high_resolution_clock::now();
    long long sum = 0;
    int iterations = 50000000;
    for (int i = 1; i <= iterations; i++) {
        Node* n = new Node();
        n->a = i;
        n->b = i + 1;
        if (i == 49999999) {
            sum = (long long)n->a + n->b;
        }
        delete n;
    }
    auto end = std::chrono::high_resolution_clock::now();
    auto durMs = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    std::cout << "==============================================" << std::endl;
    std::cout << "  C++ OBJECT ALLOC (50M) BENCHMARK" << std::endl;
    std::cout << "  Sure: " << durMs << " ms" << std::endl;
    std::cout << "  Hesap: " << sum << std::endl;
    std::cout << "==============================================" << std::endl;
    
    return 0;
}
