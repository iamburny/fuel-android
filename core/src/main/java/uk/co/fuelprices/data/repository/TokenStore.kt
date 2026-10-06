package uk.co.fuelprices.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "auth")

@Singleton
class TokenStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val tokenKey = stringPreferencesKey("jwt_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val emailKey = stringPreferencesKey("user_email")

    suspend fun saveToken(token: String, refreshToken: String?, email: String) {
        context.dataStore.edit {
            it[tokenKey] = token
            if (refreshToken != null) it[refreshTokenKey] = refreshToken
            it[emailKey] = email
        }
    }

    suspend fun getToken(): String? =
        context.dataStore.data.map { it[tokenKey] }.first()

    /** Long-lived opaque token used by [uk.co.fuelprices.data.repository.TokenAuthenticator] to
     *  silently mint a new access token via `POST /api/auth/refresh` once it expires. */
    suspend fun getRefreshToken(): String? =
        context.dataStore.data.map { it[refreshTokenKey] }.first()

    suspend fun getEmail(): String? =
        context.dataStore.data.map { it[emailKey] }.first()

    /**
     * Stores a refreshed token pair, but only if [expectedRefreshToken] is still the stored refresh
     * token — a sign-in that lands while the refresh call is in flight wins. The check and write
     * are one DataStore transaction. Returns whether the pair was stored.
     */
    suspend fun saveRefreshedTokens(expectedRefreshToken: String, token: String, refreshToken: String): Boolean {
        var saved = false
        context.dataStore.edit {
            if (it[refreshTokenKey] == expectedRefreshToken) {
                it[tokenKey] = token
                it[refreshTokenKey] = refreshToken
                saved = true
            }
        }
        return saved
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    /** Clears the session only if [expectedRefreshToken] is still the stored refresh token, so a
     *  newer sign-in isn't wiped. Atomic like [saveRefreshedTokens]. */
    suspend fun clearIfRefreshToken(expectedRefreshToken: String) {
        context.dataStore.edit {
            if (it[refreshTokenKey] == expectedRefreshToken) it.clear()
        }
    }

    suspend fun isLoggedIn(): Boolean = getToken() != null
}
