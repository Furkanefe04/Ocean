import time

def add(a, b):
    return a + b

def main():
    limit = 1000000000
    start = time.time()
    
    total_sum = 0
    for i in range(limit):
        total_sum += add(i, 1)
        
    end = time.time()
    print("PYTHON Calls Sum: {}, Time: {:.1f} ms".format(total_sum, (end - start) * 1000))

if __name__ == "__main__":
    main()
