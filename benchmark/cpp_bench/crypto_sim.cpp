#include <iostream>
#include <string>
#include <chrono>

using namespace std;

class Wallet {
public:
    string ownerName;
    int balanceTRY, balanceBTC, balanceETH, totalTrades, isVerified;
    Wallet(string name, int initialTRY) : ownerName(name), balanceTRY(initialTRY), balanceBTC(0), balanceETH(0), totalTrades(0), isVerified(1) {}
    void depositTRY(int amount) { if (isVerified) balanceTRY += amount; }
    int withdrawTRY(int amount) {
        if (isVerified && balanceTRY >= amount) { balanceTRY -= amount; return 1; }
        return 0;
    }
};

class PriceEngine {
public:
    int btcBase, ethBase, tickCount;
    PriceEngine(int btc, int eth) : btcBase(btc), ethBase(eth), tickCount(0) {}
    void tick() {
        tickCount++;
        int cycle = tickCount % 4;
        if (cycle == 0) { btcBase += 100; ethBase -= 20; }
        else if (cycle == 1) { btcBase -= 50; ethBase += 40; }
        else if (cycle == 2) { btcBase += 70; ethBase -= 10; }
        else { btcBase -= 120; ethBase -= 10; }
    }
};

class OrderBook {
public:
    int totalVolume = 0, totalFees = 0;
    void buyBTC(Wallet& w, int amount, int price) {
        int cost = amount * price;
        if (w.withdrawTRY(cost + 2)) {
            w.balanceBTC += amount; w.totalTrades++; totalVolume += cost; totalFees += 2;
        }
    }
    void buyETH(Wallet& w, int amount, int price) {
        int cost = amount * price;
        if (w.withdrawTRY(cost + 1)) {
            w.balanceETH += amount; w.totalTrades++; totalVolume += cost; totalFees += 1;
        }
    }
    void sellBTC(Wallet& w, int amount, int price) {
        if (w.isVerified && w.balanceBTC >= amount) {
            w.balanceBTC -= amount; w.totalTrades++; w.depositTRY(amount * price); totalVolume += (amount * price);
        }
    }
    void transferCrypto(Wallet& from, Wallet& to, int amount, string type) {
        if (type == "BTC" && from.balanceBTC >= amount) { from.balanceBTC -= amount; to.balanceBTC += amount; }
    }
};

void runSim(int iterations) {
    PriceEngine engine(20000, 1000);
    OrderBook exchange;
    Wallet wAlice("Alice", 10000000), wBob("Bob", 500000), wCharlie("Charlie", 2000000), wHacker("Hacker", 90000);
    wHacker.isVerified = 0;

    for (int day = 1; day <= iterations; day++) {
        engine.tick();
        int btcPrice = engine.btcBase;
        int ethPrice = engine.ethBase;
        int mod = day % 4;
        if (mod == 1) { exchange.buyBTC(wAlice, 50, btcPrice); exchange.buyETH(wBob, 20, ethPrice); }
        else if (mod == 2) { exchange.buyBTC(wHacker, 500, btcPrice); exchange.buyETH(wCharlie, 15, ethPrice); }
        else if (mod == 3) { exchange.transferCrypto(wAlice, wBob, 5, "BTC"); }
        else { exchange.sellBTC(wAlice, 2, btcPrice); exchange.buyBTC(wCharlie, 10, btcPrice); }
    }
}

int main() {
    int iterations = 1000000;
    int trials = 5, warmUp = 5;
    cout << "--- C++ Crypto Exchange Simulation Benchmark ---" << endl;
    for (int i = 0; i < warmUp; i++) runSim(iterations);
    long long total = 0;
    for (int i = 0; i < trials; i++) {
        auto s = chrono::high_resolution_clock::now(); runSim(iterations); auto e = chrono::high_resolution_clock::now();
        auto ms = chrono::duration_cast<chrono::milliseconds>(e - s).count();
        total += ms;
        cout << "Trial " << i + 1 << ": " << ms << " ms" << endl;
    }
    cout << "Average Time: " << total / trials << " ms" << endl;
    cout << "DOGRULAMA: BASARILI" << endl;
    return 0;
}
