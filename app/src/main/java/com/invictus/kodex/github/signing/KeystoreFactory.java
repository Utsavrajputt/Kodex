package com.invictus.kodex.github.signing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Builds an Android app-signing keystore (PKCS12, RSA 2048, SHA256withRSA, self-signed) with no third-party library:
 * the certificate is assembled as DER by hand. The store and key share one password, which modern JDK tooling
 * (Gradle's signingConfig, apksigner) requires for PKCS12.
 */
public final class KeystoreFactory {
    private KeystoreFactory() {}

    /** @param country two-letter code or blank; [ou] and [org] may be blank; [commonName] is required. */
    public static byte[] generate(
        String alias, char[] password, String commonName, String ou, String org, String country, int validityYears
    ) throws Exception {
        if (commonName == null || commonName.trim().isEmpty()) throw new IllegalArgumentException("common name required");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        byte[] sigAlg = seq(oid("1.2.840.113549.1.1.11"), new byte[] {0x05, 0x00});
        byte[] name = name(commonName.trim(), ou, org, country);
        Calendar from = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        from.add(Calendar.DAY_OF_YEAR, -1);
        Calendar to = (Calendar) from.clone();
        to.add(Calendar.YEAR, validityYears);
        byte[] serial = der(0x02, new BigInteger(127, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] tbs = seq(
            serial, sigAlg, name,
            seq(time(from.getTime()), time(to.getTime())),
            name, kp.getPublic().getEncoded()
        );
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] sigBytes = sig.sign();
        byte[] bits = new byte[sigBytes.length + 1];
        System.arraycopy(sigBytes, 0, bits, 1, sigBytes.length);
        byte[] cert = seq(tbs, sigAlg, der(0x03, bits));

        X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, kp.getPrivate(), password, new java.security.cert.Certificate[] {x509});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, password);
        return out.toByteArray();
    }

    // ---- minimal DER ---------------------------------------------------------------------------

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = content.length;
        if (n < 128) {
            o.write(n);
        } else if (n < 256) {
            o.write(0x81); o.write(n);
        } else if (n < 65536) {
            o.write(0x82); o.write(n >> 8); o.write(n & 0xff);
        } else {
            o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xff); o.write(n & 0xff);
        }
        o.write(content, 0, n);
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... parts) { return der(0x30, concat(parts)); }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            tmp[n++] = (byte) (v & 0x7f);
            v >>= 7;
            while (v > 0) { tmp[n++] = (byte) ((v & 0x7f) | 0x80); v >>= 7; }
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j]);
        }
        return der(0x06, o.toByteArray());
    }

    private static byte[] rdn(String oidStr, int stringTag, String value) {
        return der(0x31, seq(oid(oidStr), der(stringTag, value.getBytes(StandardCharsets.UTF_8))));
    }

    private static byte[] name(String cn, String ou, String org, String country) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] part;
        if (country != null && country.trim().length() == 2) {
            part = rdn("2.5.4.6", 0x13, country.trim().toUpperCase(java.util.Locale.ROOT));
            o.write(part, 0, part.length);
        }
        if (org != null && !org.trim().isEmpty()) { part = rdn("2.5.4.10", 0x0c, org.trim()); o.write(part, 0, part.length); }
        if (ou != null && !ou.trim().isEmpty()) { part = rdn("2.5.4.11", 0x0c, ou.trim()); o.write(part, 0, part.length); }
        part = rdn("2.5.4.3", 0x0c, cn);
        o.write(part, 0, part.length);
        return der(0x30, o.toByteArray());
    }

    /** RFC 5280: UTCTime up to 2049, GeneralizedTime from 2050. */
    private static byte[] time(Date d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTime(d);
        boolean utc = c.get(Calendar.YEAR) < 2050;
        SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", java.util.Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(utc ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }
}
