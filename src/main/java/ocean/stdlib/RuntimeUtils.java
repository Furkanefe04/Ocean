package ocean.stdlib;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
/**
 * Ocean derleyicisi ve çalışma zamanı (runtime) için düşük seviyeli yardımcı işlevler.
 * Sanal iş parçacığı yöneticisi, primitif desen eşleme, konsol G/Ç ve tip dönüşüm desteği sağlar.
 */
public class RuntimeUtils {
    private static Scanner scanner;
    private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = createVirtualThreadExecutor();

    private static ExecutorService createVirtualThreadExecutor() {
        try {
            Method m = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) m.invoke(null);
        } catch (Throwable t) {
            return Executors.newCachedThreadPool();
        }
    }

    public static ExecutorService getVirtualThreadExecutor() {
        return VIRTUAL_THREAD_EXECUTOR;
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        ScopedValue.ScopeSnapshot capturedScope = ScopedValue.ScopeSnapshot.getCurrent();
        Runnable wrapped = Task.wrap(capturedScope, runnable);
        return CompletableFuture.runAsync(wrapped, VIRTUAL_THREAD_EXECUTOR);
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        ScopedValue.ScopeSnapshot capturedScope = ScopedValue.ScopeSnapshot.getCurrent();
        Supplier<T> wrapped = (capturedScope != null && supplier != null) ? () -> {
            ScopedValue.ScopeSnapshot prev = ScopedValue.ScopeSnapshot.getCurrent();
            ScopedValue.ScopeSnapshot.setCurrent(capturedScope);
            try {
                return supplier.get();
            } finally {
                ScopedValue.ScopeSnapshot.setCurrent(prev);
            }
        } : supplier;
        return CompletableFuture.supplyAsync(wrapped, VIRTUAL_THREAD_EXECUTOR);
    }

    public static Object await(Object futureObj) {
        switch (futureObj) {
            case null -> {
                return null;
            }
            case CompletableFuture<?> cf -> {
                try {
                    return cf.join();
                } catch (java.util.concurrent.CompletionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Error err) throw err;
                    if (cause != null) throw sneakyThrow(cause);
                    throw e;
                }
            }
            case java.util.concurrent.Future<?> f -> {
                try {
                    return f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Error err) throw err;
                    if (cause != null) throw sneakyThrow(cause);
                    throw sneakyThrow(e);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
            default -> {
            }
        }
        return futureObj;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    public static synchronized Scanner getScanner() {
        if (scanner == null) {
            scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        }
        return scanner;
    }

    public static Number add(Number left, Number right) {
        if (left == null || right == null) return null;
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            return new BigDecimal(left.toString()).add(new BigDecimal(right.toString()));
        }
        if (left instanceof Double || right instanceof Double) {
            return left.doubleValue() + right.doubleValue();
        }
        if (left instanceof Float || right instanceof Float) {
            return left.floatValue() + right.floatValue();
        }
        if (left instanceof Long || right instanceof Long) {
            return left.longValue() + right.longValue();
        }
        return left.intValue() + right.intValue();
    }

    public static Number subtract(Number left, Number right) {
        if (left == null || right == null) return null;
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            return new BigDecimal(left.toString()).subtract(new BigDecimal(right.toString()));
        }
        if (left instanceof Double || right instanceof Double) {
            return left.doubleValue() - right.doubleValue();
        }
        if (left instanceof Float || right instanceof Float) {
            return left.floatValue() - right.floatValue();
        }
        if (left instanceof Long || right instanceof Long) {
            return left.longValue() - right.longValue();
        }
        return left.intValue() - right.intValue();
    }

    public static Number multiply(Number left, Number right) {
        if (left == null || right == null) return null;
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            return new BigDecimal(left.toString()).multiply(new BigDecimal(right.toString()));
        }
        if (left instanceof Double || right instanceof Double) {
            return left.doubleValue() * right.doubleValue();
        }
        if (left instanceof Float || right instanceof Float) {
            return left.floatValue() * right.floatValue();
        }
        if (left instanceof Long || right instanceof Long) {
            return left.longValue() * right.longValue();
        }
        return left.intValue() * right.intValue();
    }

    public static Number divide(Number left, Number right) {
        if (left == null || right == null) return null;
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            return new BigDecimal(left.toString()).divide(new BigDecimal(right.toString()), MathContext.DECIMAL128);
        }
        if (left instanceof Double || right instanceof Double) {
            return left.doubleValue() / right.doubleValue();
        }
        if (left instanceof Float || right instanceof Float) {
            return left.floatValue() / right.floatValue();
        }
        if (left instanceof Long || right instanceof Long) {
            return left.longValue() / right.longValue();
        }
        return left.intValue() / right.intValue();
    }

    public static Number mod(Number left, Number right) {
        if (left == null || right == null) return null;
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            return new BigDecimal(left.toString()).remainder(new BigDecimal(right.toString()));
        }
        if (left instanceof Double || right instanceof Double) {
            return left.doubleValue() % right.doubleValue();
        }
        if (left instanceof Float || right instanceof Float) {
            return left.floatValue() % right.floatValue();
        }
        if (left instanceof Long || right instanceof Long) {
            return left.longValue() % right.longValue();
        }
        return left.intValue() % right.intValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;

        if (left instanceof Number n1 && right instanceof Number n2) {
            if (n1 instanceof BigDecimal || n2 instanceof BigDecimal) {
                return new BigDecimal(n1.toString()).compareTo(new BigDecimal(n2.toString()));
            }
            if (n1 instanceof Double || n2 instanceof Double || n1 instanceof Float || n2 instanceof Float) {
                return Double.compare(n1.doubleValue(), n2.doubleValue());
            }
            return Long.compare(n1.longValue(), n2.longValue());
        }

        if (left instanceof Comparable && left.getClass().isInstance(right)) {
            return ((Comparable) left).compareTo(right);
        }

        if (right instanceof Comparable && right.getClass().isInstance(left)) {
            return -((Comparable) right).compareTo(left);
        }

        return System.identityHashCode(left) - System.identityHashCode(right);
    }

    // Ocean Primitive Pattern Matching & Exact Range Checking
    public static boolean matchesPrimitiveByte(Object val) {
        if (val instanceof Byte) return true;
        if (val instanceof Short s) return s >= Byte.MIN_VALUE && s <= Byte.MAX_VALUE;
        if (val instanceof Integer i) return i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE;
        if (val instanceof Long l) return l >= Byte.MIN_VALUE && l <= Byte.MAX_VALUE;
        if (val instanceof Float f) return !f.isNaN() && !f.isInfinite() && f == (byte) f.floatValue();
        if (val instanceof Double d) return !d.isNaN() && !d.isInfinite() && d == (byte) d.doubleValue();
        if (val instanceof Character c) return c <= Byte.MAX_VALUE;
        return false;
    }

    public static boolean matchesPrimitiveShort(Object val) {
        if (val instanceof Short || val instanceof Byte) return true;
        if (val instanceof Integer i) return i >= Short.MIN_VALUE && i <= Short.MAX_VALUE;
        if (val instanceof Long l) return l >= Short.MIN_VALUE && l <= Short.MAX_VALUE;
        if (val instanceof Float f) return !f.isNaN() && !f.isInfinite() && f == (short) f.floatValue();
        if (val instanceof Double d) return !d.isNaN() && !d.isInfinite() && d == (short) d.doubleValue();
        if (val instanceof Character c) return c <= Short.MAX_VALUE;
        return false;
    }

    public static boolean matchesPrimitiveInt(Object val) {
        if (val instanceof Integer || val instanceof Short || val instanceof Byte || val instanceof Character) return true;
        if (val instanceof Long l) return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE;
        if (val instanceof Float f) return !f.isNaN() && !f.isInfinite() && f == (int) f.floatValue();
        if (val instanceof Double d) return !d.isNaN() && !d.isInfinite() && d == (int) d.doubleValue();
        return false;
    }

    public static boolean matchesPrimitiveLong(Object val) {
        if (val instanceof Long || val instanceof Integer || val instanceof Short || val instanceof Byte || val instanceof Character) return true;
        if (val instanceof Float f) return !f.isNaN() && !f.isInfinite() && f == (long) f.floatValue();
        if (val instanceof Double d) return !d.isNaN() && !d.isInfinite() && d == (long) d.doubleValue();
        return false;
    }

    public static boolean matchesPrimitiveFloat(Object val) {
        if (val instanceof Float || val instanceof Integer || val instanceof Short || val instanceof Byte || val instanceof Character) return true;
        if (val instanceof Double d) return (double) d.floatValue() == d;
        if (val instanceof Long l) return (long) (float) l == l;
        return false;
    }

    public static boolean matchesPrimitiveDouble(Object val) {
        return val instanceof Number || val instanceof Character;
    }

    public static boolean matchesPrimitiveChar(Object val) {
        if (val instanceof Character) return true;
        if (val instanceof Integer i) return i >= Character.MIN_VALUE && i <= Character.MAX_VALUE;
        if (val instanceof Short s) return s >= Character.MIN_VALUE;
        if (val instanceof Byte b) return b >= 0;
        if (val instanceof Long l) return l >= Character.MIN_VALUE && l <= Character.MAX_VALUE;
        return false;
    }

    public static boolean matchesPrimitiveBoolean(Object val) {
        return val instanceof Boolean;
    }

    public static byte toPrimitiveByte(Object val) {
        if (val instanceof Number n) return n.byteValue();
        if (val instanceof Character c) return (byte) c.charValue();
        return 0;
    }

    public static short toPrimitiveShort(Object val) {
        if (val instanceof Number n) return n.shortValue();
        if (val instanceof Character c) return (short) c.charValue();
        return 0;
    }

    public static int toPrimitiveInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof Character c) return c;
        return 0;
    }

    public static long toPrimitiveLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof Character c) return c;
        return 0L;
    }

    public static float toPrimitiveFloat(Object val) {
        if (val instanceof Number n) return n.floatValue();
        if (val instanceof Character c) return c;
        return 0.0f;
    }

    public static double toPrimitiveDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof Character c) return c;
        return 0.0;
    }

    public static char toPrimitiveChar(Object val) {
        if (val instanceof Character c) return c;
        if (val instanceof Number n) return (char) n.intValue();
        return '\0';
    }

    public static boolean toPrimitiveBoolean(Object val) {
        if (val instanceof Boolean b) return b;
        return false;
    }

    public static Object[] sliceArray(Object[] array, int from) {
        if (array == null) return null;
        int len = array.length;
        int actualFrom = Math.max(0, Math.min(from, len));
        return java.util.Arrays.copyOfRange(array, actualFrom, len);
    }

    public static int[] sliceArray(int[] array, int from) {
        if (array == null) return null;
        int len = array.length;
        int actualFrom = Math.max(0, Math.min(from, len));
        return java.util.Arrays.copyOfRange(array, actualFrom, len);
    }

    public static long[] sliceArray(long[] array, int from) {
        if (array == null) return null;
        int len = array.length;
        int actualFrom = Math.max(0, Math.min(from, len));
        return java.util.Arrays.copyOfRange(array, actualFrom, len);
    }

    public static double[] sliceArray(double[] array, int from) {
        if (array == null) return null;
        int len = array.length;
        int actualFrom = Math.max(0, Math.min(from, len));
        return java.util.Arrays.copyOfRange(array, actualFrom, len);
    }

    public static float[] sliceArray(float[] array, int from) {
        if (array == null) return null;
        int len = array.length;
        int actualFrom = Math.max(0, Math.min(from, len));
        return java.util.Arrays.copyOfRange(array, actualFrom, len);
    }

    public static boolean[] sliceArray(boolean[] array, int from) {
        if (array == null) return null;
        int len = array.length;
        int actualFrom = Math.max(0, Math.min(from, len));
        return java.util.Arrays.copyOfRange(array, actualFrom, len);
    }

    public static byte[] sliceArray(byte[] array, int from) {
        if (array == null) return null;
        int len = array.length;
        int actualFrom = Math.max(0, Math.min(from, len));
        return java.util.Arrays.copyOfRange(array, actualFrom, len);
    }

    public static short[] sliceArray(short[] array, int from) {
        if (array == null) return null;
        int len = array.length;
        int actualFrom = Math.max(0, Math.min(from, len));
        return java.util.Arrays.copyOfRange(array, actualFrom, len);
    }

    public static char[] sliceArray(char[] array, int from) {
        if (array == null) return null;
        int len = array.length;
        int actualFrom = Math.max(0, Math.min(from, len));
        return java.util.Arrays.copyOfRange(array, actualFrom, len);
    }

    public static String smartToString(Object obj) {
        return switch (obj) {
            case null -> "null";
            case Object[] objects -> java.util.Arrays.deepToString(objects);
            case int[] ints -> java.util.Arrays.toString(ints);
            case long[] longs -> java.util.Arrays.toString(longs);
            case double[] doubles -> java.util.Arrays.toString(doubles);
            case float[] floats -> java.util.Arrays.toString(floats);
            case boolean[] booleans -> java.util.Arrays.toString(booleans);
            case byte[] bytes -> java.util.Arrays.toString(bytes);
            case char[] chars -> java.util.Arrays.toString(chars);
            case short[] shorts -> java.util.Arrays.toString(shorts);
            default -> obj.toString();
        };
    }
}
