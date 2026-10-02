package ocean.stdlib;

import java.util.BitSet;

/**
 * Bit array wrapper class for Ocean stdlib.
 */
public class OceanBitSet {
    private final BitSet bitSet;

    public OceanBitSet() {
        this.bitSet = new BitSet();
    }

    public OceanBitSet(int nbits) {
        this.bitSet = new BitSet(nbits);
    }

    public void set(int bitIndex) {
        bitSet.set(bitIndex);
    }

    public void set(int bitIndex, boolean value) {
        bitSet.set(bitIndex, value);
    }

    public boolean get(int bitIndex) {
        return bitSet.get(bitIndex);
    }

    public void clear(int bitIndex) {
        bitSet.clear(bitIndex);
    }

    public void toggle(int bitIndex) {
        bitSet.flip(bitIndex);
    }

    public int cardinality() {
        return bitSet.cardinality();
    }

    public int size() {
        return bitSet.size();
    }

    public void clear() {
        bitSet.clear();
    }

    public OceanBitSet and(OceanBitSet other) {
        OceanBitSet copy = new OceanBitSet();
        if (other != null) {
            copy.bitSet.or(this.bitSet);
            copy.bitSet.and(other.bitSet);
        }
        return copy;
    }

    public OceanBitSet or(OceanBitSet other) {
        OceanBitSet copy = new OceanBitSet();
        copy.bitSet.or(this.bitSet);
        if (other != null) {
            copy.bitSet.or(other.bitSet);
        }
        return copy;
    }

    public OceanBitSet xor(OceanBitSet other) {
        OceanBitSet copy = new OceanBitSet();
        copy.bitSet.or(this.bitSet);
        if (other != null) {
            copy.bitSet.xor(other.bitSet);
        }
        return copy;
    }

    @Override
    public String toString() {
        return bitSet.toString();
    }
}
