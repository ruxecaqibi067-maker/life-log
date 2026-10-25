package com.personal.lifelog.data.backup.format

import android.util.JsonReader
import android.util.JsonToken
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.data.local.entity.*
import com.personal.lifelog.data.repository.DataSnapshot
import com.personal.lifelog.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.CodingErrorAction

/** Explicit v1 wire fields. Derived keys and local media generations never enter the archive. */
object BackupJson {
    const val VERSION = 5
    /** v1: no custom satiety; v1/v2: no sleep goal; v1-v3: no work records/goal. */
    const val MIN_SUPPORTED_VERSION = 1
    private const val IMPORT_GENERATION = "00000000-0000-0000-0000-000000000000"
    fun encode(data: DataSnapshot): JSONObject = JSONObject().put("dataVersion", VERSION)
        .put("meals", JSONArray(data.meals.map { encode(it) }))
        .put("sleeps", JSONArray(data.sleeps.map { encode(it) }))
        .put("workRecords", JSONArray(data.workRecords.map { encode(it) }))
        .put("exercises", JSONArray(data.exercises.map { encode(it) }))
        .put("sessions", JSONArray(data.sessions.map { encode(it) }))
        .put("items", JSONArray(data.items.map { encode(it) }))
        .put("sets", JSONArray(data.sets.map { encode(it) }))
        .put("media", JSONArray(data.media.map { encode(it) }))
        .put("settings", encode(data.settings))

    fun decode(json: JSONObject): DataSnapshot {
        val version = json.int("dataVersion")
        requireBackup(version in MIN_SUPPORTED_VERSION..VERSION, BackupError.UNSUPPORTED_VERSION)
        return DataSnapshot(
            meals = json.array("meals").objects().map(::decodeMealRecord),
            sleeps = json.array("sleeps").objects().map(::decodeSleepRecord),
            exercises = json.array("exercises").objects().map(::decodeExerciseDefinition),
            sessions = json.array("sessions").objects().map { decodeWorkoutSession(it, version) },
            items = json.array("items").objects().map(::decodeWorkoutItem),
            sets = json.array("sets").objects().map(::decodeWorkoutSet),
            media = json.array("media").objects().map(::decodeMediaAsset),
            settings = decodeAppSettings(json.obj("settings"), version),
            workRecords = if (version < 4) emptyList() else json.array("workRecords").objects().map(::decodeWorkRecord),
        )
    }

    private fun encode(v: MealRecord): JSONObject = JSONObject()
        .put("id", v.id)
        .put("occurredAtUtcMs", v.occurredAtUtcMs)
        .put("zoneId", v.zoneId)
        .put("localDate", v.localDate)
        .put("text", v.text ?: JSONObject.NULL)
        .put("satiety", v.satiety?.name ?: JSONObject.NULL)
        .put("satietyText", v.satietyText ?: JSONObject.NULL)
        .put("photoId", v.photoId ?: JSONObject.NULL)
        .put("createdAtUtcMs", v.createdAtUtcMs)
        .put("updatedAtUtcMs", v.updatedAtUtcMs)
    private fun decodeMealRecord(j: JSONObject): MealRecord = MealRecord(
        id = j.string("id"),
        occurredAtUtcMs = j.long("occurredAtUtcMs"),
        zoneId = j.string("zoneId"),
        localDate = j.string("localDate"),
        text = j.nullableString("text"),
        satiety = j.nullableString("satiety")?.let { Satiety.valueOf(it) },
        satietyText = j.optionalNullableString("satietyText"),
        photoId = j.nullableString("photoId"),
        createdAtUtcMs = j.long("createdAtUtcMs"),
        updatedAtUtcMs = j.long("updatedAtUtcMs"),
    )

    private fun encode(v: SleepRecord): JSONObject = JSONObject()
        .put("id", v.id)
        .put("startedAtUtcMs", v.startedAtUtcMs)
        .put("startZoneId", v.startZoneId)
        .put("endedAtUtcMs", v.endedAtUtcMs ?: JSONObject.NULL)
        .put("endZoneId", v.endZoneId ?: JSONObject.NULL)
        .put("archiveLocalDate", v.archiveLocalDate)
        .put("createdAtUtcMs", v.createdAtUtcMs)
        .put("updatedAtUtcMs", v.updatedAtUtcMs)
    private fun decodeSleepRecord(j: JSONObject): SleepRecord = SleepRecord(
        id = j.string("id"),
        startedAtUtcMs = j.long("startedAtUtcMs"),
        startZoneId = j.string("startZoneId"),
        endedAtUtcMs = j.nullableLong("endedAtUtcMs"),
        endZoneId = j.nullableString("endZoneId"),
        archiveLocalDate = j.string("archiveLocalDate"),
        activeSlot = if (j.nullableLong("endedAtUtcMs") == null) 1 else null,
        createdAtUtcMs = j.long("createdAtUtcMs"),
        updatedAtUtcMs = j.long("updatedAtUtcMs"),
    )

    private fun encode(v: WorkRecord): JSONObject = JSONObject()
        .put("id", v.id)
        .put("startedAtUtcMs", v.startedAtUtcMs)
        .put("startZoneId", v.startZoneId)
        .put("endedAtUtcMs", v.endedAtUtcMs ?: JSONObject.NULL)
        .put("endZoneId", v.endZoneId ?: JSONObject.NULL)
        .put("archiveLocalDate", v.archiveLocalDate)
        .put("createdAtUtcMs", v.createdAtUtcMs)
        .put("updatedAtUtcMs", v.updatedAtUtcMs)
    private fun decodeWorkRecord(j: JSONObject): WorkRecord = WorkRecord(
        id = j.string("id"),
        startedAtUtcMs = j.long("startedAtUtcMs"),
        startZoneId = j.string("startZoneId"),
        endedAtUtcMs = j.nullableLong("endedAtUtcMs"),
        endZoneId = j.nullableString("endZoneId"),
        archiveLocalDate = j.string("archiveLocalDate"),
        activeSlot = if (j.nullableLong("endedAtUtcMs") == null) 1 else null,
        createdAtUtcMs = j.long("createdAtUtcMs"),
        updatedAtUtcMs = j.long("updatedAtUtcMs"),
    )

    private fun encode(v: ExerciseDefinition): JSONObject = JSONObject()
        .put("id", v.id)
        .put("name", v.name)
        .put("type", v.type.name)
        .put("isBuiltin", v.isBuiltin)
        .put("isHidden", v.isHidden)
        .put("lastUsedAtUtcMs", v.lastUsedAtUtcMs ?: JSONObject.NULL)
        .put("createdAtUtcMs", v.createdAtUtcMs)
        .put("updatedAtUtcMs", v.updatedAtUtcMs)
    private fun decodeExerciseDefinition(j: JSONObject): ExerciseDefinition = ExerciseDefinition(
        id = j.string("id"),
        name = j.string("name"),
        normalizedName = Rules.normalizedName(j.string("name")),
        type = ExerciseType.valueOf(j.string("type")),
        isBuiltin = j.boolean("isBuiltin"),
        isHidden = j.boolean("isHidden"),
        activeNameKey = if (j.boolean("isHidden")) null else Rules.normalizedName(j.string("name")),
        lastUsedAtUtcMs = j.nullableLong("lastUsedAtUtcMs"),
        createdAtUtcMs = j.long("createdAtUtcMs"),
        updatedAtUtcMs = j.long("updatedAtUtcMs"),
    )

    private fun encode(v: WorkoutSession): JSONObject = JSONObject()
        .put("id", v.id)
        .put("occurredAtUtcMs", v.occurredAtUtcMs)
        .put("zoneId", v.zoneId)
        .put("localDate", v.localDate)
        .put("note", v.note ?: JSONObject.NULL)
        .put("effort", v.effort?.name ?: JSONObject.NULL)
        .put("effortText", v.effortText ?: JSONObject.NULL)
        .put("createdAtUtcMs", v.createdAtUtcMs)
        .put("updatedAtUtcMs", v.updatedAtUtcMs)
    private fun decodeWorkoutSession(j: JSONObject, version: Int): WorkoutSession = WorkoutSession(
        id = j.string("id"),
        occurredAtUtcMs = j.long("occurredAtUtcMs"),
        zoneId = j.string("zoneId"),
        localDate = j.string("localDate"),
        note = j.nullableString("note"),
        createdAtUtcMs = j.long("createdAtUtcMs"),
        updatedAtUtcMs = j.long("updatedAtUtcMs"),
        effort = if (version < 5) null else j.nullableString("effort")?.let(WorkoutEffort::valueOf),
        effortText = if (version < 5) null else j.nullableString("effortText"),
    )

    private fun encode(v: WorkoutItem): JSONObject = JSONObject()
        .put("id", v.id)
        .put("sessionId", v.sessionId)
        .put("exerciseId", v.exerciseId)
        .put("nameSnapshot", v.nameSnapshot)
        .put("typeSnapshot", v.typeSnapshot.name)
        .put("sortOrder", v.sortOrder)
        .put("durationSeconds", v.durationSeconds ?: JSONObject.NULL)
        .put("distanceMeters", v.distanceMeters ?: JSONObject.NULL)
        .put("note", v.note ?: JSONObject.NULL)
        .put("createdAtUtcMs", v.createdAtUtcMs)
        .put("updatedAtUtcMs", v.updatedAtUtcMs)
    private fun decodeWorkoutItem(j: JSONObject): WorkoutItem = WorkoutItem(
        id = j.string("id"),
        sessionId = j.string("sessionId"),
        exerciseId = j.string("exerciseId"),
        nameSnapshot = j.string("nameSnapshot"),
        typeSnapshot = ExerciseType.valueOf(j.string("typeSnapshot")),
        sortOrder = j.int("sortOrder"),
        durationSeconds = j.nullableLong("durationSeconds"),
        distanceMeters = j.nullableLong("distanceMeters"),
        note = j.nullableString("note"),
        createdAtUtcMs = j.long("createdAtUtcMs"),
        updatedAtUtcMs = j.long("updatedAtUtcMs"),
    )

    private fun encode(v: WorkoutSet): JSONObject = JSONObject()
        .put("id", v.id)
        .put("itemId", v.itemId)
        .put("sortOrder", v.sortOrder)
        .put("weightGrams", v.weightGrams ?: JSONObject.NULL)
        .put("repetitions", v.repetitions ?: JSONObject.NULL)
        .put("createdAtUtcMs", v.createdAtUtcMs)
        .put("updatedAtUtcMs", v.updatedAtUtcMs)
    private fun decodeWorkoutSet(j: JSONObject): WorkoutSet = WorkoutSet(
        id = j.string("id"),
        itemId = j.string("itemId"),
        sortOrder = j.int("sortOrder"),
        weightGrams = j.nullableLong("weightGrams"),
        repetitions = j.nullableInt("repetitions"),
        createdAtUtcMs = j.long("createdAtUtcMs"),
        updatedAtUtcMs = j.long("updatedAtUtcMs"),
    )

    private fun encode(v: MediaAsset): JSONObject = JSONObject()
        .put("id", v.id)
        .put("mimeType", v.mimeType)
        .put("width", v.width)
        .put("height", v.height)
        .put("byteSize", v.byteSize)
        .put("sha256", v.sha256)
        .put("createdAtUtcMs", v.createdAtUtcMs)
        .put("updatedAtUtcMs", v.updatedAtUtcMs)
    private fun decodeMediaAsset(j: JSONObject): MediaAsset = MediaAsset(
        id = j.string("id"),
        generationId = IMPORT_GENERATION,
        relativePath = MediaRules.relativePath(IMPORT_GENERATION, j.string("id")),
        mimeType = j.string("mimeType"),
        width = j.int("width"),
        height = j.int("height"),
        byteSize = j.long("byteSize"),
        sha256 = j.string("sha256"),
        createdAtUtcMs = j.long("createdAtUtcMs"),
        updatedAtUtcMs = j.long("updatedAtUtcMs"),
    )

    private fun encode(v: AppSettings): JSONObject = JSONObject()
        .put("id", v.id)
        .put("themeMode", v.themeMode.name)
        .put("lastSuccessfulExportAtUtcMs", v.lastSuccessfulExportAtUtcMs ?: JSONObject.NULL)
        .put("targetSleepMinutes", v.targetSleepMinutes ?: JSONObject.NULL)
        .put("targetWorkMinutes", v.targetWorkMinutes ?: JSONObject.NULL)
        .put("createdAtUtcMs", v.createdAtUtcMs)
        .put("updatedAtUtcMs", v.updatedAtUtcMs)
    private fun decodeAppSettings(j: JSONObject, version: Int): AppSettings = AppSettings(
        id = j.int("id"),
        themeMode = ThemeMode.valueOf(j.string("themeMode")),
        lastSuccessfulExportAtUtcMs = j.nullableLong("lastSuccessfulExportAtUtcMs"),
        targetWorkMinutes = if (version < 4) null else Rules.targetWorkMinutes(j.nullableInt("targetWorkMinutes")),
        targetSleepMinutes = Rules.targetSleepMinutes(if (version < 3 && !j.has("targetSleepMinutes")) null else j.nullableInt("targetSleepMinutes")),
        createdAtUtcMs = j.long("createdAtUtcMs"),
        updatedAtUtcMs = j.long("updatedAtUtcMs"),
    )

    fun encodeManifest(m: BackupManifest): JSONObject = JSONObject()
        .put("formatVersion", VERSION).put("appVersion", m.appVersion).put("exportedAtUtcMs", m.exportedAtUtcMs)
        .put("counts", JSONObject(m.counts)).put("files", JSONArray(m.files.map {
            JSONObject().put("path", it.path).put("byteSize", it.byteSize).put("sha256", it.sha256)
        }))
    fun decodeManifest(j: JSONObject): BackupManifest {
        requireBackup(j.int("formatVersion") in MIN_SUPPORTED_VERSION..VERSION, BackupError.UNSUPPORTED_VERSION)
        val counts = j.obj("counts")
        return BackupManifest(j.string("appVersion"), j.long("exportedAtUtcMs"),
            (listOf("meals", "sleeps", "exercises", "sessions", "items", "sets", "media").associateWith { counts.int(it) } +
                ("workRecords" to if (j.int("formatVersion") < 4) 0 else counts.int("workRecords"))),
            j.array("files").objects().map { FileDigest(it.string("path"), it.long("byteSize"), it.string("sha256")) })
    }

    /** Strict types, duplicate-key/depth rejection, malformed UTF-8 rejection, no lossy number coercion. */
    fun read(file: File): JSONObject {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        return file.inputStream().use { input ->
            JsonReader(java.io.InputStreamReader(input, decoder)).use { reader ->
                fun value(depth: Int): Any {
                    requireBackup(depth <= 32, BackupError.LIMIT)
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            val obj = JSONObject(); reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                requireBackup(!obj.has(key))
                                obj.put(key, value(depth + 1))
                            }
                            reader.endObject(); obj
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            val array = JSONArray(); reader.beginArray()
                            while (reader.hasNext()) array.put(value(depth + 1))
                            reader.endArray(); array
                        }
                        JsonToken.STRING -> reader.nextString()
                        JsonToken.NUMBER -> {
                            val number = reader.nextString()
                            requireBackup(number.matches(Regex("-?(0|[1-9][0-9]*)")))
                            number.toLongOrNull() ?: fail(BackupError.INVALID_DATA)
                        }
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                        else -> fail(BackupError.INVALID_DATA)
                    }
                }
                val json = value(0) as? JSONObject ?: fail(BackupError.INVALID_DATA)
                requireBackup(reader.peek() == JsonToken.END_DOCUMENT)
                json
            }
        }
    }
}

private fun JSONObject.required(key: String): Any { requireBackup(has(key)); return get(key) }
internal fun JSONObject.string(key: String): String = required(key) as? String ?: fail(BackupError.INVALID_DATA)
internal fun JSONObject.long(key: String): Long = when (val n = required(key)) {
    is Long -> n
    is Int -> n.toLong()
    else -> fail(BackupError.INVALID_DATA)
}
internal fun JSONObject.int(key: String): Int = long(key).also { requireBackup(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
internal fun JSONObject.boolean(key: String): Boolean = required(key) as? Boolean ?: fail(BackupError.INVALID_DATA)
internal fun JSONObject.obj(key: String): JSONObject = required(key) as? JSONObject ?: fail(BackupError.INVALID_DATA)
internal fun JSONObject.array(key: String): JSONArray = required(key) as? JSONArray ?: fail(BackupError.INVALID_DATA)
internal fun JSONObject.nullableString(key: String): String? = if (required(key) === JSONObject.NULL) null else string(key)
/** Optional in v2; absent v1 archives decode it as null instead of failing. */
internal fun JSONObject.optionalNullableString(key: String): String? =
    if (!has(key) || get(key) === JSONObject.NULL) null else string(key)
internal fun JSONObject.nullableLong(key: String): Long? = if (required(key) === JSONObject.NULL) null else long(key)
internal fun JSONObject.nullableInt(key: String): Int? = if (required(key) === JSONObject.NULL) null else int(key)
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { get(it) as? JSONObject ?: fail(BackupError.INVALID_DATA) }
