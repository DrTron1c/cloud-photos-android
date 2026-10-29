package io.github.drtron1c.cloudphotos.photos;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal reader for Apple binary property lists ("bplist00"), enough for the small
 * dictionaries iCloud Photos stores in fields like locationEnc.
 * Returns Map / List / String / Long / Double / Boolean / byte[] (dates come back as
 * Double seconds since 2001-01-01). Plain Java so it can be unit-tested anywhere.
 */
public final class BPlist {
    private final byte[] data;
    private final int refSize;
    private final long[] offsets;

    private BPlist(byte[] data) {
        if (data.length < 40 || !new String(data, 0, 8, StandardCharsets.US_ASCII).equals("bplist00")) {
            throw new IllegalArgumentException("Not a binary plist");
        }
        this.data = data;
        int t = data.length - 32;
        int offsetSize = data[t + 6] & 0xFF;
        this.refSize = data[t + 7] & 0xFF;
        int numObjects = (int) readUInt(t + 8, 8);
        long tableOffset = readUInt(t + 24, 8);
        if (numObjects <= 0 || numObjects > 100_000) throw new IllegalArgumentException("Bad object count");
        offsets = new long[numObjects];
        for (int i = 0; i < numObjects; i++) offsets[i] = readUInt((int) tableOffset + i * offsetSize, offsetSize);
        this.top = (int) readUInt(t + 16, 8);
    }

    private final int top;

    public static Object parse(byte[] data) {
        BPlist p = new BPlist(data);
        return p.object(p.top, 0);
    }

    private long readUInt(int pos, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | (data[pos + i] & 0xFF);
        return v;
    }

    private Object object(int ref, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Too deep");
        int pos = (int) offsets[ref];
        int marker = data[pos] & 0xFF;
        int type = marker >> 4;
        int info = marker & 0x0F;
        switch (type) {
            case 0x0:
                if (info == 0x8) return Boolean.FALSE;
                if (info == 0x9) return Boolean.TRUE;
                return null;
            case 0x1: {
                int size = 1 << info;
                long v = readUInt(pos + 1, size);
                if (size == 8) return v; // signed 64-bit
                return v;
            }
            case 0x2: {
                int size = 1 << info;
                ByteBuffer bb = ByteBuffer.wrap(data, pos + 1, size);
                return size == 4 ? (double) bb.getFloat() : bb.getDouble();
            }
            case 0x3:
                return ByteBuffer.wrap(data, pos + 1, 8).getDouble();
            case 0x4: {
                int[] lo = length(pos, info);
                byte[] out = new byte[lo[0]];
                System.arraycopy(data, lo[1], out, 0, lo[0]);
                return out;
            }
            case 0x5: {
                int[] lo = length(pos, info);
                return new String(data, lo[1], lo[0], StandardCharsets.US_ASCII);
            }
            case 0x6: {
                int[] lo = length(pos, info);
                return new String(data, lo[1], lo[0] * 2, StandardCharsets.UTF_16BE);
            }
            case 0xA: {
                int[] lo = length(pos, info);
                List<Object> list = new ArrayList<>(lo[0]);
                for (int i = 0; i < lo[0]; i++) list.add(object((int) readUInt(lo[1] + i * refSize, refSize), depth + 1));
                return list;
            }
            case 0xD: {
                int[] lo = length(pos, info);
                Map<String, Object> map = new LinkedHashMap<>();
                int keysAt = lo[1];
                int valuesAt = lo[1] + lo[0] * refSize;
                for (int i = 0; i < lo[0]; i++) {
                    Object k = object((int) readUInt(keysAt + i * refSize, refSize), depth + 1);
                    Object v = object((int) readUInt(valuesAt + i * refSize, refSize), depth + 1);
                    map.put(String.valueOf(k), v);
                }
                return map;
            }
            default:
                return null;
        }
    }

    /** Returns {count, startOfPayload}. */
    private int[] length(int pos, int info) {
        if (info != 0x0F) return new int[]{info, pos + 1};
        int m = data[pos + 1] & 0xFF;
        int size = 1 << (m & 0x0F);
        int count = (int) readUInt(pos + 2, size);
        return new int[]{count, pos + 2 + size};
    }

    /** Latitude/longitude from an iCloud locationEnc plist, or null. */
    public static double[] latLon(byte[] data) {
        try {
            Object o = parse(data);
            if (!(o instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) o;
            Object lat = m.get("lat");
            Object lon = m.get("lon");
            if (!(lat instanceof Number) || !(lon instanceof Number)) return null;
            double a = ((Number) lat).doubleValue();
            double b = ((Number) lon).doubleValue();
            if (Double.isNaN(a) || Double.isNaN(b) || (a == 0 && b == 0) || Math.abs(a) > 90 || Math.abs(b) > 180) return null;
            return new double[]{a, b};
        } catch (RuntimeException e) {
            return null;
        }
    }
}
