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
    val town: String?,
    val postcode: String?,
    val latitude: Double,
    val longitude: Double,
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

    @Transaction
    @Query("""
        SELECT * FROM stations 
        WHERE name LIKE '%' || :query || '%' 
           OR postcode LIKE '%' || :query || '%'
           OR brand LIKE '%' || :query || '%'
        LIMIT :limit
    """)
    suspend fun searchStations(query: String, limit: Int = 20): List<StationWithPrices>

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
    version = 1,
    exportSchema = false,
)
abstract class FuelDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao
}
