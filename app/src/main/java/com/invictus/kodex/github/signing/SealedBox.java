package com.invictus.kodex.github.signing;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * libsodium-compatible {@code crypto_box_seal}, the encryption GitHub requires for Actions secrets:
 * ephemeral X25519 key, nonce = BLAKE2b-24(epk || recipientPk), XSalsa20-Poly1305. Output = epk (32) || mac (16) || ciphertext.
 * Self-contained (no JCA XDH, which only exists from Android 13), so it works on every supported API level.
 */
public final class SealedBox {
    private SealedBox() {}

    public static byte[] seal(byte[] message, byte[] recipientPublicKey) {
        if (recipientPublicKey.length != 32) throw new IllegalArgumentException("public key must be 32 bytes");
        byte[] esk = new byte[32];
        new SecureRandom().nextBytes(esk);
        byte[] epk = x25519(esk, BASE);
        byte[] nonceInput = new byte[64];
        System.arraycopy(epk, 0, nonceInput, 0, 32);
        System.arraycopy(recipientPublicKey, 0, nonceInput, 32, 32);
        byte[] nonce = blake2b(nonceInput, 24);
        byte[] shared = x25519(esk, recipientPublicKey);
        byte[] key = hsalsa20(shared, new byte[16]);
        byte[] box = secretbox(message, nonce, key);
        byte[] out = new byte[32 + box.length];
        System.arraycopy(epk, 0, out, 0, 32);
        System.arraycopy(box, 0, out, 32, box.length);
        return out;
    }

    // ---- X25519 (RFC 7748) --------------------------------------------------------------------

    private static final byte[] BASE = new byte[32];
    static { BASE[0] = 9; }
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);

    static byte[] x25519(byte[] scalar, byte[] uBytes) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= 127;
        k[31] |= 64;
        BigInteger kn = decodeLE(k);
        byte[] u = uBytes.clone();
        u[31] &= 127;
        BigInteger x1 = decodeLE(u).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        boolean swap = false;
        for (int t = 254; t >= 0; t--) {
            boolean bit = kn.testBit(t);
            if (swap != bit) {
                BigInteger tx = x2; x2 = x3; x3 = tx;
                BigInteger tz = z2; z2 = z3; z3 = tz;
            }
            swap = bit;
            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger s = da.add(cb).mod(P);
            BigInteger df = da.subtract(cb).mod(P);
            x3 = s.multiply(s).mod(P);
            z3 = x1.multiply(df.multiply(df).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap) {
            BigInteger tx = x2; x2 = x3; x3 = tx;
            BigInteger tz = z2; z2 = z3; z3 = tz;
        }
        BigInteger res = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);
        return encodeLE(res, 32);
    }

    private static BigInteger decodeLE(byte[] b) {
        byte[] be = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) be[b.length - i] = b[i];
        return new BigInteger(be);
    }

    private static byte[] encodeLE(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) {
            if (be.length - 1 - i >= 0) out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    // ---- Salsa20 family -----------------------------------------------------------------------

    private static final byte[] SIGMA = {'e', 'x', 'p', 'a', 'n', 'd', ' ', '3', '2', '-', 'b', 'y', 't', 'e', ' ', 'k'};

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static void putLe32(int v, byte[] b, int o) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
    }

    private static int rotl(int v, int c) { return (v << c) | (v >>> (32 - c)); }

    private static void rounds(int[] x) {
        for (int i = 0; i < 10; i++) {
            x[4] ^= rotl(x[0] + x[12], 7);  x[8] ^= rotl(x[4] + x[0], 9);
            x[12] ^= rotl(x[8] + x[4], 13); x[0] ^= rotl(x[12] + x[8], 18);
            x[9] ^= rotl(x[5] + x[1], 7);   x[13] ^= rotl(x[9] + x[5], 9);
            x[1] ^= rotl(x[13] + x[9], 13); x[5] ^= rotl(x[1] + x[13], 18);
            x[14] ^= rotl(x[10] + x[6], 7); x[2] ^= rotl(x[14] + x[10], 9);
            x[6] ^= rotl(x[2] + x[14], 13); x[10] ^= rotl(x[6] + x[2], 18);
            x[3] ^= rotl(x[15] + x[11], 7); x[7] ^= rotl(x[3] + x[15], 9);
            x[11] ^= rotl(x[7] + x[3], 13); x[15] ^= rotl(x[11] + x[7], 18);
            x[1] ^= rotl(x[0] + x[3], 7);   x[2] ^= rotl(x[1] + x[0], 9);
            x[3] ^= rotl(x[2] + x[1], 13);  x[0] ^= rotl(x[3] + x[2], 18);
            x[6] ^= rotl(x[5] + x[4], 7);   x[7] ^= rotl(x[6] + x[5], 9);
            x[4] ^= rotl(x[7] + x[6], 13);  x[5] ^= rotl(x[4] + x[7], 18);
            x[11] ^= rotl(x[10] + x[9], 7); x[8] ^= rotl(x[11] + x[10], 9);
            x[9] ^= rotl(x[8] + x[11], 13); x[10] ^= rotl(x[9] + x[8], 18);
            x[12] ^= rotl(x[15] + x[14], 7); x[13] ^= rotl(x[12] + x[15], 9);
            x[14] ^= rotl(x[13] + x[12], 13); x[15] ^= rotl(x[14] + x[13], 18);
        }
    }

    private static int[] state(byte[] key, byte[] in16) {
        int[] s = new int[16];
        s[0] = le32(SIGMA, 0); s[5] = le32(SIGMA, 4); s[10] = le32(SIGMA, 8); s[15] = le32(SIGMA, 12);
        for (int i = 0; i < 4; i++) { s[1 + i] = le32(key, 4 * i); s[11 + i] = le32(key, 16 + 4 * i); }
        for (int i = 0; i < 4; i++) s[6 + i] = le32(in16, 4 * i);
        return s;
    }

    static byte[] hsalsa20(byte[] key, byte[] in16) {
        int[] x = state(key, in16);
        rounds(x);
        byte[] out = new byte[32];
        int[] idx = {0, 5, 10, 15, 6, 7, 8, 9};
        for (int i = 0; i < 8; i++) putLe32(x[idx[i]], out, 4 * i);
        return out;
    }

    private static byte[] salsa20Block(byte[] key, byte[] nonce8, long counter) {
        byte[] in = new byte[16];
        System.arraycopy(nonce8, 0, in, 0, 8);
        putLe32((int) counter, in, 8);
        putLe32((int) (counter >>> 32), in, 12);
        int[] s = state(key, in);
        int[] x = s.clone();
        rounds(x);
        byte[] out = new byte[64];
        for (int i = 0; i < 16; i++) putLe32(x[i] + s[i], out, 4 * i);
        return out;
    }

    /** XSalsa20 keystream XOR; [skip] bytes of the stream are discarded first (32 for the Poly1305 key). */
    private static byte[] xsalsa20Xor(byte[] data, byte[] nonce24, byte[] key, int skip) {
        byte[] n16 = new byte[16];
        System.arraycopy(nonce24, 0, n16, 0, 16);
        byte[] sub = hsalsa20(key, n16);
        byte[] n8 = new byte[8];
        System.arraycopy(nonce24, 16, n8, 0, 8);
        byte[] out = new byte[data.length];
        long pos = skip;
        for (int i = 0; i < data.length; i++, pos++) {
            byte[] block = salsa20Block(sub, n8, pos / 64);
            out[i] = (byte) (data[i] ^ block[(int) (pos % 64)]);
        }
        return out;
    }

    static byte[] secretbox(byte[] msg, byte[] nonce24, byte[] key) {
        byte[] polyKey = xsalsa20Xor(new byte[32], nonce24, key, 0);
        byte[] ct = xsalsa20Xor(msg, nonce24, key, 32);
        byte[] mac = poly1305(ct, polyKey);
        byte[] out = new byte[16 + ct.length];
        System.arraycopy(mac, 0, out, 0, 16);
        System.arraycopy(ct, 0, out, 16, ct.length);
        return out;
    }

    // ---- Poly1305 -----------------------------------------------------------------------------

    private static byte[] poly1305(byte[] msg, byte[] key32) {
        byte[] rb = new byte[16];
        System.arraycopy(key32, 0, rb, 0, 16);
        rb[3] &= 15; rb[7] &= 15; rb[11] &= 15; rb[15] &= 15;
        rb[4] &= (byte) 252; rb[8] &= (byte) 252; rb[12] &= (byte) 252;
        BigInteger r = decodeLE(rb);
        BigInteger p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
        BigInteger acc = BigInteger.ZERO;
        for (int i = 0; i < msg.length; i += 16) {
            int n = Math.min(16, msg.length - i);
            byte[] blk = new byte[n + 1];
            for (int j = 0; j < n; j++) blk[j] = msg[i + j];
            blk[n] = 1;
            acc = acc.add(decodeLE(blk)).multiply(r).mod(p);
        }
        byte[] sb = new byte[16];
        System.arraycopy(key32, 16, sb, 0, 16);
        BigInteger tag = acc.add(decodeLE(sb)).mod(BigInteger.ONE.shiftLeft(128));
        return encodeLE(tag, 16);
    }

    // ---- BLAKE2b (RFC 7693), unkeyed ------------------------------------------------------------

    private static final long[] IV = {
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    };
    private static final byte[][] SIG = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
        {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4}, {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
        {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13}, {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
        {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11}, {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
        {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5}, {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
    };

    static byte[] blake2b(byte[] in, int outLen) {
        long[] h = IV.clone();
        h[0] ^= 0x01010000L ^ outLen;
        int blocks = Math.max(1, (in.length + 127) / 128);
        for (int b = 0; b < blocks; b++) {
            byte[] blk = new byte[128];
            int off = b * 128;
            int n = Math.min(128, in.length - off);
            if (n > 0) System.arraycopy(in, off, blk, 0, n);
            boolean last = b == blocks - 1;
            compress(h, blk, last ? in.length : (long) (b + 1) * 128, last);
        }
        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) out[i] = (byte) (h[i / 8] >>> (8 * (i % 8)));
        return out;
    }

    private static void compress(long[] h, byte[] blk, long t, boolean last) {
        long[] m = new long[16];
        for (int i = 0; i < 16; i++) {
            long v = 0;
            for (int j = 7; j >= 0; j--) v = (v << 8) | (blk[i * 8 + j] & 0xffL);
            m[i] = v;
        }
        long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= t;
        if (last) v[14] = ~v[14];
        for (int r = 0; r < 12; r++) {
            byte[] s = SIG[r % 10];
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]); g(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]); g(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]); g(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]); g(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
    }

    private static void g(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x; v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];     v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y; v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];     v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }
}
