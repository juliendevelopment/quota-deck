package be.julien.quotadeck

import be.julien.quotadeck.data.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ApiClientTest {
    @Test fun getsJsonWithoutLeakingCredentialsToErrors() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"plan\":\"pro\"}"))
            val result = ApiClient().get(server.url("/usage").toString(), mapOf("Authorization" to "Bearer secret"))
            assertEquals("pro", result.text("plan"))
            assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun unauthorizedIsActionableAndBodyIsNeverShown() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private token secret"))
            val error = runCatching { ApiClient().get(server.url("/usage").toString()) }.exceptionOrNull() as ProviderException
            assertTrue(error.needsLogin)
            assertFalse(error.message!!.contains("secret"))
        }
    }
    @Test fun invalidJsonIsNotAcceptedAsQuota() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>Login</html>"))
            assertTrue(runCatching { ApiClient().get(server.url("/").toString()) }.exceptionOrNull() is ProviderException)
        }
    }
    @Test fun redirectsAreNotFollowedWithAuthorization() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
            val error = runCatching { ApiClient().get(server.url("/").toString(), mapOf("Authorization" to "secret")) }.exceptionOrNull() as ProviderException
            assertEquals(302, error.httpCode)
            assertEquals(1, server.requestCount)
        }
    }
}
