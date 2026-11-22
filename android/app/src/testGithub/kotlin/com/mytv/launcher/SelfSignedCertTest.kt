// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.SelfSignedCert
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator

class SelfSignedCertTest {
    @Test fun certificateCarriesTheKeyAndVerifies() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = SelfSignedCert.create(kp.public, kp.private)
        cert.verify(kp.public)
        cert.checkValidity()
        assertEquals(kp.public, cert.publicKey)
        assertEquals("CN=MyTV", cert.subjectX500Principal.name)
        assertEquals(3, cert.version)
    }
}
