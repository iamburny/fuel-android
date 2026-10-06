package uk.co.fuelprices.data.repository

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import uk.co.fuelprices.data.api.AuthSessionApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var authenticator: TokenAuthenticator

    // In-memory stand-in for the DataStore-backed TokenStore.
    @Volatile private var storedToken: String? = null
    @Volatile private var storedRefreshToken: String? = null
    private val lock = Any()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        val authApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthSessionApi::class.java)

        val tokenStore = mockk<TokenStore>()
        coEvery { tokenStore.getTokens() } answers { synchronized(lock) { StoredTokens(storedToken, storedRefreshToken) } }
        coEvery { tokenStore.clearIfUnchanged(any()) } answers {
            synchronized(lock) {
                if (firstArg<StoredTokens>() == StoredTokens(storedToken, storedRefreshToken)) {
                    storedToken = null
                    storedRefreshToken = null
                }
            }
        }
        coEvery { tokenStore.takeRefreshTokenAndClear() } answers {
            synchronized(lock) { storedRefreshToken.also { storedToken = null; storedRefreshToken = null } }
        }
        coEvery { tokenStore.saveRefreshedTokens(any(), any(), any()) } answers {
            synchronized(lock) {
                if (storedRefreshToken == firstArg<String>()) {
                    storedToken = secondArg()
                    storedRefreshToken = thirdArg()
                    true
                } else {
                    false
                }
            }
        }

        storedToken = "old-access"
        storedRefreshToken = "old-refresh"
        authenticator = TokenAuthenticator(tokenStore, authApi)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun unauthorized(bearer: String? = "old-access", prior: Response? = null): Response {
        val request = Request.Builder()
            .url(server.url("/api/favourites/"))
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(prior)
            .build()
    }

    private fun refreshed(access: String = "new-access", refresh: String = "new-refresh") =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"access_token":"$access","refresh_token":"$refresh","token_type":"bearer","role":"user"}""")

    @Test
    fun `a successful refresh stores the rotated pair and retries with the new access token`() {
        server.enqueue(refreshed())

        val retry = authenticator.authenticate(null, unauthorized())

        assertEquals("Bearer new-access", retry?.header("Authorization"))
        assertEquals("new-access", storedToken)
        assertEquals("new-refresh", storedRefreshToken)
        assertEquals("""{"refresh_token":"old-refresh"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `a 5xx from refresh keeps the stored tokens`() {
        server.enqueue(MockResponse().setResponseCode(502))

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertEquals("old-access", storedToken)
        assertEquals("old-refresh", storedRefreshToken)
    }

    @Test
    fun `a 429 from refresh keeps the stored tokens`() {
        server.enqueue(MockResponse().setResponseCode(429))

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertEquals("old-refresh", storedRefreshToken)
    }

    @Test
    fun `a network failure during refresh keeps the stored tokens`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertEquals("old-refresh", storedRefreshToken)
    }

    @Test
    fun `a malformed refresh response keeps the stored tokens`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("<html>oops</html>"))

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertEquals("old-refresh", storedRefreshToken)
    }

    @Test
    fun `a refresh response without a rotated refresh token keeps the stored tokens`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"access_token":"new-access"}"""))

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertEquals("old-access", storedToken)
        assertEquals("old-refresh", storedRefreshToken)
    }

    @Test
    fun `a 401 from refresh clears the session`() {
        server.enqueue(MockResponse().setResponseCode(401))

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertNull(storedToken)
        assertNull(storedRefreshToken)
    }

    @Test
    fun `a 400 from refresh clears the session`() {
        server.enqueue(MockResponse().setResponseCode(400))

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertNull(storedRefreshToken)
    }

    @Test
    fun `a refused refresh does not wipe a session stored while it was in flight`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // A sign-in completes while the refresh call is on the wire.
                storedToken = "login-access"
                storedRefreshToken = "login-refresh"
                return MockResponse().setResponseCode(401)
            }
        }

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertEquals("login-access", storedToken)
        assertEquals("login-refresh", storedRefreshToken)
    }

    @Test
    fun `a refresh that loses to a sign-in mid-flight revokes its new token and does not retry`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/auth/refresh") {
                    storedToken = "login-access"
                    storedRefreshToken = "login-refresh"
                    return refreshed()
                }
                return MockResponse()
            }
        }

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertEquals("login-access", storedToken)
        assertEquals("login-refresh", storedRefreshToken)
        server.takeRequest(5, TimeUnit.SECONDS) // the refresh
        val logout = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/api/auth/logout", logout?.path)
        assertEquals("""{"refresh_token":"new-refresh"}""", logout?.body?.readUtf8())
    }

    @Test
    fun `a token refreshed by another request is reused without refreshing again`() {
        storedToken = "already-refreshed"

        val retry = authenticator.authenticate(null, unauthorized())

        assertEquals("Bearer already-refreshed", retry?.header("Authorization"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a request that failed after sign-out is not retried and does not refresh`() {
        storedToken = null
        storedRefreshToken = null

        assertNull(authenticator.authenticate(null, unauthorized()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unauthenticated request is never refreshed`() {
        assertNull(authenticator.authenticate(null, unauthorized(bearer = null)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a retried request that still 401s clears the session`() {
        val retried = unauthorized(prior = unauthorized(bearer = "older-access"))

        assertNull(authenticator.authenticate(null, retried))
        assertNull(storedRefreshToken)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sign-out does not wait for an in-flight refresh, which then revokes its new token`() {
        val refreshReceived = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/refresh" -> {
                    refreshReceived.countDown()
                    releaseRefresh.await(5, TimeUnit.SECONDS)
                    refreshed()
                }
                else -> MockResponse()
            }
        }

        var retry: Request? = null
        var authenticated = false
        val refresher = thread {
            retry = authenticator.authenticate(null, unauthorized())
            authenticated = true
        }
        assertTrue(refreshReceived.await(5, TimeUnit.SECONDS))

        // Returns while the refresh is still blocked on the server.
        runBlocking { authenticator.signOut() }
        assertNull(storedToken)
        assertNull(storedRefreshToken)
        // The old token's revoke is held back until the refresh presenting it has finished.
        assertEquals("/api/auth/refresh", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))

        releaseRefresh.countDown()
        refresher.join(5_000)

        assertTrue(authenticated)
        assertNull(retry)
        assertNull(storedToken)
        assertNull(storedRefreshToken)
        val revoked = (1..2).map { server.takeRequest(5, TimeUnit.SECONDS) }
        assertTrue(revoked.all { it?.path == "/api/auth/logout" })
        assertEquals(
            setOf("""{"refresh_token":"old-refresh"}""", """{"refresh_token":"new-refresh"}"""),
            revoked.map { it?.body?.readUtf8() }.toSet(),
        )
    }

    @Test
    fun `a 401 handled after sign-out does not present the token being revoked`() {
        runBlocking { authenticator.signOut() }

        assertNull(authenticator.authenticate(null, unauthorized()))

        val only = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/api/auth/logout", only?.path)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `sign-out clears locally even when the revoke fails`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        runBlocking { authenticator.signOut() }

        assertNull(storedToken)
        assertNull(storedRefreshToken)
    }

    @Test
    fun `sign-out without revoke makes no request`() {
        runBlocking { authenticator.signOut(revoke = false) }

        assertNull(storedRefreshToken)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }
}
