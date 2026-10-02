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

    // Permanently deletes the signed-in account and everything attached to it.
    @DELETE("api/auth/me")
    suspend fun deleteAccount()

    // The emailed link opens the website, which completes verification; the app never handles it.
    @POST("api/auth/verify-email/request")
    suspend fun requestEmailVerification(): VerifyEmailResponse

    @POST("api/auth/accept-terms")
    suspend fun acceptTerms(@Body body: AcceptTermsRequest)

    // ── Favourites ───────────────────────────────────────

    @GET("api/favourites/")
    suspend fun getFavourites(): List<FavouriteDto>

    @POST("api/favourites/")
    suspend fun addFavourite(@Body body: FavouriteCreateRequest): FavouriteDto

    @PATCH("api/favourites/{id}")
    suspend fun updateFavourite(@Path("id") favouriteId: Int, @Body body: FavouriteUpdateRequest): FavouriteDto

    @PATCH("api/favourites/{id}")
    suspend fun updateFavouriteFuelType(@Path("id") favouriteId: Int, @Body body: FavouriteFuelTypeUpdateRequest): FavouriteDto

    @DELETE("api/favourites/{id}")
    suspend fun removeFavourite(@Path("id") favouriteId: Int)

    // ── Area alerts ──────────────────────────────────────

    @GET("api/alerts/")
    suspend fun getAlerts(): List<AlertSubscriptionDto>

    @POST("api/alerts/")
    suspend fun addAlert(@Body body: AlertCreateRequest): AlertSubscriptionDto

    @DELETE("api/alerts/{id}")
    suspend fun removeAlert(@Path("id") id: Int)

    // ── Station ratings ──────────────────────────────────
    // Every route answers 404 while the shared.station-ratings flag is off server-side.

    @GET("api/stations/{id}/ratings")
    suspend fun getStationRatings(@Path("id") stationId: Int, @Query("page") page: Int = 1): PublicRatingsResponse

    @GET("api/ratings/mine")
    suspend fun getMyRating(@Query("station_id") stationId: Int): MyRatingResponse

    @POST("api/stations/{id}/ratings")
    suspend fun createRating(@Path("id") stationId: Int, @Body body: RatingInputRequest): RatingSavedResponse

    @PATCH("api/ratings/{id}")
    suspend fun updateRating(@Path("id") ratingId: Int, @Body body: RatingInputRequest): RatingSavedResponse

    @POST("api/ratings/{id}/report")
    suspend fun reportRating(@Path("id") ratingId: Int, @Body body: ReportRatingRequest)

    @POST("api/ratings/{id}/block-author")
    suspend fun blockRatingAuthor(@Path("id") ratingId: Int): BlockAuthorResponse

    @GET("api/ratings/blocked")
    suspend fun getBlockedReviewers(): BlockedReviewersResponse

    @DELETE("api/ratings/blocked/{authorRef}")
    suspend fun unblockReviewer(@Path("authorRef") authorRef: String)

    // ── Discrepancy ──────────────────────────────────────

    @POST("api/discrepancy/")
    suspend fun reportDiscrepancy(@Body body: DiscrepancyReportRequest)

    @GET("api/discrepancy/report-url")
    suspend fun getDiscrepancyReportUrl(): Map<String, String>
}
