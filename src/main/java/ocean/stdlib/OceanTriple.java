package ocean.stdlib;

import java.util.Objects;

/**
 * Generic 3-tuple Triple class for Ocean stdlib.
 */
public class OceanTriple<A, B, C> {
    private A first;
    private B second;
    private C third;

    public OceanTriple(A first, B second, C third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public static <A, B, C> OceanTriple<A, B, C> of(A first, B second, C third) {
        return new OceanTriple<>(first, second, third);
    }

    public A getFirst() { return first; }
    public B getSecond() { return second; }
    public C getThird() { return third; }

    public void setFirst(A first) { this.first = first; }
    public void setSecond(B second) { this.second = second; }
    public void setThird(C third) { this.third = third; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OceanTriple<?, ?, ?> triple = (OceanTriple<?, ?, ?>) o;
        return Objects.equals(first, triple.first) &&
               Objects.equals(second, triple.second) &&
               Objects.equals(third, triple.third);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second, third);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ", " + third + ")";
    }
}
