package uk.co.fuelprices.data.db

import androidx.room.*

// ── Entities ─────────────────────────────────────────────

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val id: Int,
    val govId: String,
    val name: String,
    val brand: String?,
    val operator: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val town: String?,
    val county: String?,
    val postcode: String?,
    val phone: String?,
    val latitude: Double,
    val longitude: Double,
    val temporaryClosure: Boolean = false,
    val isMotorway: Boolean = false,
    val isSupermarket: Boolean = false,
    // Complex DTO fields stored as raw JSON strings so the entity stays primitive (no Room
    // TypeConverters); the repository encodes/decodes them. amenities keeps its dual array/object
    // shape because the original JSON is round-tripped verbatim.
    val amenitiesJson: String?,
    val openingHoursJson: String?,
    val lastFetchedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "fuel_prices",
    primaryKeys = ["stationId", "fuelType"],
    foreignKeys = [ForeignKey(
        entity = StationEntity::class,
        parentColumns = ["id"],
        childColumns = ["stationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("stationId")]
)
data class FuelPriceEntity(
    val stationId: Int,
    val fuelType: String,
    val pricePence: Double,
    val reportedAt: String,
)

data class StationWithPrices(
    @Embedded val station: StationEntity,
    @Relation(parentColumn = "id", entityColumn = "stationId")
    val prices: List<FuelPriceEntity>,
)

// ── DAO ──────────────────────────────────────────────────

@Dao
interface StationDao {
    @Transaction
    @Query("SELECT * FROM stations ORDER BY name ASC LIMIT :limit")
    suspend fun getAllStations(limit: Int = 100): List<StationWithPrices>

    /** Cached stations within a bounding box, refreshed within the freshness window. */
    @Transaction
    @Query("""
        SELECT * FROM stations
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLng AND :maxLng
          AND lastFetchedAt >= :freshAfter
        ORDER BY name ASC
        LIMIT :limit
    """)
    suspend fun getFreshStationsNear(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
        freshAfter: Long, limit: Int = 100,
    ): List<StationWithPrices>

    @Transaction
    @Query("SELECT * FROM stations WHERE id = :id")
    suspend fun getStationById(id: Int): StationWithPrices?

    /**
     * Offline fallback for the server's `/api/stations/search`. Matches the same four fields the
     * server does — name, postcode, brand and **town** (town was missing here for a long time,
     * silently making offline search narrower than online search for anyone typing a place name).
     *
     * There is deliberately no `ORDER BY`: the caller ([uk.co.fuelprices.data.repository.FuelRepository])
     * sorts by distance in Kotlin when it has a fix, since SQLite can't do haversine. [limit] is
     * therefore a *candidate* cap here, not the number of rows the user sees — the repository
     * passes a wider cap and trims to the display limit after sorting, so the nearest matches
     * aren't lost to an arbitrary pre-sort truncation. It has no default for that reason: a
     * caller passing the display limit here would silently reintroduce that truncation.
     */
    @Transaction
    @Query("""
        SELECT * FROM stations
        WHERE name LIKE '%' || :query || '%'
           OR postcode LIKE '%' || :query || '%'
           OR brand LIKE '%' || :query || '%'
           OR town LIKE '%' || :query || '%'
        LIMIT :limit
    """)
    suspend fun searchStations(query: String, limit: Int): List<StationWithPrices>

    @Upsert
    suspend fun upsertStations(stations: List<StationEntity>)

    @Upsert
    suspend fun upsertPrices(prices: List<FuelPriceEntity>)

    @Query("DELETE FROM stations WHERE lastFetchedAt < :before")
    suspend fun deleteStale(before: Long)
}

// ── Database ─────────────────────────────────────────────

@Database(
    entities = [StationEntity::class, FuelPriceEntity::class],
    // v2: StationEntity gained addressLine2/county/phone, the closure/motorway/supermarket flags,
    // and JSON columns for amenities + opening hours. The cache is rebuildable, so the DI builder's
    // fallbackToDestructiveMigration() handles the bump — no hand-written Migration needed.
    version = 2,
    exportSchema = false,
)
abstract class FuelDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao
}
