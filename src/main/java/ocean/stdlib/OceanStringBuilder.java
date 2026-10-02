package ocean.stdlib;

/**
 * Chainable StringBuilder wrapper for Ocean stdlib.
 */
public class OceanStringBuilder {
    private final StringBuilder sb;

    public OceanStringBuilder() {
        this.sb = new StringBuilder();
    }

    public OceanStringBuilder(String initial) {
        this.sb = new StringBuilder(initial != null ? initial : "");
    }

    public OceanStringBuilder append(Object val) {
        sb.append(val);
        return this;
    }

    public OceanStringBuilder appendLine(Object val) {
        sb.append(val).append(System.lineSeparator());
        return this;
    }

    public OceanStringBuilder reverse() {
        sb.reverse();
        return this;
    }

    public OceanStringBuilder repeat(String str, int count) {
        if (str != null) {
            sb.append(str.repeat(Math.max(0, count)));
        }
        return this;
    }

    public OceanStringBuilder clear() {
        sb.setLength(0);
        return this;
    }

    public int length() {
        return sb.length();
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
