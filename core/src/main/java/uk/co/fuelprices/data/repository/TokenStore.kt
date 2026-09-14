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

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    suspend fun isLoggedIn(): Boolean = getToken() != null
}
