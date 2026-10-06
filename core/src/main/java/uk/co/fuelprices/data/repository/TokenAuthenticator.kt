package uk.co.fuelprices.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * successor. The session is dropped when the server rejects the refresh token (400/401), when it
 * accepts it but the successor can't be used (an unreadable 2xx, or one that can't be saved; the
 * old token is spent either way), when a retry carrying a freshly refreshed token still gets a
 * 401, or when there is no refresh token to try. A network failure, timeout, 5xx or 429 leaves
 * the stored tokens alone so a later request can retry.
 *
 * Refreshes hold [mutex], so only one is ever in flight. Sign-out doesn't wait for it: it clears
 * the store at once, and the store's conditional writes stop an in-flight refresh from writing the
 * session back (see [signOut]).
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val authApi: AuthSessionApi,
) : Authenticator {
    private val mutex = Mutex()
    private val revokeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The refresh token whose last refresh call got no answer (timeout, dropped connection), so
    // the server may still be processing it. Only read or written under [mutex].
    private var unansweredRefreshToken: String? = null

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
                val stored = tokenStore.getTokens()
                // Signed out while this request was in flight — don't revive the session, and
                // don't present a refresh token that sign-out is revoking.
                val currentAccessToken = stored.token ?: return@withLock null
                // Someone else already refreshed (or signed in) while we waited on the mutex —
                // retry with what's now stored instead of refreshing a second time.
                if (currentAccessToken != failedAccessToken) {
                    return@withLock response.request.withBearer(currentAccessToken)
                }

                val refreshToken = stored.refreshToken
                if (refreshToken == null) {
                    // Nothing to recover with — genuinely signed out.
                    tokenStore.clearIfUnchanged(stored)
                    return@withLock null
                }
                // This was already a retry and the token it carried still 401'd, so the session
                // really is dead (not just the original access token) — clear it here too, or
                // isLoggedIn() keeps reporting true and FavouritesViewModel shows the raw HTTP 401
                // instead of routing to sign-in.
                if (attempts > 1) {
                    tokenStore.clearIfUnchanged(stored)
                    return@withLock null
                }

                when (val outcome = refresh(refreshToken)) {
                    is RefreshOutcome.Refreshed -> {
                        val saved = try {
                            tokenStore.saveRefreshedTokens(
                                expectedRefreshToken = refreshToken,
                                token = outcome.accessToken,
                                refreshToken = outcome.refreshToken,
                            )
                        } catch (e: Exception) {
                            abandonSession(stored, outcome.refreshToken)
                            return@withLock null
                        }
                        if (saved) {
                            response.request.withBearer(outcome.accessToken)
                        } else {
                            // Signed out (or a different sign-in stored) mid-refresh. The pair
                            // just minted belongs to a session nobody holds any more, so revoke it
                            // rather than leave it live, and let the original 401 stand.
                            revoke(outcome.refreshToken)
                            null
                        }
                    }
                    RefreshOutcome.Rejected -> {
                        // Conditional, so a sign-in that landed mid-refresh isn't wiped.
                        tokenStore.clearIfUnchanged(stored)
                        null // null => OkHttp gives up and surfaces the original 401.
                    }
                    is RefreshOutcome.Spent -> {
                        abandonSession(stored, outcome.issuedRefreshToken)
                        null
                    }
                    RefreshOutcome.Unavailable -> null
                }
            }
        }
    }

    /**
     * Signs out locally at once, then revokes the refresh token on the server in the background.
     *
     * The clear doesn't wait for an in-flight refresh. That refresh's conditional save then fails,
     * so it revokes the successor it was issued instead of storing it; any refresh that hasn't
     * read the store yet finds it empty and doesn't refresh at all.
     *
     * The revoke of the cleared token itself waits on [mutex], so it reaches the server only after
     * an in-flight refresh presenting that same token has finished. Revoking it first would make
     * the refresh look like reuse of a revoked token, which the backend answers by revoking every
     * session the user has. For the same reason it is skipped when the last refresh of that token
     * got no answer, since that request may still reach the server after the revoke; the token is
     * then left to expire. Revokes are best-effort and their results ignored. [revoke] is false
     * where the server has already dropped the session, such as after deleting the account.
     *
     * Non-cancellable so that leaving the screen mid-sign-out can't clear the store without
     * scheduling the revoke.
     */
    suspend fun signOut(revoke: Boolean = true) = withContext(NonCancellable) {
        val refreshToken = tokenStore.takeRefreshTokenAndClear()
        if (revoke && refreshToken != null) {
            revokeScope.launch {
                mutex.withLock {
                    if (refreshToken != unansweredRefreshToken) revoke(refreshToken)
                }
            }
        }
    }

    /**
     * The server spent the stored refresh token but its successor can't be kept. Holding on to the
     * spent one would revoke every session the user has the next time it's presented, so this
     * device's session is dropped instead (unless a sign-in has replaced it), and the successor,
     * if one was readable, is revoked so it isn't left live.
     */
    private suspend fun abandonSession(stored: StoredTokens, issuedRefreshToken: String?) {
        try {
            tokenStore.clearIfUnchanged(stored)
        } catch (e: Exception) {
            // Storage is failing; the revoke below still goes out.
        }
        issuedRefreshToken?.let { revoke(it) }
    }

    private fun revoke(refreshToken: String) {
        try {
            authApi.logout(LogoutRequest(refreshToken)).enqueue(IgnoreResult)
        } catch (e: Exception) {
            // Best-effort: a revoke that can't be sent is dropped.
        }
    }

    private fun refresh(refreshToken: String): RefreshOutcome {
        val response = try {
            authApi.refresh(RefreshRequest(refreshToken)).execute()
        } catch (e: Exception) {
            // Offline, timeout, DNS: no status came back.
            unansweredRefreshToken = refreshToken
            return RefreshOutcome.Unavailable
        }
        unansweredRefreshToken = null
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return when (response.code()) {
                400, 401 -> RefreshOutcome.Rejected
                else -> RefreshOutcome.Unavailable
            }
        }
        // A 2xx: the server has spent the token sent, whatever happens reading the body.
        val body = try {
            response.body()?.use { it.string() }
        } catch (e: Exception) {
            null
        }
        val fields = body?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val accessToken = fields?.stringField("access_token")
        val rotated = fields?.stringField("refresh_token")
        return if (accessToken != null && rotated != null) {
            RefreshOutcome.Refreshed(accessToken, rotated)
        } else {
            RefreshOutcome.Spent(rotated)
        }
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

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
        /** The server accepted the refresh token, but the response can't be used. */
        data class Spent(val issuedRefreshToken: String?) : RefreshOutcome
        /** No answer from the server, or a 5xx/429; the refresh token may still be valid. */
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
