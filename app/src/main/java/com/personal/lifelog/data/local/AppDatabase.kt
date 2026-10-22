package com.personal.lifelog.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.dao.MetadataDao
import com.personal.lifelog.data.local.dao.RecordDao
import com.personal.lifelog.data.local.dao.WorkoutDao
import com.personal.lifelog.data.local.entity.*
import java.time.Clock

@Database(
    entities = [MealRecord::class, SleepRecord::class, ExerciseDefinition::class,
        WorkoutSession::class, WorkoutItem::class, WorkoutSet::class,
        MediaAsset::class, AppSettings::class, RestoreState::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun workouts(): WorkoutDao
    abstract fun metadata(): MetadataDao

    companion object {
        const val NAME = "life-log.db"

        fun create(context: Context, clock: Clock, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addCallback(creationCallback(clock))
                // First real schema is v1. Future versions must register explicit tested migrations.
                // Never add fallbackToDestructiveMigration to the user database.
                .build()

        fun creationCallback(clock: Clock): Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                installGuards(db)
                val now = clock.millis()
                BuiltinExercises.entries.forEach { entry ->
                    val key = Rules.normalizedName(entry.name)
                    db.execSQL(
                        "INSERT INTO ExerciseDefinition (id,name,normalizedName,type,isBuiltin,isHidden,activeNameKey,lastUsedAtUtcMs,createdAtUtcMs,updatedAtUtcMs) VALUES (?,?,?,?,1,0,?,NULL,?,?)",
                        arrayOf<Any>(entry.id, entry.name, key, entry.type.name, key, now, now),
                    )
                }
                db.execSQL("INSERT INTO AppSettings (id,themeMode,lastSuccessfulExportAtUtcMs,createdAtUtcMs,updatedAtUtcMs) VALUES (1,'SYSTEM',NULL,?,?)", arrayOf(now, now))
                db.execSQL("INSERT INTO RestoreState (id,lastCommittedRestoreId,createdAtUtcMs,updatedAtUtcMs) VALUES (1,NULL,?,?)", arrayOf(now, now))
            }
        }

        /** Custom guards supplement Room's exported tables/indices; preserve them in future migrations. */
        private fun installGuards(db: SupportSQLiteDatabase) {
            fun guard(table: String, condition: String) {
                listOf("INSERT", "UPDATE").forEach { operation ->
                    db.execSQL("CREATE TRIGGER guard_${table}_${operation} BEFORE $operation ON $table WHEN $condition BEGIN SELECT RAISE(ABORT, 'invalid $table invariant'); END")
                }
            }
            guard("AppSettings", "NEW.id != 1")
            guard("RestoreState", "NEW.id != 1")
            guard("SleepRecord", "(NEW.endedAtUtcMs IS NULL AND (NEW.activeSlot IS NOT 1 OR NEW.endZoneId IS NOT NULL)) OR (NEW.endedAtUtcMs IS NOT NULL AND (NEW.activeSlot IS NOT NULL OR NEW.endZoneId IS NULL OR NEW.endedAtUtcMs <= NEW.startedAtUtcMs))")
            guard("ExerciseDefinition", "(NEW.isHidden = 0 AND NEW.activeNameKey IS NOT NEW.normalizedName) OR (NEW.isHidden = 1 AND NEW.activeNameKey IS NOT NULL)")
            guard("MealRecord", "NEW.photoId IS NULL AND (NEW.text IS NULL OR length(trim(NEW.text)) = 0)")
            guard("WorkoutSet", "(NEW.weightGrams IS NULL AND NEW.repetitions IS NULL) OR NEW.weightGrams < 0 OR NEW.repetitions <= 0")
        }
    }
}
