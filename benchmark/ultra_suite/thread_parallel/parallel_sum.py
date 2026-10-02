import threading
import time

class SumThread(threading.Thread):
    def __init__(self, arr, start, end):
        super().__init__()
        self.arr = arr
        self.start_idx = start
        self.end_idx = end
        self.partial_sum = 0

    def run(self):
        s = 0
        for i in range(self.start_idx, self.end_idx):
            s += self.arr[i]
        self.partial_sum = s

def main():
    size = 100000000
    arr = list(range(size))
    
    num_threads = 8
    threads = []
    chunk_size = size // num_threads
    
    start_time = time.time()
    
    for i in range(num_threads):
        start_idx = i * chunk_size
        end_idx = size if i == num_threads - 1 else (i + 1) * chunk_size
        t = SumThread(arr, start_idx, end_idx)
        threads.append(t)
        t.start()
        
    total_sum = 0
    for t in threads:
        t.join()
        total_sum += t.partial_sum
        
    end_time = time.time()
    print("PYTHON Parallel Sum: {}, Time: {:.1f} ms".format(total_sum, (end_time - start_time) * 1000))

if __name__ == "__main__":
    main()
