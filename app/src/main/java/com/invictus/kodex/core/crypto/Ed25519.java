package com.invictus.kodex.core.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * Ed25519 public key from a 32-byte private seed (RFC 8032), on plain BigInteger so it works on every Android version
 * (the platform only offers Ed25519 from Android 13).
 */
public final class Ed25519 {
    private Ed25519() {}

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger Q = P; // field prime, alias for readability below
    private static final BigInteger[] BASE = basePoint();

    public static byte[] publicFromSeed(byte[] seed) {
        if (seed.length != 32) throw new IllegalArgumentException("seed must be 32 bytes");
        byte[] h;
        try {
            h = MessageDigest.getInstance("SHA-512").digest(seed);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        h[0] &= (byte) 248;
        h[31] &= 127;
        h[31] |= 64;
        BigInteger a = fromLE(h, 32);
        return encode(mul(a, BASE));
    }

    // Points are extended coordinates [X, Y, Z, T].
    private static BigInteger[] basePoint() {
        BigInteger y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger x = recoverX(y, false);
        return new BigInteger[] {x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    private static BigInteger recoverX(BigInteger y, boolean sign) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger x2 = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P);
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) {
            x = x.multiply(BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)).mod(P);
        }
        if (x.testBit(0) != sign) x = P.subtract(x);
        return x;
    }

    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = BigInteger.TWO.multiply(p[3]).multiply(q[3]).multiply(D).mod(P);
        BigInteger d = BigInteger.TWO.multiply(p[2]).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new BigInteger[] {e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] mul(BigInteger s, BigInteger[] pt) {
        BigInteger[] q = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        BigInteger[] cur = pt;
        for (int i = 0; i < s.bitLength(); i++) {
            if (s.testBit(i)) q = add(q, cur);
            cur = add(cur, cur);
        }
        return q;
    }

    private static byte[] encode(BigInteger[] p) {
        BigInteger zinv = p[2].modInverse(P);
        BigInteger x = p[0].multiply(zinv).mod(P);
        BigInteger y = p[1].multiply(zinv).mod(P);
        byte[] out = toLE(y, 32);
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    private static BigInteger fromLE(byte[] b, int len) {
        byte[] be = new byte[len + 1];
        for (int i = 0; i < len; i++) be[len - i] = b[i];
        return new BigInteger(be);
    }

    private static byte[] toLE(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
