package com.personal.lifelog.data.backup.format

import android.graphics.BitmapFactory
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.repository.DataSnapshot
import com.personal.lifelog.domain.ExerciseType
import java.io.File

object BackupValidation {
    fun counts(d: DataSnapshot) = linkedMapOf("meals" to d.meals.size, "sleeps" to d.sleeps.size,
        "exercises" to d.exercises.size, "sessions" to d.sessions.size, "items" to d.items.size,
        "sets" to d.sets.size, "media" to d.media.size)

    fun validate(d: DataSnapshot, l: BackupLimits) {
        fun ids(values: List<String>) { values.forEach(Rules::id); requireBackup(values.distinct().size == values.size) }
        fun times(created: Long, updated: Long) { requireBackup(updated >= created) }
        ids(d.meals.map { it.id }); ids(d.sleeps.map { it.id }); ids(d.exercises.map { it.id })
        ids(d.sessions.map { it.id }); ids(d.items.map { it.id }); ids(d.sets.map { it.id }); ids(d.media.map { it.id })
        requireBackup(d.meals.size + d.sleeps.size + d.sessions.size <= l.records &&
            d.exercises.size <= l.exercises && d.media.size <= l.photos && d.sets.size <= l.sets, BackupError.LIMIT)
        val photos = d.media.associateBy { it.id }
        requireBackup(d.meals.mapNotNull { it.photoId }.toSet() == photos.keys)
        d.media.forEach { MediaRules.validate(it); requireBackup(it.byteSize <= l.photoBytes, BackupError.LIMIT) }
        d.meals.forEach {
            times(it.createdAtUtcMs, it.updatedAtUtcMs)
            requireBackup(it.localDate == RecordTime.localDate(it.occurredAtUtcMs, it.zoneId))
            requireBackup(Rules.text(it.text, Rules.MEAL_TEXT_LIMIT) == it.text)
            requireBackup(it.text != null || it.photoId != null)
        }
        val sleeps = d.sleeps.sortedBy { it.startedAtUtcMs }
        sleeps.forEachIndexed { i, s ->
            times(s.createdAtUtcMs, s.updatedAtUtcMs)
            RecordTime.zone(s.startZoneId)
            if (s.endedAtUtcMs == null) requireBackup(s.endZoneId == null && s.activeSlot == 1)
            else {
                requireBackup(s.endZoneId != null && s.activeSlot == null)
                RecordTime.durationSeconds(s.startedAtUtcMs, s.endedAtUtcMs)
            }
            requireBackup(s.archiveLocalDate == RecordTime.localDate(s.endedAtUtcMs ?: s.startedAtUtcMs,
                s.endZoneId ?: s.startZoneId))
            if (i > 0) requireBackup(sleeps[i - 1].endedAtUtcMs?.let { it <= s.startedAtUtcMs } == true)
        }
        val definitions = d.exercises.associateBy { it.id }
        val builtins = BuiltinExercises.entries.associateBy { it.id }
        requireBackup(builtins.keys.all { it in definitions })
        val activeNames = mutableSetOf<Pair<ExerciseType, String>>()
        d.exercises.forEach {
            times(it.createdAtUtcMs, it.updatedAtUtcMs)
            requireBackup(Rules.exerciseName(it.name) == it.name)
            val key = Rules.normalizedName(it.name)
            requireBackup(it.normalizedName == key && it.activeNameKey == if (it.isHidden) null else key)
            requireBackup(it.isBuiltin == (it.id in builtins))
            if (!it.isHidden) requireBackup(activeNames.add(it.type to key))
        }
        val sessions = d.sessions.associateBy { it.id }
        val items = d.items.associateBy { it.id }
        val groupedItems = d.items.groupBy { it.sessionId }
        val groupedSets = d.sets.groupBy { it.itemId }
        d.sessions.forEach {
            times(it.createdAtUtcMs, it.updatedAtUtcMs)
            requireBackup(it.localDate == RecordTime.localDate(it.occurredAtUtcMs, it.zoneId))
            requireBackup(Rules.text(it.note, Rules.NOTE_LIMIT) == it.note)
            val children = groupedItems[it.id].orEmpty()
            requireBackup(children.isNotEmpty())
            requireBackup(children.size <= l.itemsPerSession, BackupError.LIMIT)
            requireBackup(children.map { c -> c.sortOrder }.sorted() == children.indices.toList())
        }
        d.items.forEach {
            times(it.createdAtUtcMs, it.updatedAtUtcMs)
            requireBackup(it.sessionId in sessions && it.exerciseId in definitions)
            requireBackup(Rules.exerciseName(it.nameSnapshot) == it.nameSnapshot)
            requireBackup(Rules.text(it.note, Rules.NOTE_LIMIT) == it.note)
            Rules.positive(it.durationSeconds, "durationSeconds"); Rules.positive(it.distanceMeters, "distanceMeters")
            val children = groupedSets[it.id].orEmpty()
            requireBackup(children.size <= l.setsPerItem, BackupError.LIMIT)
            requireBackup(children.map { c -> c.sortOrder }.sorted() == children.indices.toList())
            requireBackup(!(it.typeSnapshot == ExerciseType.STRENGTH && (it.durationSeconds != null || it.distanceMeters != null)))
            requireBackup(!(it.typeSnapshot == ExerciseType.OTHER && it.distanceMeters != null))
            requireBackup(it.typeSnapshot == ExerciseType.STRENGTH || children.isEmpty())
        }
        d.sets.forEach {
            times(it.createdAtUtcMs, it.updatedAtUtcMs)
            requireBackup(it.itemId in items && (it.weightGrams != null || it.repetitions != null))
            Rules.weight(it.weightGrams); Rules.repetitions(it.repetitions)
        }
        requireBackup(d.settings.id == 1)
        times(d.settings.createdAtUtcMs, d.settings.updatedAtUtcMs)
    }

    suspend fun photo(file: File, asset: MediaAsset, l: BackupLimits) {
        val actual = digest(file)
        requireBackup(actual.byteSize == asset.byteSize && actual.sha256 == asset.sha256, BackupError.HASH_MISMATCH)
        java.io.RandomAccessFile(file, "r").use { jpeg ->
            requireBackup(jpeg.length() >= 4, BackupError.INVALID_PHOTO)
            requireBackup(jpeg.readUnsignedShort() == 0xffd8, BackupError.INVALID_PHOTO)
            jpeg.seek(jpeg.length() - 2)
            requireBackup(jpeg.readUnsignedShort() == 0xffd9, BackupError.INVALID_PHOTO)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        requireBackup(bounds.outMimeType == "image/jpeg" && bounds.outWidth == asset.width && bounds.outHeight == asset.height,
            BackupError.INVALID_PHOTO)
        requireBackup(asset.width.toLong() * asset.height <= l.imagePixels, BackupError.LIMIT)
        val options = BitmapFactory.Options()
        while (asset.width.toLong() * asset.height / options.inSampleSize.coerceAtLeast(1).let { it.toLong() * it } > 1_000_000) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: fail(BackupError.INVALID_PHOTO)
        bitmap.recycle()
    }
}
