package uk.co.fuelprices.data.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Call
import retrofit2.Callback
import uk.co.fuelprices.data.api.AuthSessionApi
import uk.co.fuelprices.data.api.LogoutRequest
import uk.co.fuelprices.data.api.RefreshRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backend issues a 24h JWT (`AppModule`'s access-token interceptor) plus a single-use refresh
 * token. On a 401, this attempts one silent `POST /api/auth/refresh` before giving up.
 *
 * Refresh tokens rotate, and re-presenting a spent one long after rotation makes the backend
 * revoke every session the user has. So the stored refresh token is only replaced by a confirmed
 * successor. The session is dropped when the server rejects the refresh token (400/401), when a
 * retry carrying a freshly refreshed token still gets a 401, or when there is no refresh token to
 * try. A network failure, timeout, 5xx, 429 or unreadable refresh response leaves the stored
 * tokens alone so a later request can retry.
 *
 * Refresh, give-up and [signOut] all hold [mutex], so only one refresh is ever in flight and a
 * sign-out can't interleave with one.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val authApi: AuthSessionApi,
) : Authenticator {
    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Only ever try to refresh a request that was actually sent with a Bearer token — an
        // unauthenticated call (/login, /register, a wrong-password 401, etc.) has nothing to
        // refresh, and must not have a stale token attached on "retry".
        val failedAuthHeader = response.request.header("Authorization") ?: return null
        val failedAccessToken = failedAuthHeader.removePrefix("Bearer ")
        val attempts = responseCount(response)
        if (attempts > MAX_RETRIES) return null

        // OkHttp calls this on a thread that holds one of its dispatcher's per-host slots. The
        // refresh runs with a synchronous execute(), which needs no dispatcher slot, so it can't
        // be starved by other requests blocked here waiting on the mutex.
        return runBlocking {
            mutex.withLock {
                // Signed out while this request was in flight — don't revive the session.
                val currentAccessToken = tokenStore.getToken() ?: return@withLock null
                // Someone else already refreshed (or signed in) while we waited on the mutex —
                // retry with what's now stored instead of refreshing a second time.
                if (currentAccessToken != failedAccessToken) {
                    return@withLock response.request.withBearer(currentAccessToken)
                }

                val refreshToken = tokenStore.getRefreshToken()
                if (refreshToken == null) {
                    // Nothing to recover with — genuinely signed out.
                    tokenStore.clear()
                    return@withLock null
                }
                // This was already a retry and the token it carried still 401'd, so the session
                // really is dead (not just the original access token) — clear it here too, or
                // isLoggedIn() keeps reporting true and FavouritesViewModel shows the raw HTTP 401
                // instead of routing to sign-in.
                if (attempts > 1) {
                    tokenStore.clearIfRefreshToken(refreshToken)
                    return@withLock null
                }

                when (val outcome = refresh(refreshToken)) {
                    is RefreshOutcome.Refreshed -> {
                        val saved = tokenStore.saveRefreshedTokens(
                            expectedRefreshToken = refreshToken,
                            token = outcome.accessToken,
                            refreshToken = outcome.refreshToken,
                        )
                        // Not saved means a sign-in replaced the session mid-refresh; retry with
                        // that session's token rather than the one just minted for the old one.
                        val token = if (saved) outcome.accessToken else tokenStore.getToken()
                        token?.let { response.request.withBearer(it) }
                    }
                    RefreshOutcome.Rejected -> {
                        // Conditional, so a sign-in that landed mid-refresh isn't wiped.
                        tokenStore.clearIfRefreshToken(refreshToken)
                        null // null => OkHttp gives up and surfaces the original 401.
                    }
                    RefreshOutcome.Unavailable -> null
                }
            }
        }
    }

    /**
     * Signs out locally, then revokes the refresh token on the server without waiting for it.
     *
     * Holding [mutex] means an in-flight refresh finishes first, so the token read here is its
     * rotated successor (the one the server still honours), and nothing can write a session back
     * after the clear. That wait can last up to the refresh call timeout, so it is non-cancellable:
     * leaving the screen that started the sign-out mustn't abandon it. The revoke is best-effort:
     * the local sign-out has already happened, and its result is ignored. [revoke] is false where
     * the server has already dropped the session, such as after deleting the account.
     */
    suspend fun signOut(revoke: Boolean = true) {
        val refreshToken = withContext(NonCancellable) {
            mutex.withLock {
                tokenStore.getRefreshToken().also { tokenStore.clear() }
            }
        }
        if (revoke && refreshToken != null) {
            authApi.logout(LogoutRequest(refreshToken)).enqueue(IgnoreResult)
        }
    }

    private fun refresh(refreshToken: String): RefreshOutcome {
        val response = try {
            authApi.refresh(RefreshRequest(refreshToken)).execute()
        } catch (e: Exception) {
            // Offline, timeout, DNS, or a body that couldn't be parsed.
            return RefreshOutcome.Unavailable
        }
        if (response.isSuccessful) {
            val body = response.body()
            val rotated = body?.refreshToken
            // Without a successor the old token is the only one held, so keep it.
            if (body == null || rotated == null) return RefreshOutcome.Unavailable
            return RefreshOutcome.Refreshed(body.accessToken, rotated)
        }
        response.errorBody()?.close()
        return when (response.code()) {
            400, 401 -> RefreshOutcome.Rejected
            else -> RefreshOutcome.Unavailable
        }
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private sealed interface RefreshOutcome {
        data class Refreshed(val accessToken: String, val refreshToken: String) : RefreshOutcome
        /** The server refused the refresh token itself. */
        data object Rejected : RefreshOutcome
        /** The refresh didn't complete; the refresh token may still be valid. */
        data object Unavailable : RefreshOutcome
    }

    private object IgnoreResult : Callback<Unit> {
        override fun onResponse(call: Call<Unit>, response: retrofit2.Response<Unit>) = Unit
        override fun onFailure(call: Call<Unit>, t: Throwable) = Unit
    }

    private companion object {
        // The original request plus one retry with a refreshed token, and one more if a different
        // session was stored by the time that retry failed.
        const val MAX_RETRIES = 2
    }
}
