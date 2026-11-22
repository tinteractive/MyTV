// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import java.math.BigInteger
import java.security.MessageDigest

/**
 * SPAKE2 over edwards25519, exactly as BoringSSL's spake25519.c (which adb's Wireless debugging pairing uses): the
 * pairing code and the TLS session secret become a shared key without either side sending the code.
 *
 * Written for MyTV from BoringSSL's description with plain BigInteger arithmetic (a pairing needs only a handful of
 * point multiplications). The points M and N come from BoringSSL's seeds and were checked against its tables;
 * Spake2Test holds vectors from an independent prototype.
 */
internal class Spake2(private val alice: Boolean, private val myName: ByteArray, private val theirName: ByteArray) {
    private var privateKey: BigInteger = BigInteger.ZERO
    private var passwordScalar: BigInteger = BigInteger.ZERO
    private var passwordHash = ByteArray(0)
    private var myMsg = ByteArray(0)

    /** Our 32-byte message. [random] is 64 random bytes (a parameter so tests can repeat a run). */
    fun message(password: ByteArray, random: ByteArray): ByteArray {
        require(random.size == 64)
        // Multiplied by the cofactor (eight), so that it is cleared when operating on the peer's point.
        privateKey = le(random).mod(L).shiftLeft(3)
        passwordHash = sha512(password)
        // BoringSSL's "password scalar hack": multiples of L added so that the low three bits are zero.
        var s = le(passwordHash).mod(L)
        if (s.testBit(0)) s += L
        if (s.testBit(1)) s += L.shiftLeft(1)
        if (s.testBit(2)) s += L.shiftLeft(2)
        passwordScalar = s
        myMsg = encode(add(mul(BASE, privateKey), mul(if (alice) M else N, passwordScalar)))
        return myMsg.copyOf()
    }

    /** The 64-byte shared key from the peer's message; throws when the message is not a point on the curve. */
    fun key(theirMsg: ByteArray): ByteArray {
        require(theirMsg.size == 32 && myMsg.isNotEmpty())
        val qStar = decode(theirMsg) ?: throw IllegalArgumentException("not a point on the curve")
        val q = add(qStar, neg(mul(if (alice) N else M, passwordScalar)))
        val dh = encode(mul(q, privateKey))
        val md = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            md.update(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(b.size.toLong()).array())
            md.update(b)
        }
        if (alice) { put(myName); put(theirName); put(myMsg); put(theirMsg) }
        else { put(theirName); put(myName); put(theirMsg); put(myMsg) }
        put(dh); put(passwordHash)
        return md.digest()
    }

    /** A point in extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    companion object {
        private val TWO: BigInteger = BigInteger.valueOf(2)
        private val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
        private val L: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
        private val D: BigInteger = (BigInteger.valueOf(-121665) * inv(BigInteger.valueOf(121666))).mod(P)
        private val D2: BigInteger = (D * TWO).mod(P)
        private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)
        private val ZERO = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        private val BASE: Pt = run {
            val y = (BigInteger.valueOf(4) * inv(BigInteger.valueOf(5))).mod(P)
            point(recoverX(y, false)!!, y)
        }
        /** BoringSSL's M and N: the first SHA-256 chain value of the seed that decodes as a point (ed25519.py rules). */
        private val M: Pt = seedPoint("edwards25519 point generation seed (M)")
        private val N: Pt = seedPoint("edwards25519 point generation seed (N)")

        private fun inv(a: BigInteger) = a.modPow(P - TWO, P)
        private fun point(x: BigInteger, y: BigInteger) = Pt(x, y, BigInteger.ONE, (x * y).mod(P))

        private fun le(b: ByteArray) = BigInteger(1, b.reversedArray())

        private fun sha512(b: ByteArray) = MessageDigest.getInstance("SHA-512").digest(b)

        /** x for [y] with the given sign (odd = true), or null when y is not on the curve. */
        private fun recoverX(y: BigInteger, odd: Boolean): BigInteger? {
            val u = (y * y - BigInteger.ONE).mod(P)
            val v = (D * y * y + BigInteger.ONE).mod(P)
            var x = (u * v.modPow(BigInteger.valueOf(3), P) * (u * v.modPow(BigInteger.valueOf(7), P)).modPow((P - BigInteger.valueOf(5)).shiftRight(3), P)).mod(P)
            val vxx = (v * x * x).mod(P)
            if (vxx != u) {
                if (vxx != (-u).mod(P)) return null
                x = (x * SQRT_M1).mod(P)
            }
            if (x.testBit(0) != odd) x = (-x).mod(P)
            return x
        }

        private fun seedPoint(seed: String): Pt {
            var v = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            while (true) {
                decode(v)?.let { return it }
                v = MessageDigest.getInstance("SHA-256").digest(v)
            }
        }

        private fun decode(s: ByteArray): Pt? {
            val n = le(s)
            val y = n.clearBit(255).mod(P)
            val x = recoverX(y, n.testBit(255)) ?: return null
            return point(x, y)
        }

        private fun encode(p: Pt): ByteArray {
            val zi = inv(p.z)
            val x = (p.x * zi).mod(P); val y = (p.y * zi).mod(P)
            val n = if (x.testBit(0)) y.setBit(255) else y
            val out = ByteArray(32)
            val be = n.toByteArray()
            for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
            return out
        }

        /** Unified addition for a = -1 twisted Edwards (complete on edwards25519). */
        private fun add(a: Pt, b: Pt): Pt {
            val pa = ((a.y - a.x) * (b.y - b.x)).mod(P)
            val pb = ((a.y + a.x) * (b.y + b.x)).mod(P)
            val c = (a.t * D2 * b.t).mod(P)
            val d = (a.z * TWO * b.z).mod(P)
            val e = pb - pa; val f = d - c; val g = d + c; val h = pb + pa
            return Pt((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
        }

        private fun neg(a: Pt) = Pt((-a.x).mod(P), a.y, a.z, (-a.t).mod(P))

        private fun mul(p: Pt, n: BigInteger): Pt {
            var r = ZERO
            for (i in n.bitLength() - 1 downTo 0) {
                r = add(r, r)
                if (n.testBit(i)) r = add(r, p)
            }
            return r
        }

        /** For tests: the encoded points M and N. */
        internal fun encodedM() = encode(M)
        internal fun encodedN() = encode(N)
    }
}
