package be.julien.quotadeck

import be.julien.quotadeck.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class OAuthFailureDiagnosticRegressionTest {
    @Test fun safeOAuthCodeIsKeptButSensitiveDescriptionIsNotExposed() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"private authorization code fixture-secret and email private@example.com"}"""))
            val error = runCatching { ApiClient().post(server.url("/token").toString(), buildJsonObject {}) }.exceptionOrNull() as ProviderException
            assertTrue(error.message!!.contains("invalid_grant"))
            assertFalse(error.message!!.contains("fixture-secret"))
            assertFalse(error.message!!.contains("private@example.com"))
        }
    }
}
