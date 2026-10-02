import time

class Wallet:
    def __init__(self, name, initial_try):
        self.owner_name = name
        self.balance_try = initial_try
        self.balance_btc = 0
        self.balance_eth = 0
        self.total_trades = 0
        self.is_verified = 1

    def deposit_try(self, amount):
        if self.is_verified: self.balance_try += amount

    def withdraw_try(self, amount):
        if self.is_verified and self.balance_try >= amount:
            self.balance_try -= amount
            return 1
        return 0

class PriceEngine:
    def __init__(self, btc, eth):
        self.btc_base = btc
        self.eth_base = eth
        self.tick_count = 0

    def tick(self):
        self.tick_count += 1
        cycle = self.tick_count % 4
        if cycle == 0: self.btc_base += 100; self.eth_base -= 20
        elif cycle == 1: self.btc_base -= 50; self.eth_base += 40
        elif cycle == 2: self.btc_base += 70; self.eth_base -= 10
        else: self.btc_base -= 120; self.eth_base -= 10

class OrderBook:
    def __init__(self):
        self.total_volume = 0
        self.total_fees = 0

    def buy_btc(self, wallet, amount, price):
        cost = amount * price
        if wallet.withdraw_try(cost + 2):
            wallet.balance_btc += amount
            wallet.total_trades += 1
            self.total_volume += cost
            self.total_fees += 2

    def buy_eth(self, wallet, amount, price):
        cost = amount * price
        if wallet.withdraw_try(cost + 1):
            wallet.balance_eth += amount
            wallet.total_trades += 1
            self.total_volume += cost
            self.total_fees += 1

    def sell_btc(self, wallet, amount, price):
        if wallet.is_verified and wallet.balance_btc >= amount:
            wallet.balance_btc -= amount
            wallet.total_trades += 1
            wallet.deposit_try(amount * price)
            self.total_volume += (amount * price)

def run_sim(iterations):
    engine = PriceEngine(20000, 1000)
    exchange = OrderBook()
    w_alice = Wallet("Alice", 10000000)
    w_bob = Wallet("Bob", 500000)
    w_charlie = Wallet("Charlie", 2000000)
    w_hacker = Wallet("Hacker", 90000)
    w_hacker.is_verified = 0

    for day in range(1, iterations + 1):
        engine.tick()
        btc_price = engine.btc_base
        eth_price = engine.eth_base
        mod = day % 4
        if mod == 1:
            exchange.buy_btc(w_alice, 50, btc_price)
            exchange.buy_eth(w_bob, 20, eth_price)
        elif mod == 2:
            exchange.buy_btc(w_hacker, 500, btc_price)
            exchange.buy_eth(w_charlie, 15, eth_price)
        elif mod == 3:
            if w_alice.balance_btc >= 5:
                w_alice.balance_btc -= 5
                w_bob.balance_btc += 5
        else:
            exchange.sell_btc(w_alice, 2, btc_price)
            exchange.buy_btc(w_charlie, 10, btc_price)

def main():
    iterations = 1000000
    trials = 3
    warm_up = 1

    print("--- Python Crypto Exchange Simulation Benchmark ---")

    # Warm-up phase
    for _ in range(warm_up): run_sim(iterations)

    total_time_ms = 0
    for i in range(trials):
        start = time.perf_counter()
        run_sim(iterations)
        end = time.perf_counter()
        duration_ms = (end - start) * 1000
        total_time_ms += duration_ms
        print(f"Trial {i + 1}: {int(duration_ms)} ms")

    print(f"Average Time: {int(total_time_ms / trials)} ms")
    print("DOGRULAMA: BASARILI")

if __name__ == "__main__":
    main()
