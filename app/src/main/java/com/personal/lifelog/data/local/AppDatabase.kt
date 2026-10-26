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
import com.personal.lifelog.data.local.dao.TodoDao
import com.personal.lifelog.data.local.entity.*
import java.time.Clock

@Database(
    entities = [MealRecord::class, SleepRecord::class, WorkRecord::class, ExerciseDefinition::class,
        WorkoutSession::class, WorkoutItem::class, WorkoutSet::class,
        MediaAsset::class, AppSettings::class, RestoreState::class, TodoRecord::class, HabitRecord::class, MealPhoto::class],
    version = 7,
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
    abstract fun todos(): TodoDao
    abstract fun mealPhotos(): com.personal.lifelog.data.local.dao.MealPhotoDao

    companion object {
        const val NAME = "life-log.db"

        fun create(context: Context, clock: Clock, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addCallback(creationCallback(clock))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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

        /** Existing workouts remain unrated; no fabricated exertion or rewritten set history. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE WorkoutSession ADD COLUMN effort TEXT")
                db.execSQL("ALTER TABLE WorkoutSession ADD COLUMN effortText TEXT")
                installEffortGuard(db)
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS HabitRecord (id TEXT NOT NULL, title TEXT NOT NULL, startDate TEXT NOT NULL, scheduledMinute INTEGER, frequency TEXT NOT NULL, intervalDays INTEGER NOT NULL, weekdaysMask INTEGER NOT NULL, status TEXT NOT NULL, createdAtUtcMs INTEGER NOT NULL, updatedAtUtcMs INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS TodoRecord (id TEXT NOT NULL, title TEXT NOT NULL, localDate TEXT NOT NULL, scheduledMinute INTEGER, habitId TEXT, habitDate TEXT, completedAtUtcMs INTEGER, deletedAtUtcMs INTEGER, createdAtUtcMs INTEGER NOT NULL, updatedAtUtcMs INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(habitId) REFERENCES HabitRecord(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_TodoRecord_localDate ON TodoRecord (localDate)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_TodoRecord_habitId_habitDate ON TodoRecord (habitId, habitDate)")
                installTodoGuards(db)
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS MealPhoto (mealId TEXT NOT NULL, photoId TEXT NOT NULL, sortOrder INTEGER NOT NULL, PRIMARY KEY(mealId, photoId), FOREIGN KEY(mealId) REFERENCES MealRecord(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(photoId) REFERENCES MediaAsset(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_MealPhoto_photoId ON MealPhoto (photoId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_MealPhoto_mealId_sortOrder ON MealPhoto (mealId, sortOrder)")
                db.execSQL("INSERT INTO MealPhoto(mealId,photoId,sortOrder) SELECT id,photoId,0 FROM MealRecord WHERE photoId IS NOT NULL")
                installMealPhotoGuards(db)
            }
        }

        private fun installMealPhotoGuards(db: SupportSQLiteDatabase) {
            listOf("INSERT", "UPDATE").forEach { op ->
                db.execSQL("CREATE TRIGGER guard_MealPhoto_$op BEFORE $op ON MealPhoto WHEN NEW.sortOrder NOT BETWEEN 0 AND 8 BEGIN SELECT RAISE(ABORT, 'invalid MealPhoto order'); END")
            }
        }

        private fun installTodoGuards(db: SupportSQLiteDatabase) {
            fun invalidDate(column: String) = "(length(NEW.$column) != 10 OR NEW.$column < '1900-01-01' OR NEW.$column > '2199-12-31' OR date(NEW.$column, '+0 days') IS NOT NEW.$column)"
            val common = "length(trim(NEW.title)) NOT BETWEEN 1 AND 120 OR " +
                "(NEW.scheduledMinute IS NOT NULL AND (typeof(NEW.scheduledMinute) != 'integer' OR NEW.scheduledMinute NOT BETWEEN 0 AND 1439)) OR " +
                "NEW.updatedAtUtcMs < NEW.createdAtUtcMs"
            listOf("INSERT", "UPDATE").forEach { operation ->
                db.execSQL("CREATE TRIGGER guard_HabitRecord_$operation BEFORE $operation ON HabitRecord WHEN " +
                    "$common OR ${invalidDate("startDate")} OR NEW.frequency NOT IN ('DAILY','INTERVAL','WEEKLY') OR " +
                    "NEW.status NOT IN ('ACTIVE','PAUSED','ARCHIVED') OR " +
                    "(NEW.frequency = 'DAILY' AND (NEW.intervalDays != 1 OR NEW.weekdaysMask != 0)) OR " +
                    "(NEW.frequency = 'INTERVAL' AND (NEW.intervalDays NOT BETWEEN 1 AND 365 OR NEW.weekdaysMask != 0)) OR " +
                    "(NEW.frequency = 'WEEKLY' AND (NEW.intervalDays != 1 OR NEW.weekdaysMask NOT BETWEEN 1 AND 127)) " +
                    "BEGIN SELECT RAISE(ABORT, 'invalid HabitRecord invariant'); END")
                db.execSQL("CREATE TRIGGER guard_TodoRecord_$operation BEFORE $operation ON TodoRecord WHEN " +
                    "$common OR ${invalidDate("localDate")} OR " +
                    "((NEW.habitId IS NULL) != (NEW.habitDate IS NULL)) OR " +
                    "(NEW.habitDate IS NOT NULL AND ${invalidDate("habitDate")}) OR " +
                    "(NEW.completedAtUtcMs IS NOT NULL AND (NEW.completedAtUtcMs < NEW.createdAtUtcMs OR NEW.completedAtUtcMs > NEW.updatedAtUtcMs)) OR " +
                    "(NEW.deletedAtUtcMs IS NOT NULL AND (NEW.deletedAtUtcMs < NEW.createdAtUtcMs OR NEW.deletedAtUtcMs > NEW.updatedAtUtcMs)) " +
                    (if (operation == "UPDATE") "OR NEW.habitId IS NOT OLD.habitId OR NEW.habitDate IS NOT OLD.habitDate OR (OLD.deletedAtUtcMs IS NOT NULL AND NEW.deletedAtUtcMs IS NULL) " else "") +
                    "BEGIN SELECT RAISE(ABORT, 'invalid TodoRecord invariant'); END")
            }
        }

        private fun installEffortGuard(db: SupportSQLiteDatabase) {
            listOf("INSERT", "UPDATE").forEach { operation ->
                db.execSQL("CREATE TRIGGER guard_WorkoutSession_$operation BEFORE $operation ON WorkoutSession WHEN " +
                    "(NEW.effort IS NOT NULL AND NEW.effort NOT IN ('EASY','MODERATE','HARD','VERY_HARD','CUSTOM')) OR " +
                    "(NEW.effort = 'CUSTOM' AND (NEW.effortText IS NULL OR length(trim(NEW.effortText)) NOT BETWEEN 1 AND ${Rules.EFFORT_TEXT_LIMIT})) OR " +
                    "((NEW.effort IS NULL OR NEW.effort != 'CUSTOM') AND NEW.effortText IS NOT NULL) " +
                    "BEGIN SELECT RAISE(ABORT, 'invalid WorkoutSession invariant'); END")
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
            installEffortGuard(db)
            installTodoGuards(db)
            installMealPhotoGuards(db)
            guard("RestoreState", "NEW.id != 1")
            guard("SleepRecord", "(NEW.endedAtUtcMs IS NULL AND (NEW.activeSlot IS NOT 1 OR NEW.endZoneId IS NOT NULL)) OR (NEW.endedAtUtcMs IS NOT NULL AND (NEW.activeSlot IS NOT NULL OR NEW.endZoneId IS NULL OR NEW.endedAtUtcMs <= NEW.startedAtUtcMs))")
            guard("ExerciseDefinition", "(NEW.isHidden = 0 AND NEW.activeNameKey IS NOT NEW.normalizedName) OR (NEW.isHidden = 1 AND NEW.activeNameKey IS NOT NULL)")
            installMealGuard(db)
            guard("WorkoutSet", "(NEW.weightGrams IS NULL AND NEW.repetitions IS NULL) OR NEW.weightGrams < 0 OR NEW.repetitions <= 0")
        }
    }
}
