package uk.co.fuelprices.data.api

import retrofit2.http.*

interface FuelPricesApi {

    // ── Stations ─────────────────────────────────────────

    @GET("api/stations/nearby")
    suspend fun getNearbyStations(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radius") radiusMiles: Double = 10.0,
        @Query("fuel_type") fuelType: String? = null,
        @Query("limit") limit: Int = 20,
    ): StationListResponse

    @GET("api/stations/bounds")
    suspend fun getStationsInBounds(
        @Query("minLat") minLat: Double,
        @Query("maxLat") maxLat: Double,
        @Query("minLng") minLng: Double,
        @Query("maxLng") maxLng: Double,
        @Query("limit") limit: Int = 100,
    ): StationListResponse

    @GET("api/stations/{id}")
    suspend fun getStation(@Path("id") stationId: Int): StationDto

    /**
     * Text search over station name/postcode/brand/town. Results come back ranked by relevance
     * (exact name or postcode match → name prefix → everything else).
     *
     * [lat]/[lng] are optional: when both are supplied, distance becomes the final tie-break
     * *within* a relevance tier and each station carries a `distance_miles`, exactly as
     * `/api/stations/nearby` does. They must be genuinely absent from the request when there's no
     * GPS fix — never sent as 0, which the backend would read as a real position in the Gulf of
     * Guinea. Retrofit drops null `@Query` values from the URL entirely
     * (`ParameterHandler.Query.apply` returns early on null), so a boxed nullable `Double?` is the
     * mechanism here; a non-null `Double` with a default would be serialised as `0.0`.
     */
    @GET("api/stations/search/")
    suspend fun searchStations(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null,
    ): StationListResponse

    // ── Prices ───────────────────────────────────────────

    @GET("api/prices/averages")
    suspend fun getNationalAverages(): AveragesResponse

    @GET("api/prices/heatmap")
    suspend fun getHeatmap(
        @Query("fuel_type") fuelType: String = "E10",
    ): HeatmapResponse

    @GET("api/prices/history/{stationId}")
    suspend fun getPriceHistory(
        @Path("stationId") stationId: Int,
        @Query("fuel_type") fuelType: String = "E10",
        @Query("days") days: Int = 30,
    ): PriceHistoryResponse

    @GET("api/prices/trends")
    suspend fun getNationalTrends(
        @Query("fuel_type") fuelType: String = "E10",
        @Query("days") days: Int = 30,
    ): TrendsResponse

    // ── Auth ─────────────────────────────────────────────

    @FormUrlEncoded
    @POST("api/auth/login")
    suspend fun login(
        @Field("username") email: String,
        @Field("password") password: String,
    ): TokenResponse

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): UserResponse

    @POST("api/auth/google")
    suspend fun googleLogin(@Body body: GoogleLoginRequest): TokenResponse

    // Authenticates via the refresh token in the body, not a Bearer header — called directly by
    // TokenAuthenticator, never through the normal Bearer-attaching interceptor path.
    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenResponse

    @POST("api/auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest)

    @POST("api/auth/fcm-token")
    suspend fun updateFcmToken(@Query("fcm_token") token: String)

    @GET("api/auth/preferences")
    suspend fun getPreferences(): PreferencesDto

    @PUT("api/auth/preferences")
    suspend fun updatePreferences(@Body body: PreferencesDto): PreferencesDto

    // ── Favourites ───────────────────────────────────────

    @GET("api/favourites/")
    suspend fun getFavourites(): List<FavouriteDto>

    @POST("api/favourites/")
    suspend fun addFavourite(@Body body: FavouriteCreateRequest): FavouriteDto

    @DELETE("api/favourites/{id}")
    suspend fun removeFavourite(@Path("id") favouriteId: Int)

    // ── Area alerts ──────────────────────────────────────

    @GET("api/alerts/")
    suspend fun getAlerts(): List<AlertSubscriptionDto>

    @POST("api/alerts/")
    suspend fun addAlert(@Body body: AlertCreateRequest): AlertSubscriptionDto

    @DELETE("api/alerts/{id}")
    suspend fun removeAlert(@Path("id") id: Int)

    // ── Discrepancy ──────────────────────────────────────

    @POST("api/discrepancy/")
    suspend fun reportDiscrepancy(@Body body: DiscrepancyReportRequest)

    @GET("api/discrepancy/report-url")
    suspend fun getDiscrepancyReportUrl(): Map<String, String>
}
