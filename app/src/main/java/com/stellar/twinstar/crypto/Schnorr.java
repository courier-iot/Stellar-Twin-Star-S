package com.stellar.twinstar.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;

/** Minimal BIP-340 Schnorr signatures on secp256k1, used to sign Nostr relay events. */
public final class Schnorr {
    private Schnorr() {}

    static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    static final BigInteger[] G = {
            new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
            new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    };
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger SEVEN = BigInteger.valueOf(7);

    static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
        if (a == null) return b;
        if (b == null) return a;
        BigInteger lam;
        if (a[0].equals(b[0])) {
            if (!a[1].equals(b[1]) || a[1].signum() == 0) return null;
            lam = THREE.multiply(a[0]).multiply(a[0]).mod(P)
                    .multiply(TWO.multiply(a[1]).modInverse(P)).mod(P);
        } else {
            lam = b[1].subtract(a[1]).mod(P)
                    .multiply(b[0].subtract(a[0]).mod(P).modInverse(P)).mod(P);
        }
        BigInteger x3 = lam.multiply(lam).subtract(a[0]).subtract(b[0]).mod(P);
        BigInteger y3 = lam.multiply(a[0].subtract(x3)).subtract(a[1]).mod(P);
        return new BigInteger[]{x3, y3};
    }

    static BigInteger[] mul(BigInteger[] pt, BigInteger k) {
        BigInteger[] r = null;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (k.testBit(i)) r = add(r, pt);
        }
        return r;
    }

    static byte[] bytes32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    static BigInteger num(byte[] b) { return new BigInteger(1, b); }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] taggedHash(String tag, byte[]... parts) {
        byte[] th = sha256(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[][] all = new byte[parts.length + 2][];
        all[0] = th; all[1] = th;
        System.arraycopy(parts, 0, all, 2, parts.length);
        return sha256(all);
    }

    public static byte[] pubkey(byte[] secret) {
        BigInteger d = num(secret);
        if (d.signum() == 0 || d.compareTo(N) >= 0) throw new IllegalArgumentException("bad key");
        return bytes32(mul(G, d)[0]);
    }

    public static byte[] sign(byte[] msg, byte[] secret, byte[] aux) {
        BigInteger d0 = num(secret);
        BigInteger[] Pt = mul(G, d0);
        BigInteger d = Pt[1].testBit(0) ? N.subtract(d0) : d0;
        byte[] t = bytes32(d.xor(num(taggedHash("BIP0340/aux", aux))));
        byte[] px = bytes32(Pt[0]);
        BigInteger k0 = num(taggedHash("BIP0340/nonce", t, px, msg)).mod(N);
        if (k0.signum() == 0) throw new IllegalStateException("bad nonce");
        BigInteger[] R = mul(G, k0);
        BigInteger k = R[1].testBit(0) ? N.subtract(k0) : k0;
        byte[] rx = bytes32(R[0]);
        BigInteger e = num(taggedHash("BIP0340/challenge", rx, px, msg)).mod(N);
        byte[] s = bytes32(k.add(e.multiply(d)).mod(N));
        byte[] sig = new byte[64];
        System.arraycopy(rx, 0, sig, 0, 32);
        System.arraycopy(s, 0, sig, 32, 32);
        return sig;
    }

    public static boolean verify(byte[] msg, byte[] pub, byte[] sig) {
        BigInteger x = num(pub);
        if (x.compareTo(P) >= 0) return false;
        BigInteger c = x.pow(3).add(SEVEN).mod(P);
        BigInteger y = c.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P);
        if (!y.modPow(TWO, P).equals(c)) return false;
        if (y.testBit(0)) y = P.subtract(y);
        BigInteger[] Pt = {x, y};
        byte[] rb = new byte[32]; byte[] sb = new byte[32];
        System.arraycopy(sig, 0, rb, 0, 32);
        System.arraycopy(sig, 32, sb, 0, 32);
        BigInteger r = num(rb), s = num(sb);
        if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) return false;
        BigInteger e = num(taggedHash("BIP0340/challenge", rb, pub, msg)).mod(N);
        BigInteger[] R = add(mul(G, s), mul(Pt, N.subtract(e)));
        return R != null && !R[1].testBit(0) && R[0].equals(r);
    }
}
