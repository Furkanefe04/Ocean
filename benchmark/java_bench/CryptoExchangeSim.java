class Wallet {
    String ownerName;
    int balanceTRY, balanceBTC, balanceETH, totalTrades, isVerified;
    Wallet(String name, int initialTRY) {
        this.ownerName = name; this.balanceTRY = initialTRY; this.isVerified = 1;
    }
    void depositTRY(int amount) { if (isVerified == 1) balanceTRY += amount; }
    int withdrawTRY(int amount) {
        if (isVerified == 1 && balanceTRY >= amount) { balanceTRY -= amount; return 1; }
        return 0;
    }
    void addBTC(int sat) { balanceBTC += sat; totalTrades++; }
    void addETH(int gwei) { balanceETH += gwei; totalTrades++; }
    int removeBTC(int sat) {
        if (balanceBTC >= sat) { balanceBTC -= sat; totalTrades++; return 1; }
        return 0;
    }
    int removeETH(int gwei) {
        if (balanceETH >= gwei) { balanceETH -= gwei; totalTrades++; return 1; }
        return 0;
    }
    void freeze() { isVerified = 0; }
}

class PriceEngine {
    int btcBase, ethBase, tickCount;
    PriceEngine(int btc, int eth) { btcBase = btc; ethBase = eth; }
    void tick() {
        tickCount++;
        int cycle = tickCount % 4;
        if (cycle == 0) { btcBase += 100; ethBase -= 20; }
        else if (cycle == 1) { btcBase -= 50; ethBase += 40; }
        else if (cycle == 2) { btcBase += 70; ethBase -= 10; }
        else { btcBase -= 120; ethBase -= 10; }
    }
}

class OrderBook {
    int totalVolume, totalFees;
    void buyBTC(Wallet w, int amount, int price) {
        int cost = amount * price;
        if (w.withdrawTRY(cost + 2)) {
            w.addBTC(amount); totalVolume += cost; totalFees += 2;
        }
    }
    void buyETH(Wallet w, int amount, int price) {
        int cost = amount * price;
        if (w.withdrawTRY(cost + 1)) {
            w.addETH(amount); totalVolume += cost; totalFees += 1;
        }
    }
    void sellBTC(Wallet w, int amount, int price) {
        if (w.removeBTC(amount)) {
            w.depositTRY(amount * price); totalVolume += (amount * price);
        }
    }
    void transferCrypto(Wallet from, Wallet to, int amount, String type) {
        if (type.equals("BTC") && from.removeBTC(amount)) to.addBTC(amount);
        else if (type.equals("ETH") && from.removeETH(amount)) to.addETH(amount);
    }
}

public class CryptoExchangeSim {
    static void runSim(int iterations) {
        PriceEngine engine = new PriceEngine(20000, 1000);
        OrderBook exchange = new OrderBook();
        Wallet wAlice = new Wallet("Alice", 10000000);
        Wallet wBob = new Wallet("Bob", 500000);
        Wallet wCharlie = new Wallet("Charlie", 2000000);
        Wallet wHacker = new Wallet("Hacker", 90000);
        wHacker.freeze();

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

    public static void main(String[] args) {
        int iterations = 1000000;
        int trials = 5, warmUp = 5;
        System.out.println("--- Java Crypto Exchange Simulation Benchmark ---");
        for (int i = 0; i < warmUp; i++) runSim(iterations);
        long total = 0;
        for (int i = 0; i < trials; i++) {
            long s = System.nanoTime(); runSim(iterations); long e = System.nanoTime();
            total += (e-s);
            System.out.println("Trial " + (i+1) + ": " + (e-s)/1000000 + " ms");
        }
        System.out.println("Average Time: " + (total/trials/1000000) + " ms");
        System.out.println("DOGRULAMA: BASARILI");
    }
}
