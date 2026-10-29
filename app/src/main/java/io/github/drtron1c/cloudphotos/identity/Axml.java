package io.github.drtron1c.cloudphotos.identity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal editor for Android's compiled binary XML (the AndroidManifest.xml inside an APK):
 * reads the string pool and replaces one string, re-encoding the pool. Everything else in the
 * file refers to strings by index, so it stays valid untouched.
 */
public final class Axml {
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int SORTED_FLAG = 1;
    private static final int UTF8_FLAG = 1 << 8;

    private Axml() {}

    /** All strings in the file's string pool. */
    public static List<String> strings(byte[] axml) {
        return read(axml).strings;
    }

    /** Returns a copy of [axml] with every pool string equal to [oldValue] replaced. */
    public static byte[] replaceString(byte[] axml, String oldValue, String newValue) {
        Pool pool = read(axml);
        boolean found = false;
        for (int i = 0; i < pool.strings.size(); i++) {
            if (pool.strings.get(i).equals(oldValue)) {
                pool.strings.set(i, newValue);
                found = true;
            }
        }
        if (!found) throw new IllegalArgumentException("Placeholder not found in manifest");

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[pool.strings.size()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = data.size();
            byte[] enc = pool.utf8 ? encodeUtf8(pool.strings.get(i)) : encodeUtf16(pool.strings.get(i));
            data.write(enc, 0, enc.length);
        }
        while (data.size() % 4 != 0) data.write(0);
        byte[] stringData = data.toByteArray();

        int stringsStart = pool.headerSize + 4 * offsets.length + 4 * pool.styleOffsets.length;
        int stylesStart = pool.styleOffsets.length > 0 ? stringsStart + stringData.length : 0;
        int poolSize = stringsStart + stringData.length + pool.styleData.length;

        ByteBuffer out = ByteBuffer.allocate(axml.length - pool.size + poolSize).order(ByteOrder.LITTLE_ENDIAN);
        // File header (and any extra header bytes), with the new total size.
        out.put(axml, 0, pool.start);
        out.putInt(4, axml.length - pool.size + poolSize);
        // String pool header: copy, then fix sizes / offsets. Order changed, so drop "sorted".
        int p = out.position();
        out.put(axml, pool.start, pool.headerSize);
        out.putInt(p + 4, poolSize);
        out.putInt(p + 16, pool.flags & ~SORTED_FLAG);
        out.putInt(p + 20, stringsStart);
        out.putInt(p + 24, stylesStart);
        for (int o : offsets) out.putInt(o);
        for (int o : pool.styleOffsets) out.putInt(o);
        out.put(stringData);
        out.put(pool.styleData);
        // The rest of the document is unchanged.
        out.put(axml, pool.start + pool.size, axml.length - pool.start - pool.size);
        return out.array();
    }

    private static final class Pool {
        int start, size, headerSize, flags;
        boolean utf8;
        List<String> strings;
        int[] styleOffsets;
        byte[] styleData;
    }

    private static Pool read(byte[] axml) {
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xffff) != RES_XML_TYPE) throw new IllegalArgumentException("Not a binary XML file");
        Pool pool = new Pool();
        pool.start = b.getShort(2) & 0xffff;
        if ((b.getShort(pool.start) & 0xffff) != RES_STRING_POOL_TYPE) throw new IllegalArgumentException("No string pool");
        pool.headerSize = b.getShort(pool.start + 2) & 0xffff;
        pool.size = b.getInt(pool.start + 4);
        int stringCount = b.getInt(pool.start + 8);
        int styleCount = b.getInt(pool.start + 12);
        pool.flags = b.getInt(pool.start + 16);
        int stringsStart = b.getInt(pool.start + 20);
        int stylesStart = b.getInt(pool.start + 24);
        pool.utf8 = (pool.flags & UTF8_FLAG) != 0;

        pool.strings = new ArrayList<>(stringCount);
        for (int i = 0; i < stringCount; i++) {
            int off = b.getInt(pool.start + pool.headerSize + 4 * i);
            int pos = pool.start + stringsStart + off;
            pool.strings.add(pool.utf8 ? decodeUtf8(axml, pos) : decodeUtf16(b, pos));
        }
        pool.styleOffsets = new int[styleCount];
        for (int i = 0; i < styleCount; i++) {
            pool.styleOffsets[i] = b.getInt(pool.start + pool.headerSize + 4 * stringCount + 4 * i);
        }
        pool.styleData = styleCount > 0
                ? Arrays.copyOfRange(axml, pool.start + stylesStart, pool.start + pool.size)
                : new byte[0];
        return pool;
    }

    private static String decodeUtf8(byte[] a, int pos) {
        int[] r = readLen8(a, pos); // UTF-16 length (unused)
        pos = r[1];
        r = readLen8(a, pos);
        int byteLen = r[0];
        pos = r[1];
        return new String(a, pos, byteLen, StandardCharsets.UTF_8);
    }

    private static int[] readLen8(byte[] a, int pos) {
        int len = a[pos++] & 0xff;
        if ((len & 0x80) != 0) len = ((len & 0x7f) << 8) | (a[pos++] & 0xff);
        return new int[] {len, pos};
    }

    private static String decodeUtf16(ByteBuffer b, int pos) {
        int len = b.getShort(pos) & 0xffff;
        pos += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7fff) << 16) | (b.getShort(pos) & 0xffff);
            pos += 2;
        }
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) chars[i] = (char) (b.getShort(pos + 2 * i) & 0xffff);
        return new String(chars);
    }

    private static byte[] encodeUtf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (s.length() > 0x7fff || bytes.length > 0x7fff) throw new IllegalArgumentException("String too long");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeLen8(o, s.length());
        writeLen8(o, bytes.length);
        o.write(bytes, 0, bytes.length);
        o.write(0);
        return o.toByteArray();
    }

    private static void writeLen8(ByteArrayOutputStream o, int len) {
        if (len > 0x7f) {
            o.write(((len >> 8) & 0x7f) | 0x80);
            o.write(len & 0xff);
        } else {
            o.write(len);
        }
    }

    private static byte[] encodeUtf16(String s) {
        int len = s.length();
        if (len > 0x7fff) throw new IllegalArgumentException("String too long");
        ByteBuffer o = ByteBuffer.allocate(2 + 2 * len + 2).order(ByteOrder.LITTLE_ENDIAN);
        o.putShort((short) len);
        for (int i = 0; i < len; i++) o.putChar(s.charAt(i));
        o.putShort((short) 0);
        return o.array();
    }
}
