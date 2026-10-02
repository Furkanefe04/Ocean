#include <iostream>
#include <vector>
#include <string>
#include <chrono>
#include <algorithm>
#include <iomanip>
#include <sstream>

struct BigInt {
    std::vector<uint32_t> digits;
    static const uint32_t BASE = 1000000000;

    BigInt(uint32_t v = 0) { if (v > 0) digits.push_back(v); }

    void add(const BigInt& other) {
        size_t n = std::max(digits.size(), other.digits.size());
        uint64_t carry = 0;
        for (size_t i = 0; i < n || carry; ++i) {
            if (i == digits.size()) digits.push_back(0);
            uint64_t cur = (uint64_t)digits[i] + carry + (i < other.digits.size() ? other.digits[i] : 0);
            digits[i] = (uint32_t)(cur % BASE);
            carry = cur / BASE;
        }
    }

    std::string toString() const {
        if (digits.empty()) return "0";
        std::stringstream ss;
        ss << digits.back();
        for (int i = (int)digits.size() - 2; i >= 0; --i) {
            ss << std::setfill('0') << std::setw(9) << digits[i];
        }
        return ss.str();
    }
};

int main() {
    std::cout << "C++ Fib(200,000) baslatiliyor..." << std::endl;
    auto start = std::chrono::high_resolution_clock::now();
    
    BigInt a(0);
    BigInt b(1);
    
    for (int i = 2; i <= 200000; ++i) {
        BigInt temp = b;
        b.add(a);
        a = temp;
    }
    
    auto end = std::chrono::high_resolution_clock::now();
    auto dur = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    std::cout << "C++ Fib(200,000) Tamamlandi." << std::endl;
    std::cout << "Sure: " << dur << " ms" << std::endl;
    std::string s = b.toString();
    std::cout << "Basamak Sayisi: " << s.length() << std::endl;
    std::cout << "DOGRULAMA: " << s.substr(0, 10) << "..." << s.substr(s.length() - 10) << std::endl;
    
    return 0;
}
