package uk.co.fuelprices.di

import android.content.Context
import androidx.room.Room
import com.google.firebase.analytics.FirebaseAnalytics
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.getunleash.android.DefaultUnleash
import io.getunleash.android.Unleash
import io.getunleash.android.UnleashConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import uk.co.fuelprices.core.BuildConfig
import uk.co.fuelprices.data.api.FuelPricesApi
import uk.co.fuelprices.data.db.FuelDatabase
import uk.co.fuelprices.data.repository.TokenStore
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(tokenStore: TokenStore): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // Inject JWT token for authenticated endpoints
                val token = kotlinx.coroutines.runBlocking { tokenStore.getToken() }
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            // The backend issues a 24h JWT with no refresh token/endpoint at all — once it
            // expires (or the account's deleted, etc.) every authenticated call 401s forever,
            // since nothing else ever clears the stored token. Drop it here so isLoggedIn() flips
            // to false right away and screens like Favourites show their normal signed-out state
            // instead of a raw "HTTP 401" once the exception reaches them.
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 401) {
                    kotlinx.coroutines.runBlocking { tokenStore.clear() }
                }
                response
            }
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG)
                        HttpLoggingInterceptor.Level.BODY
                    else
                        HttpLoggingInterceptor.Level.NONE
                }
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): FuelPricesApi =
        retrofit.create(FuelPricesApi::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FuelDatabase =
        Room.databaseBuilder(context, FuelDatabase::class.java, "fuel_prices.db")
            .fallbackToDestructiveMigration()
            .build()

    // Feature flags — self-hosted Unleash, single instance app-wide (its own docs warn against
    // multiple instances due to on-disk cache contention). An empty UNLEASH_CLIENT_KEY (no
    // local.properties entry yet) degrades the same way an unset MAPS_API_KEY does — flag
    // evaluations just fall back to their default, no crash.
    //
    // delayedInitialization(false): the SDK defaults this to true (caller starts polling manually
    // via unleash.start() later, e.g. once some app-specific context is known). Nothing here ever
    // called start(), so with the default the client never polled at all — isEnabled() was just
    // silently returning its local `default` argument for every flag, forever. Confirmed by
    // disassembling DefaultUnleash's constructor: it only calls start$default(...) when
    // getDelayedInitialization() is false.
    @Provides
    @Singleton
    fun provideUnleashClient(@ApplicationContext context: Context): Unleash =
        DefaultUnleash(
            androidContext = context,
            unleashConfig = UnleashConfig.newBuilder("fuel-android")
                .proxyUrl(BuildConfig.UNLEASH_URL.trimEnd('/') + "/api/frontend")
                .clientKey(BuildConfig.UNLEASH_CLIENT_KEY)
                .delayedInitialization(false)
                .build(),
        )

    @Provides
    @Singleton
    fun provideFirebaseAnalytics(@ApplicationContext context: Context): FirebaseAnalytics =
        FirebaseAnalytics.getInstance(context)
}
