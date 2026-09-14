package uk.co.fuelprices.data.repository

import dagger.Lazy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import uk.co.fuelprices.data.api.FuelPricesApi
import uk.co.fuelprices.data.api.RefreshRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backend issues a 24h JWT (`AppModule`'s access-token interceptor) plus a long-lived opaque
 * refresh token. On a 401, this attempts exactly one silent `POST /api/auth/refresh` before
 * giving up — replaces the old "401 -> clear the token immediately" stopgap, which had no way to
 * recover and forced a full re-login roughly weekly.
 *
 * [api] is `dagger.Lazy` to break the dependency cycle: `OkHttpClient` needs this
 * `Authenticator`, which needs [FuelPricesApi], which needs the `Retrofit` built from that same
 * `OkHttpClient`.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val api: Lazy<FuelPricesApi>,
) : Authenticator {
    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Only ever try to refresh a request that was actually sent with a Bearer token — an
        // unauthenticated call (/login, /register, a wrong-password 401, etc.) has nothing to
        // refresh, and must not have a stale token attached on "retry".
        val failedAuthHeader = response.request.header("Authorization") ?: return null
        // Never refresh-and-retry the refresh call itself — avoids an infinite loop on a
        // genuinely-invalid refresh token.
        if (response.request.url.encodedPath.endsWith("/api/auth/refresh")) return null
        // Already retried this exact request once — give up rather than loop. The retry carried
        // a freshly-refreshed token and still 401'd, so the session really is dead (not just the
        // original access token) — clear it here too, or isLoggedIn() keeps reporting true and
        // FavouritesViewModel shows the raw HTTP 401 instead of routing to sign-in.
        if (responseCount(response) >= 2) {
            runBlocking { tokenStore.clear() }
            return null
        }

        return runBlocking {
            mutex.withLock {
                val failedAccessToken = failedAuthHeader.removePrefix("Bearer ")
                val currentAccessToken = tokenStore.getToken()
                // Someone else already refreshed while we waited on the mutex — retry with
                // what's now stored instead of refreshing a second time.
                if (currentAccessToken != null && currentAccessToken != failedAccessToken) {
                    return@withLock response.request.newBuilder()
                        .header("Authorization", "Bearer $currentAccessToken")
                        .build()
                }

                val refreshToken = tokenStore.getRefreshToken()
                if (refreshToken == null) {
                    // Nothing to recover with — genuinely signed out.
                    tokenStore.clear()
                    return@withLock null
                }
                try {
                    val refreshed = api.get().refresh(RefreshRequest(refreshToken))
                    tokenStore.saveToken(refreshed.accessToken, refreshed.refreshToken, tokenStore.getEmail() ?: "")
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${refreshed.accessToken}")
                        .build()
                } catch (e: HttpException) {
                    // The server gave a definitive HTTP rejection of the refresh token itself
                    // (401/expired/revoked) — genuinely signed out.
                    tokenStore.clear()
                    null // null => OkHttp gives up and surfaces the original 401.
                } catch (e: Exception) {
                    // A transient failure trying to reach the refresh endpoint (offline, timeout,
                    // DNS, etc.) — not a rejection of the refresh token itself. Leave the tokens
                    // intact so a later request can retry once connectivity returns, instead of
                    // force-signing-out on a network blip.
                    null
                }
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
