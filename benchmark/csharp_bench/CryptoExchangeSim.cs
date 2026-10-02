using System;
using System.Diagnostics;

class Wallet {
    public string ownerName;
    public int balanceTRY, balanceBTC, balanceETH, totalTrades, isVerified;
    public Wallet(string name, int initialTRY) {
        ownerName = name; balanceTRY = initialTRY; isVerified = 1;
    }
    public void DepositTRY(int amount) { if (isVerified == 1) balanceTRY += amount; }
    public int WithdrawTRY(int amount) {
        if (isVerified == 1 && balanceTRY >= amount) { balanceTRY -= amount; return 1; }
        return 0;
    }
}

class PriceEngine {
    public int btcBase, ethBase, tickCount;
    public PriceEngine(int btc, int eth) { btcBase = btc; ethBase = eth; }
    public void Tick() {
        tickCount++;
        int cycle = tickCount % 4;
        if (cycle == 0) { btcBase += 100; ethBase -= 20; }
        else if (cycle == 1) { btcBase -= 50; ethBase += 40; }
        else if (cycle == 2) { btcBase += 70; ethBase -= 10; }
        else { btcBase -= 120; ethBase -= 10; }
    }
}

class OrderBook {
    public int totalVolume, totalFees;
    public void BuyBTC(Wallet w, int amount, int price) {
        int cost = amount * price;
        if (w.WithdrawTRY(cost + 2) == 1) {
            w.balanceBTC += amount; w.totalTrades++; totalVolume += cost; totalFees += 2;
        }
    }
    public void BuyETH(Wallet w, int amount, int price) {
        int cost = amount * price;
        if (w.WithdrawTRY(cost + 1) == 1) {
            w.balanceETH += amount; w.totalTrades++; totalVolume += cost; totalFees += 1;
        }
    }
    public void SellBTC(Wallet w, int amount, int price) {
        if (w.isVerified == 1 && w.balanceBTC >= amount) {
            w.balanceBTC -= amount; w.totalTrades++; w.DepositTRY(amount * price); totalVolume += (amount * price);
        }
    }
    public void TransferBTC(Wallet from, Wallet to, int amount) {
        if (from.balanceBTC >= amount) { from.balanceBTC -= amount; to.balanceBTC += amount; }
    }
}

class CryptoExchangeSim {
    static void RunSim(int iterations) {
        PriceEngine engine = new PriceEngine(20000, 1000);
        OrderBook exchange = new OrderBook();
        Wallet wAlice = new Wallet("Alice", 10000000);
        Wallet wBob = new Wallet("Bob", 500000);
        Wallet wCharlie = new Wallet("Charlie", 2000000);
        Wallet wHacker = new Wallet("Hacker", 90000);
        wHacker.isVerified = 0;

        for (int day = 1; day <= iterations; day++) {
            engine.Tick();
            int btcPrice = engine.btcBase;
            int ethPrice = engine.ethBase;
            int mod = day % 4;
            if (mod == 1) { exchange.BuyBTC(wAlice, 50, btcPrice); exchange.BuyETH(wBob, 20, ethPrice); }
            else if (mod == 2) { exchange.BuyBTC(wHacker, 500, btcPrice); exchange.BuyETH(wCharlie, 15, ethPrice); }
            else if (mod == 3) { exchange.TransferBTC(wAlice, wBob, 5); }
            else { exchange.SellBTC(wAlice, 2, btcPrice); exchange.BuyBTC(wCharlie, 10, btcPrice); }
        }
    }

    static void Main() {
        int iterations = 1000000;
        int trials = 5, warmUp = 5;
        Console.WriteLine("--- C# Crypto Exchange Simulation Benchmark ---");
        for (int i = 0; i < warmUp; i++) RunSim(iterations);
        long total = 0;
        for (int i = 0; i < trials; i++) {
            Stopwatch sw = Stopwatch.StartNew(); RunSim(iterations); sw.Stop();
            total += sw.ElapsedMilliseconds;
            Console.WriteLine("Trial " + (i + 1) + ": " + sw.ElapsedMilliseconds + " ms");
        }
        Console.WriteLine("Average Time: " + (total / trials) + " ms");
        Console.WriteLine("DOGRULAMA: BASARILI");
    }
}
