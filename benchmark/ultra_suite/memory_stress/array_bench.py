import time

def main():
    size = 100000000
    # Pre-allocating list
    arr = [0] * size
    
    start = time.time()
    total_sum = 0
    for i in range(size):
        val = i * 3 + 1
        arr[i] = val
        total_sum += val
        
    end = time.time()
    print("PYTHON Sum: {}, Time: {:.1f} ms".format(total_sum, (end - start) * 1000))

if __name__ == "__main__":
    main()
