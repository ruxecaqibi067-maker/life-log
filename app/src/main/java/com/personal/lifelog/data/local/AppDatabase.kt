package com.personal.lifelog.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.dao.MetadataDao
import com.personal.lifelog.data.local.dao.RecordDao
import com.personal.lifelog.data.local.dao.RestoreDao
import com.personal.lifelog.data.local.dao.SleepSummaryDao
import com.personal.lifelog.data.local.dao.WorkDao
import com.personal.lifelog.data.local.dao.WorkSummaryDao
import com.personal.lifelog.data.local.dao.WorkoutDao
import com.personal.lifelog.data.local.entity.*
import java.time.Clock

@Database(
    entities = [MealRecord::class, SleepRecord::class, WorkRecord::class, ExerciseDefinition::class,
        WorkoutSession::class, WorkoutItem::class, WorkoutSet::class,
        MediaAsset::class, AppSettings::class, RestoreState::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun workouts(): WorkoutDao
    abstract fun metadata(): MetadataDao
    abstract fun restore(): RestoreDao
    abstract fun sleepSummaries(): SleepSummaryDao
    abstract fun work(): WorkDao
    abstract fun workSummaries(): WorkSummaryDao

    companion object {
        const val NAME = "life-log.db"

        fun create(context: Context, clock: Clock, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addCallback(creationCallback(clock))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // First real schema was v1. Every later version has an explicit tested migration.
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

        /** Additive v1 -> v2: custom satiety text, with the new row invariant. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE MealRecord ADD COLUMN satietyText TEXT")
                installMealGuard(db)
            }
        }

        /** Existing users have no goal until they explicitly save one. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE AppSettings ADD COLUMN targetSleepMinutes INTEGER")
                installSettingsGuard(db, includeWork = false)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS WorkRecord (id TEXT NOT NULL, startedAtUtcMs INTEGER NOT NULL, startZoneId TEXT NOT NULL, endedAtUtcMs INTEGER, endZoneId TEXT, archiveLocalDate TEXT NOT NULL, activeSlot INTEGER, createdAtUtcMs INTEGER NOT NULL, updatedAtUtcMs INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_WorkRecord_archiveLocalDate ON WorkRecord (archiveLocalDate)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_WorkRecord_activeSlot ON WorkRecord (activeSlot)")
                db.execSQL("ALTER TABLE AppSettings ADD COLUMN targetWorkMinutes INTEGER")
                installSettingsGuard(db)
                installWorkGuard(db)
            }
        }

        private fun installWorkGuard(db: SupportSQLiteDatabase) {
            listOf("INSERT", "UPDATE").forEach { operation ->
                db.execSQL("CREATE TRIGGER guard_WorkRecord_$operation BEFORE $operation ON WorkRecord WHEN " +
                    "(NEW.endedAtUtcMs IS NULL AND (NEW.activeSlot IS NOT 1 OR NEW.endZoneId IS NOT NULL)) OR " +
                    "(NEW.endedAtUtcMs IS NOT NULL AND (NEW.activeSlot IS NOT NULL OR NEW.endZoneId IS NULL OR NEW.endedAtUtcMs <= NEW.startedAtUtcMs OR typeof(NEW.endedAtUtcMs - NEW.startedAtUtcMs) != 'integer')) OR " +
                    "EXISTS(SELECT 1 FROM WorkRecord WHERE id != NEW.id AND (NEW.endedAtUtcMs IS NULL OR startedAtUtcMs < NEW.endedAtUtcMs) AND (endedAtUtcMs IS NULL OR NEW.startedAtUtcMs < endedAtUtcMs)) " +
                    "BEGIN SELECT RAISE(ABORT, 'invalid WorkRecord invariant'); END")
            }
        }

        private fun installSettingsGuard(db: SupportSQLiteDatabase, includeWork: Boolean = true) {
            listOf("INSERT", "UPDATE").forEach { operation ->
                db.execSQL("DROP TRIGGER IF EXISTS guard_AppSettings_$operation")
                db.execSQL("CREATE TRIGGER guard_AppSettings_$operation BEFORE $operation ON AppSettings WHEN " +
                    "NEW.id != 1 OR (NEW.targetSleepMinutes IS NOT NULL AND " +
                    "(typeof(NEW.targetSleepMinutes) != 'integer' OR NEW.targetSleepMinutes < 1 OR " +
                    "NEW.targetSleepMinutes > ${Rules.TARGET_SLEEP_MINUTES_MAX})) " +
                    (if (includeWork) "OR (NEW.targetWorkMinutes IS NOT NULL AND " +
                        "(typeof(NEW.targetWorkMinutes) != 'integer' OR NEW.targetWorkMinutes < 1 OR " +
                        "NEW.targetWorkMinutes > ${Rules.TARGET_WORK_MINUTES_MAX})) " else "") +
                    "BEGIN SELECT RAISE(ABORT, 'invalid AppSettings invariant'); END")
            }
        }

        /**
         * One trigger covers both the "content or photo" invariant and custom satiety:
         * CUSTOM requires a non-empty short text; every other value forbids text.
         */
        internal fun installMealGuard(db: SupportSQLiteDatabase) {
            listOf("INSERT", "UPDATE").forEach { operation ->
                db.execSQL("DROP TRIGGER IF EXISTS guard_MealRecord_$operation")
                db.execSQL(
                    "CREATE TRIGGER guard_MealRecord_$operation BEFORE $operation ON MealRecord WHEN " +
                        "(NEW.photoId IS NULL AND (NEW.text IS NULL OR length(trim(NEW.text)) = 0)) OR " +
                        "(NEW.satiety = 'CUSTOM' AND (NEW.satietyText IS NULL OR " +
                        "length(trim(NEW.satietyText)) = 0 OR length(trim(NEW.satietyText)) > ${Rules.SATIETY_TEXT_LIMIT})) OR " +
                        "(NEW.satiety IS NULL AND NEW.satietyText IS NOT NULL) OR " +
                        "(NEW.satiety IS NOT NULL AND NEW.satiety != 'CUSTOM' AND NEW.satietyText IS NOT NULL) " +
                        "BEGIN SELECT RAISE(ABORT, 'invalid MealRecord invariant'); END",
                )
            }
        }

        /** Custom guards supplement Room's exported tables/indices; preserve them in future migrations. */
        private fun installGuards(db: SupportSQLiteDatabase) {
            fun guard(table: String, condition: String) {
                listOf("INSERT", "UPDATE").forEach { operation ->
                    db.execSQL("CREATE TRIGGER guard_${table}_${operation} BEFORE $operation ON $table WHEN $condition BEGIN SELECT RAISE(ABORT, 'invalid $table invariant'); END")
                }
            }
            installSettingsGuard(db)
            installWorkGuard(db)
            guard("RestoreState", "NEW.id != 1")
            guard("SleepRecord", "(NEW.endedAtUtcMs IS NULL AND (NEW.activeSlot IS NOT 1 OR NEW.endZoneId IS NOT NULL)) OR (NEW.endedAtUtcMs IS NOT NULL AND (NEW.activeSlot IS NOT NULL OR NEW.endZoneId IS NULL OR NEW.endedAtUtcMs <= NEW.startedAtUtcMs))")
            guard("ExerciseDefinition", "(NEW.isHidden = 0 AND NEW.activeNameKey IS NOT NEW.normalizedName) OR (NEW.isHidden = 1 AND NEW.activeNameKey IS NOT NULL)")
            installMealGuard(db)
            guard("WorkoutSet", "(NEW.weightGrams IS NULL AND NEW.repetitions IS NULL) OR NEW.weightGrams < 0 OR NEW.repetitions <= 0")
        }
    }
}
