package io.motohub.android.feature.trips

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class TripStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trips (
                id TEXT PRIMARY KEY,
                name TEXT,
                motorcycle_id TEXT,
                source TEXT NOT NULL,
                started_at_ms INTEGER NOT NULL,
                ended_at_ms INTEGER,
                distance_m REAL NOT NULL DEFAULT 0,
                moving_time_ms INTEGER NOT NULL DEFAULT 0,
                elapsed_time_ms INTEGER NOT NULL DEFAULT 0,
                max_speed_mps REAL NOT NULL DEFAULT 0,
                point_count INTEGER NOT NULL DEFAULT 0,
                min_lat REAL,
                max_lat REAL,
                min_lon REAL,
                max_lon REAL,
                active INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE trip_points (
                trip_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                speed_mps REAL NOT NULL,
                accuracy_m REAL NOT NULL,
                altitude_m REAL,
                PRIMARY KEY (trip_id, sequence),
                FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX trip_points_time ON trip_points(trip_id, timestamp_ms)")
        db.execSQL("CREATE INDEX trips_started ON trips(started_at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun createTrip(
        motorcycleId: String?,
        source: TripRecordingSource,
        startedAtMillis: Long = System.currentTimeMillis()
    ): TripSummary {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow(
            TABLE_TRIPS,
            null,
            ContentValues().apply {
                put("id", id)
                put("motorcycle_id", motorcycleId)
                put("source", source.name)
                put("started_at_ms", startedAtMillis)
            }
        )
        return checkNotNull(getSummary(id))
    }

    @Synchronized
    internal fun persistProgress(
        tripId: String,
        points: List<TripTrackPoint>,
        snapshot: TripRecordingSnapshot
    ) {
        writableDatabase.inTransaction {
            insertPoints(tripId, points)
            update(
                TABLE_TRIPS,
                progressValues(snapshot),
                "id = ?",
                arrayOf(tripId)
            )
        }
    }

    @Synchronized
    internal fun finalizeTrip(
        tripId: String,
        points: List<TripTrackPoint>,
        snapshot: TripRecordingSnapshot,
        endedAtMillis: Long,
        keep: Boolean
    ) {
        writableDatabase.inTransaction {
            if (!keep) {
                delete(TABLE_TRIPS, "id = ?", arrayOf(tripId))
                return@inTransaction
            }
            insertPoints(tripId, points)
            update(
                TABLE_TRIPS,
                progressValues(snapshot).apply {
                    put("ended_at_ms", endedAtMillis)
                    put("active", 0)
                },
                "id = ?",
                arrayOf(tripId)
            )
        }
    }

    @Synchronized
    fun activeTrip(): Pair<TripSummary, TripTrackPoint?>? {
        val summary = readableDatabase.query(
            TABLE_TRIPS,
            TRIP_COLUMNS,
            "active = 1",
            null,
            null,
            null,
            "started_at_ms DESC",
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.readSummary() else null } ?: return null
        return summary to lastPoint(summary.id)
    }

    @Synchronized
    fun listTrips(): List<TripSummary> = readableDatabase.query(
        TABLE_TRIPS,
        TRIP_COLUMNS,
        "active = 0",
        null,
        null,
        null,
        "started_at_ms DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readSummary()) } }

    @Synchronized
    fun getTrip(id: String): TripDetails? {
        val summary = getSummary(id) ?: return null
        return TripDetails(summary, trackPoints(id))
    }

    @Synchronized
    internal fun trackPoints(id: String): List<TripTrackPoint> = readableDatabase.query(
            TABLE_POINTS,
            POINT_COLUMNS,
            "trip_id = ?",
            arrayOf(id),
            null,
            null,
            "sequence ASC"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readPoint()) } }

    @Synchronized
    fun libraryStats(): TripLibraryStats = readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(SUM(distance_m), 0), COALESCE(SUM(moving_time_ms), 0) FROM trips WHERE active = 0",
        null
    ).use { cursor ->
        if (!cursor.moveToFirst()) TripLibraryStats() else TripLibraryStats(
            tripCount = cursor.getInt(0),
            totalDistanceMeters = cursor.getDouble(1),
            totalMovingTimeMillis = cursor.getLong(2)
        )
    }

    @Synchronized
    fun rename(id: String, name: String?) {
        writableDatabase.update(
            TABLE_TRIPS,
            ContentValues().apply {
                val normalized = name?.trim()?.takeIf(String::isNotEmpty)
                if (normalized == null) putNull("name") else put("name", normalized.take(80))
            },
            "id = ?",
            arrayOf(id)
        )
    }

    @Synchronized
    fun delete(id: String) {
        writableDatabase.delete(TABLE_TRIPS, "id = ?", arrayOf(id))
    }

    private fun getSummary(id: String): TripSummary? = readableDatabase.query(
        TABLE_TRIPS,
        TRIP_COLUMNS,
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readSummary() else null }

    private fun lastPoint(tripId: String): TripTrackPoint? = readableDatabase.query(
        TABLE_POINTS,
        POINT_COLUMNS,
        "trip_id = ?",
        arrayOf(tripId),
        null,
        null,
        "sequence DESC",
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readPoint() else null }

    private fun progressValues(snapshot: TripRecordingSnapshot) = ContentValues().apply {
        put("distance_m", snapshot.distanceMeters)
        put("moving_time_ms", snapshot.movingTimeMillis)
        put("elapsed_time_ms", snapshot.elapsedTimeMillis)
        put("max_speed_mps", snapshot.maxSpeedMetersPerSecond)
        put("point_count", snapshot.pointCount)
        putNullable("min_lat", snapshot.minLatitude)
        putNullable("max_lat", snapshot.maxLatitude)
        putNullable("min_lon", snapshot.minLongitude)
        putNullable("max_lon", snapshot.maxLongitude)
    }

    private fun SQLiteDatabase.insertPoints(tripId: String, points: List<TripTrackPoint>) {
        points.forEach { point ->
            insertOrThrow(
                TABLE_POINTS,
                null,
                ContentValues().apply {
                    put("trip_id", tripId)
                    put("sequence", point.sequence)
                    put("latitude", point.latitude)
                    put("longitude", point.longitude)
                    put("timestamp_ms", point.timestampMillis)
                    put("speed_mps", point.speedMetersPerSecond)
                    put("accuracy_m", point.accuracyMeters)
                    if (point.altitudeMeters == null) putNull("altitude_m")
                    else put("altitude_m", point.altitudeMeters)
                }
            )
        }
    }

    private fun Cursor.readSummary() = TripSummary(
        id = getString(getColumnIndexOrThrow("id")),
        name = nullableString("name"),
        motorcycleId = nullableString("motorcycle_id"),
        source = TripRecordingSource.fromStorage(getString(getColumnIndexOrThrow("source"))),
        startedAtMillis = getLong(getColumnIndexOrThrow("started_at_ms")),
        endedAtMillis = nullableLong("ended_at_ms"),
        distanceMeters = getDouble(getColumnIndexOrThrow("distance_m")),
        movingTimeMillis = getLong(getColumnIndexOrThrow("moving_time_ms")),
        elapsedTimeMillis = getLong(getColumnIndexOrThrow("elapsed_time_ms")),
        maxSpeedMetersPerSecond = getFloat(getColumnIndexOrThrow("max_speed_mps")),
        pointCount = getInt(getColumnIndexOrThrow("point_count")),
        minLatitude = nullableDouble("min_lat"),
        maxLatitude = nullableDouble("max_lat"),
        minLongitude = nullableDouble("min_lon"),
        maxLongitude = nullableDouble("max_lon"),
        active = getInt(getColumnIndexOrThrow("active")) != 0
    )

    private fun Cursor.readPoint() = TripTrackPoint(
        sequence = getInt(getColumnIndexOrThrow("sequence")),
        latitude = getDouble(getColumnIndexOrThrow("latitude")),
        longitude = getDouble(getColumnIndexOrThrow("longitude")),
        timestampMillis = getLong(getColumnIndexOrThrow("timestamp_ms")),
        speedMetersPerSecond = getFloat(getColumnIndexOrThrow("speed_mps")),
        accuracyMeters = getFloat(getColumnIndexOrThrow("accuracy_m")),
        altitudeMeters = nullableDouble("altitude_m")
    )

    private fun Cursor.nullableString(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.nullableLong(column: String): Long? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.nullableDouble(column: String): Double? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getDouble(it) }

    private fun ContentValues.putNullable(key: String, value: Double?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val DATABASE_NAME = "moto_hub_trips.db"
        const val DATABASE_VERSION = 1
        const val TABLE_TRIPS = "trips"
        const val TABLE_POINTS = "trip_points"
        val TRIP_COLUMNS = arrayOf(
            "id", "name", "motorcycle_id", "source", "started_at_ms", "ended_at_ms",
            "distance_m", "moving_time_ms", "elapsed_time_ms", "max_speed_mps", "point_count",
            "min_lat", "max_lat", "min_lon", "max_lon", "active"
        )
        val POINT_COLUMNS = arrayOf(
            "sequence", "latitude", "longitude", "timestamp_ms", "speed_mps", "accuracy_m", "altitude_m"
        )
    }
}
