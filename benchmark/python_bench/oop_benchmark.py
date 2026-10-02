import time

class Base:
    def increment(self, n):
        return n + 1

class Sub(Base):
    def increment(self, self_n):
        return self_n + 2

def run_benchmark():
    start = time.time()
    total = 0
    for i in range(1000000):
        s = Sub()
        total += s.increment(1)
    end = time.time()
    print(f"Total: {total}")
    print(f"Python OOP Sresi: {int((end - start) * 1000)}ms")

if __name__ == "__main__":
    run_benchmark()
