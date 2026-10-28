package io.github.drtron1c.cloudphotos.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SRP-6a client exactly as Apple's iCloud web sign-in expects it:
 * RFC 5054 2048-bit group, SHA-256, padded k/u, and no username inside x.
 * The password is first stretched with Apple's "s2k" / "s2k_fo" PBKDF2 scheme.
 *
 * Written in plain Java (no Android APIs) so it can be unit-tested anywhere.
 */
public final class AppleSrp {

    private static final BigInteger N = new BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310D"
                    + "CD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF"
                    + "747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0"
                    + "D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9"
                    + "DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16);
    private static final BigInteger G = BigInteger.valueOf(2);
    private static final int N_LEN = 256;

    private final String accountName;
    private final BigInteger a;
    private final BigInteger bigA;

    private byte[] m1;
    private byte[] m2;

    public AppleSrp(String accountName) {
        this(accountName, randomPrivate());
    }

    /** Test hook: use a fixed 256-byte private value. */
    public AppleSrp(String accountName, byte[] privateA) {
        this.accountName = accountName;
        this.a = new BigInteger(1, privateA);
        this.bigA = G.modPow(a, N);
    }

    private static byte[] randomPrivate() {
        byte[] bytes = new byte[N_LEN];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** Public value A, minimal big-endian bytes (what Apple expects base64-encoded as "a"). */
    public byte[] publicA() {
        return unsigned(bigA);
    }

    /** Apple's password stretching. protocol is "s2k" or "s2k_fo". */
    public static byte[] derivePassword(String password, String protocol, byte[] salt, int iterations) {
        byte[] sha = sha256(password.getBytes(StandardCharsets.UTF_8));
        byte[] input = "s2k_fo".equals(protocol)
                ? toHex(sha).getBytes(StandardCharsets.US_ASCII)
                : sha;
        return pbkdf2Sha256(input, salt, iterations, 32);
    }

    /**
     * Process the server challenge. Returns M1 (also available via {@link #m1()}).
     * Throws IllegalStateException when the server values fail SRP-6a safety checks.
     */
    public byte[] processChallenge(byte[] salt, byte[] serverB, byte[] derivedPassword) {
        BigInteger bigB = new BigInteger(1, serverB);
        if (bigB.mod(N).signum() == 0) throw new IllegalStateException("Invalid server value B");

        BigInteger k = new BigInteger(1, hash(pad(N), pad(G)));
        BigInteger u = new BigInteger(1, hash(pad(bigA), pad(bigB)));
        if (u.signum() == 0) throw new IllegalStateException("Invalid scrambling parameter");

        byte[] inner = hash(":".getBytes(StandardCharsets.UTF_8), derivedPassword);
        BigInteger x = new BigInteger(1, hash(salt, inner));
        BigInteger v = G.modPow(x, N);

        BigInteger base = bigB.subtract(k.multiply(v)).mod(N);
        BigInteger exp = a.add(u.multiply(x));
        BigInteger s = base.modPow(exp, N);
        byte[] key = sha256(unsigned(s));

        byte[] hN = sha256(unsigned(N));
        byte[] hG = sha256(pad(G));
        byte[] xor = new byte[hN.length];
        for (int i = 0; i < xor.length; i++) xor[i] = (byte) (hN[i] ^ hG[i]);

        byte[] hI = sha256(accountName.getBytes(StandardCharsets.UTF_8));
        m1 = hash(xor, hI, salt, unsigned(bigA), unsigned(bigB), key);
        m2 = hash(unsigned(bigA), m1, key);
        return m1;
    }

    public byte[] m1() {
        return m1;
    }

    public byte[] m2() {
        return m2;
    }

    // ---- helpers ----

    static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) return Arrays.copyOfRange(bytes, 1, bytes.length);
        return bytes;
    }

    private static byte[] pad(BigInteger value) {
        byte[] raw = unsigned(value);
        if (raw.length >= N_LEN) return raw;
        byte[] out = new byte[N_LEN];
        System.arraycopy(raw, 0, out, N_LEN - raw.length, raw.length);
        return out;
    }

    private static byte[] hash(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[] data) {
        return hash(data);
    }

    static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** PBKDF2-HMAC-SHA256 over raw bytes (javax's PBEKeySpec only accepts chars, so do it by hand). */
    static byte[] pbkdf2Sha256(byte[] password, byte[] salt, int iterations, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA256"));
            int hLen = mac.getMacLength();
            int blocks = (length + hLen - 1) / hLen;
            byte[] out = new byte[length];
            int offset = 0;
            for (int block = 1; block <= blocks; block++) {
                mac.update(salt);
                mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
                byte[] u = mac.doFinal();
                byte[] t = u.clone();
                for (int i = 1; i < iterations; i++) {
                    u = mac.doFinal(u);
                    for (int j = 0; j < t.length; j++) t[j] ^= u[j];
                }
                int n = Math.min(hLen, length - offset);
                System.arraycopy(t, 0, out, offset, n);
                offset += n;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
