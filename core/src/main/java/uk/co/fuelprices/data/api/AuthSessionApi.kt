package uk.co.fuelprices.data.api

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The refresh-token endpoints. Both authenticate by the refresh token in the body, so this runs
 * on its own client with no Bearer interceptor and no `TokenAuthenticator` (see `AppModule`).
 *
 * The calls return [Call] rather than being `suspend`: `TokenAuthenticator` runs on an OkHttp
 * dispatcher thread and executes [refresh] synchronously on it, and sign-out fires [logout]
 * without waiting for the result.
 */
interface AuthSessionApi {

    /** Rotates a single-use refresh token: the one sent is spent and the response carries its
     *  successor. 400/401 means the refresh token is no longer valid. */
    @POST("api/auth/refresh")
    fun refresh(@Body body: RefreshRequest): Call<TokenResponse>

    /** Revokes one refresh token. Always 200, whether or not the token was still valid. */
    @POST("api/auth/logout")
    fun logout(@Body body: LogoutRequest): Call<Unit>
}
