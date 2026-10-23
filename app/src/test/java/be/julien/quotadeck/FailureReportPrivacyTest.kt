package be.julien.quotadeck

import be.julien.quotadeck.core.*
import be.julien.quotadeck.data.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class FailureReportPrivacyTest {
    @Test fun reportDoesNotExportExceptionMessageBodyOrOAuthQuery() {
        val error = ProviderException("fixture-private-secret private@example.com", httpCode = 400,
            safeErrorCode = "invalid_grant", endpoint = "api.example.com/token?code=fixture-private-secret&state=fixture-state")
        val report = FailureReport.format(ProviderId.CLAUDE, AuthPhase.TOKEN_EXCHANGE, error, "0.1.2", 34)
        assertTrue(report.contains("invalid_grant"))
        assertTrue(report.contains("api.example.com/token"))
        assertEquals(8, report.lines().size)
        assertFalse(report.contains("fixture-private-secret"))
        assertFalse(report.contains("fixture-state"))
        assertFalse(report.contains("private@example.com"))
    }
    @Test fun providerErrorCodeIsAllowlisted() {
        val report = FailureReport.format(ProviderId.ANTIGRAVITY, AuthPhase.TOKEN_EXCHANGE,
            ProviderException("private", safeErrorCode = "fixture-private-secret"), "0.1.2", 34)
        assertFalse(report.contains("fixture-private-secret"))
    }
    @Test fun networkErrorsHaveDistinctSafeCategories() = runTest {
        val cases = listOf(
            UnknownHostException("fixture-private-secret") to NetworkFailure.DNS,
            SSLHandshakeException("fixture-private-secret") to NetworkFailure.TLS,
            SocketTimeoutException("fixture-private-secret") to NetworkFailure.TIMEOUT,
            IOException("socket failed: EPERM fixture-private-secret") to NetworkFailure.RESTRICTED,
        )
        MockWebServer().use { server ->
            for ((failure, expected) in cases) {
                val api = ApiClient(OkHttpClient.Builder().addInterceptor { throw failure }.build())
                val error = runCatching { api.get(server.url("/token?code=fixture-private-secret").toString()) }.exceptionOrNull() as ProviderException
                assertEquals(expected, error.networkFailure)
                assertFalse(error.message!!.contains("fixture-private-secret"))
                val report = FailureReport.format(ProviderId.ANTIGRAVITY, AuthPhase.TOKEN_EXCHANGE, error, "0.1.2", 34)
                assertTrue(report.contains(expected.name))
                assertFalse(report.contains("fixture-private-secret"))
            }
        }
    }
}
