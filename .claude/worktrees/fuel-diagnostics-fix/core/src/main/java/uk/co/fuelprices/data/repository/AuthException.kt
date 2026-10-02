package uk.co.fuelprices.data.repository

import retrofit2.HttpException

/**
 * A sign-in/registration failure translated to a user-facing message, so the app layer doesn't need
 * Retrofit on its classpath to interpret HTTP status codes. [message] is safe to show directly.
 */
class AuthException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { INVALID_CREDENTIALS, EMAIL_TAKEN, GOOGLE_SIGNIN_FAILED, OTHER }

    companion object {
        fun from(e: HttpException): AuthException = when (e.code()) {
            401 -> AuthException(Reason.INVALID_CREDENTIALS, "Invalid email or password")
            409 -> AuthException(Reason.EMAIL_TAKEN, "That email is already registered")
            else -> AuthException(Reason.OTHER, "Something went wrong. Please try again.")
        }
    }
}
