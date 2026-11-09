package com.lidseeker.app.data

import com.lidseeker.app.music.*
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class ConnectionDiagnosticsTest {
    @Test fun distinguishesDnsTimeoutAndCertificateErrors() {
        assertEquals(ConnectionIssue.DNS,ConnectionDiagnostics.classify(UnknownHostException("private hostname")))
        assertEquals(ConnectionIssue.TIMEOUT,ConnectionDiagnostics.classify(SocketTimeoutException()))
        assertEquals(ConnectionIssue.TLS,ConnectionDiagnostics.classify(SSLHandshakeException("secret diagnostic string")))
    }
    @Test fun preservesAuthErrorsAndDoesNotEchoRawSecrets() {
        assertEquals(ConnectionIssue.AUTH,ConnectionDiagnostics.classify(ConnectionProblem(ConnectionIssue.AUTH)))
        val message=ConnectionDiagnostics.explain(ConnectionDiagnostics.classify(UnknownHostException("https://host/?token=secret")))
        assertFalse(message.contains("secret"))
        assertTrue(message.contains("Tailscale"))
    }
}
