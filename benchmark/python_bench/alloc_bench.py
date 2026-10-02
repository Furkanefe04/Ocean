import time

class Node:
    __slots__ = ['a', 'b'] # To give python a fighting chance against full dictionary allocations
    def __init__(self, a, b):
        self.a = a
        self.b = b

def main():
    start = time.time()
    total_sum = 0
    for i in range(1, 50000001):
        n = Node(i, i + 1)
        if i == 49999999:
            total_sum = n.a + n.b
            
    dur = int((time.time() - start) * 1000)
    print("==============================================")
    print("  PYTHON OBJECT ALLOC (50M) BENCHMARK")
    print("  Sure: {} ms".format(dur))
    print("  Hesap: {}".format(total_sum))
    print("==============================================")

if __name__ == "__main__":
    main()
