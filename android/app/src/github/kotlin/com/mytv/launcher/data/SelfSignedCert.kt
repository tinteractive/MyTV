// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A minimal self-signed X.509 certificate (SHA-256 with RSA, CN=MyTV) for MyTV's Wireless debugging key: adbd only looks
 * at the public key in it. Plain DER, no extra library; checked by SelfSignedCertTest.
 */
internal object SelfSignedCert {
    private val SHA256_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun create(pub: PublicKey, key: PrivateKey, name: String = "MyTV"): X509Certificate {
        val alg = seq(tlv(0x06, SHA256_RSA), byteArrayOf(0x05, 0x00))
        val dn = seq(tlv(0x31, seq(tlv(0x06, COMMON_NAME), tlv(0x0c, name.toByteArray()))))
        val validity = seq(tlv(0x17, "250101000000Z".toByteArray()), tlv(0x18, "20991231235959Z".toByteArray()))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))),            // version 3
            tlv(0x02, BigInteger.ONE.toByteArray()),         // serial
            alg, dn, validity, dn,
            pub.encoded,                                     // SubjectPublicKeyInfo, already DER
        )
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key); update(tbs); sign() }
        val der = seq(tbs, alg, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(value)
        return out.toByteArray()
    }
}
