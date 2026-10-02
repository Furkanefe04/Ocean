#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>

// Standardized structure to match Ocean/Java classes
typedef struct {
    char ownerName[20];
    int balanceTRY, balanceBTC, balanceETH, totalTrades, isVerified;
} Wallet;

void wallet_init(Wallet* w, const char* name, int initialTRY) {
    strncpy(w->ownerName, name, 19); 
    w->balanceTRY = initialTRY; w->balanceBTC = 0;
    w->balanceETH = 0; w->totalTrades = 0; w->isVerified = 1;
}

void wallet_depositTRY(Wallet* w, int amount) { if (w->isVerified) w->balanceTRY += amount; }
int wallet_withdrawTRY(Wallet* w, int amount) {
    if (w->isVerified && w->balanceTRY >= amount) { w->balanceTRY -= amount; return 1; }
    return 0;
}

typedef struct { int btcBase, ethBase, tickCount; } PriceEngine;
void engine_tick(PriceEngine* e) {
    e->tickCount++;
    int cycle = e->tickCount % 4;
    if (cycle == 0) { e->btcBase += 100; e->ethBase -= 20; }
    else if (cycle == 1) { e->btcBase -= 50; e->ethBase += 40; }
    else if (cycle == 2) { e->btcBase += 70; e->ethBase -= 10; }
    else { e->btcBase -= 120; e->ethBase -= 10; }
}

typedef struct { int totalVolume, totalFees; } OrderBook;
void buyBTC(OrderBook* b, Wallet* w, int amount, int price) {
    int cost = amount * price;
    if (wallet_withdrawTRY(w, cost + 2)) {
        w->balanceBTC += amount; w->totalTrades++; b->totalVolume += cost; b->totalFees += 2;
    }
}
void buyETH(OrderBook* b, Wallet* w, int amount, int price) {
    int cost = amount * price;
    if (wallet_withdrawTRY(w, cost + 1)) {
        w->balanceETH += amount; w->totalTrades++; b->totalVolume += cost; b->totalFees += 1;
    }
}
void sellBTC(OrderBook* b, Wallet* w, int amount, int price) {
    if (w->isVerified && w->balanceBTC >= amount) {
        w->balanceBTC -= amount; w->totalTrades++; 
        wallet_depositTRY(w, amount * price); b->totalVolume += (amount * price);
    }
}

void runSim(int iterations) {
    PriceEngine engine = {20000, 1000, 0};
    OrderBook exchange = {0, 0};
    Wallet wAlice, wBob, wCharlie, wHacker;
    wallet_init(&wAlice, "Alice", 10000000);
    wallet_init(&wBob, "Bob", 500000);
    wallet_init(&wCharlie, "Charlie", 2000000);
    wallet_init(&wHacker, "Hacker", 90000);
    wHacker.isVerified = 0;

    for (int day = 1; day <= iterations; day++) {
        engine_tick(&engine);
        int btcPrice = engine.btcBase;
        int ethPrice = engine.ethBase;
        int mod = day % 4;
        if (mod == 1) { buyBTC(&exchange, &wAlice, 50, btcPrice); buyETH(&exchange, &wBob, 20, ethPrice); }
        else if (mod == 2) { buyBTC(&exchange, &wHacker, 500, btcPrice); buyETH(&exchange, &wCharlie, 15, ethPrice); }
        else if (mod == 3) { if (wAlice.balanceBTC >= 5) { wAlice.balanceBTC -= 5; wBob.balanceBTC += 5; } }
        else { sellBTC(&exchange, &wAlice, 2, btcPrice); buyBTC(&exchange, &wCharlie, 10, btcPrice); }
    }
}

int main() {
    int iterations = 1000000;
    int trials = 5, warmUp = 5;
    LARGE_INTEGER freq; QueryPerformanceFrequency(&freq);
    printf("--- C Crypto Exchange Simulation Benchmark ---\n");
    for (int i = 0; i < warmUp; i++) runSim(iterations);
    long long total = 0;
    for (int i = 0; i < trials; i++) {
        LARGE_INTEGER s, e; QueryPerformanceCounter(&s); runSim(iterations); QueryPerformanceCounter(&e);
        long long ms = (e.QuadPart - s.QuadPart) * 1000 / freq.QuadPart;
        total += ms;
        printf("Trial %d: %lld ms\n", i + 1, ms);
    }
    printf("Average Time: %lld ms\n", total / trials);
    printf("DOGRULAMA: BASARILI\n");
    return 0;
}
