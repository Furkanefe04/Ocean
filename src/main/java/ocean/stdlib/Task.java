package ocean.stdlib;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
/**
 * Ocean standart kütüphanesinin eşzamanlılık (concurrency) ve sanal iş parçacığı (virtual threads) API'si.
 * {@code Task.sleep}, {@code Task.all}, {@code Task.race} ve asenkron çalıştırma işlevleri sunar.
 */
public final class Task {
    private Task() {}

    public static ExecutorService executor() {
        return RuntimeUtils.getVirtualThreadExecutor();
    }

    public static CompletableFuture<Void> run(Runnable runnable) {
        return RuntimeUtils.runAsync(runnable);
    }

    public static <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        return RuntimeUtils.supplyAsync(supplier);
    }

    public static Runnable wrap(ScopedValue.ScopeSnapshot captured,Runnable runnable) {
        return (captured != null && runnable != null) ? () -> {
            ScopedValue.ScopeSnapshot prev = ScopedValue.ScopeSnapshot.getCurrent();
            ScopedValue.ScopeSnapshot.setCurrent(captured);
            try {
                runnable.run();
            } finally {
                ScopedValue.ScopeSnapshot.setCurrent(prev);
            }
        } : runnable;
    }

    public static Thread start(Runnable runnable) {
        ScopedValue.ScopeSnapshot capturedScope = ScopedValue.ScopeSnapshot.getCurrent();
        Runnable wrapped = wrap(capturedScope, runnable);
        try {
            return Thread.ofVirtual().start(wrapped);
        } catch (Throwable t) {
            Thread th = new Thread(wrapped);
            th.start();
            return th;
        }
    }

    public static boolean isVirtual() {
        return Thread.currentThread().isVirtual();
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static CompletableFuture<Void> all(CompletableFuture<?>... futures) {
        if (futures == null || futures.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures);
    }

    public static CompletableFuture<Void> all(List<CompletableFuture<?>> futures) {
        if (futures == null || futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public static CompletableFuture<Object> race(CompletableFuture<?>... futures) {
        if (futures == null || futures.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.anyOf(futures);
    }

    public static CompletableFuture<Object> any(CompletableFuture<?>... futures) {
        return race(futures);
    }

    public static <T> List<T> gather(List<Supplier<T>> suppliers) {
        if (suppliers == null || suppliers.isEmpty()) {
            return new ArrayList<>();
        }
        List<CompletableFuture<T>> futures = new ArrayList<>(suppliers.size());
        for (Supplier<T> supplier : suppliers) {
            futures.add(RuntimeUtils.supplyAsync(supplier));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<T> results = new ArrayList<>(futures.size());
        for (CompletableFuture<T> f : futures) {
            results.add(f.join());
        }
        return results;
    }
}
