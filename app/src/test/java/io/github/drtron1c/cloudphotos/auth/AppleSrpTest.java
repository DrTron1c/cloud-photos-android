package io.github.drtron1c.cloudphotos.auth;

import static org.junit.Assert.assertEquals;

import java.util.Base64;

import org.junit.Test;

/**
 * Vectors generated with pysrp (rfc5054 + no_username_in_x, SHA-256, NG_2048) — the same
 * library icloudpd / pyicloud use to sign in to iCloud — with a pysrp server verifying M1.
 */
public class AppleSrpTest {

    private static final String ACCOUNT = "jane.appleseed@example.com";
    private static final String PASSWORD = "Corr3ct-h0rse ✓ battery";
    private static final int ITERATIONS = 20309;

    private static byte[] salt() {
        byte[] s = new byte[16];
        for (int i = 0; i < 16; i++) s[i] = (byte) (i + 1);
        return s;
    }

    private static byte[] fixedA() {
        byte[] a = new byte[256];
        for (int i = 0; i < 256; i++) a[i] = (byte) ((i * 7 + 3) % 256);
        return a;
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }

    private static final String EXPECTED_A =
            "OP1phOePkrUDMZXfxP+tPi3wcgjsnn+Y2jmAWegDuhlnX5JsYHZOP0S2nCerSefdIqNyZfo7EdY/grM32Tke0S6KRtQmhXzRVjh4TMHhjC5NvhYnfypux51es+hw5pA1qvhgkz2+njynRGRue5//MWCG8/cXhKlFZ9bGVxs4+SG81k0PYL8xksjy64jhRhJ6bbvidnwPmt02u+g79mAcHdj85OiqUcjIDkDFLm2dISxwQRggBzsJHqAnP+CnsqTO57fw5hbynJ9HoSaslexngQnX3RQicuN/rhoWsRdKvD+uDVj1iIzq06Ym3QqTM+ohNAiHw1hGX3H6yqWZ6/ABxw==";

    @Test
    public void s2kMatchesReference() {
        byte[] key = AppleSrp.derivePassword(PASSWORD, "s2k", salt(), ITERATIONS);
        assertEquals("471e4e12bf0e4a9faa758fb0db79062a40a1d77d6794b0bf11d364e53d721bab", AppleSrp.toHex(key));
        AppleSrp srp = new AppleSrp(ACCOUNT, fixedA());
        assertEquals(EXPECTED_A, b64(srp.publicA()));
        String b = "brPWn8uYzbRfz1VhWrnP3emtFuuDmUJ9e3V7jQJnDHeH7ygyuW2EnJ0ClG/g8qmrNzR9RA1LzunksTiQ+10gAYvXGm3gBDoJGAM9tyrB4jfG25qVFT4/mlN5pwZZFZbmImSOOFUZp2ItvVli2sk4RW3SZFNdLtBe9ImU15h3hMStdGEF481aEucQj6mZZXh0OvmptplEvFpK3AqYDe7WeAp905Fis5o9tLAj1TLvYwYQxNA3nJAveOFcRYDzOdaHwI1AnVZW2EqbvtdlAdwOjhkaU81+ChQROKZ6L88M5rfgnkbTr/cSlTe8Il1oY+lCmoe8Pwy25Ryr6nIULZJyZA==";
        srp.processChallenge(salt(), unb64(b), key);
        assertEquals("ye1GUTYs9bjKOC7tCN38zjk7ITPD0/RY/AY2jiLFHHQ=", b64(srp.m1()));
        assertEquals("I8LpFlNkvge/S2ozSrAgpkwJkEI/whRjnlG7qIlAoio=", b64(srp.m2()));
    }

    @Test
    public void s2kFoMatchesReference() {
        byte[] key = AppleSrp.derivePassword(PASSWORD, "s2k_fo", salt(), ITERATIONS);
        assertEquals("d71274179bea0efdb1795621f82b9bcb8abd40a3949d3fc950fa2aec4a2d1e51", AppleSrp.toHex(key));
        AppleSrp srp = new AppleSrp(ACCOUNT, fixedA());
        String b = "eJEyrBt0TXh00gUDIwxREej5App0fTrIx+3NUk5Z/19SJp0J3qlfHQf0qCZgMkw1/6TJFaZkGEsjKKKz9zNmBqy185twgsZxJ3TgCiMa+0oCYuZGHpIIK2ByeAFFfj/LyaJmBVoJDdkaMWwkhvj79TktWzIMkWD2GI1Lj7uepjcgq5tNj42E+GW9qx3Wfk9NZEWYA23FsG5BVZG4JLeh1ju+DfOBzumTRLpfOG+wPGJogHCQbadC0PIhcb7MSJ6kzxHTOUDnOzil10wKatuWa2ycGPmeYo3mDw0B6LgGolKzsYxMRVhvr0PD/NKoN/ZsyHHuORj2QLv048oTXig3xg==";
        srp.processChallenge(salt(), unb64(b), key);
        assertEquals("vxcO2OmShpQE1bjp2Ui/y/sglZgVjcf5jt4FONLcAn0=", b64(srp.m1()));
        assertEquals("qqgd4Y2n2iZfiI4iWA7Eg8S+UMiJzt0mYRwR6eCffFY=", b64(srp.m2()));
    }
}
