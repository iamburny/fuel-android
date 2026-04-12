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
    private val emailKey = stringPreferencesKey("user_email")

    suspend fun saveToken(token: String, email: String) {
        context.dataStore.edit {
            it[tokenKey] = token
            it[emailKey] = email
        }
    }

    suspend fun getToken(): String? =
        context.dataStore.data.map { it[tokenKey] }.first()

    suspend fun getEmail(): String? =
        context.dataStore.data.map { it[emailKey] }.first()

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    suspend fun isLoggedIn(): Boolean = getToken() != null
}
