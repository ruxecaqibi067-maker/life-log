package com.personal.lifelog.data.backup

import android.app.Application
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.domain.Satiety
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.*
import java.time.*
import java.util.zip.*

internal fun zipEntries(zip: File): LinkedHashMap<String, ByteArray> = ZipFile(zip).use { z ->
    linkedMapOf<String, ByteArray>().also { result -> z.entries().asSequence().forEach { result[it.name] = z.getInputStream(it).readBytes() } }
}
internal fun writeZip(file: File, entries: Map<String, ByteArray>): File = file.also {
    ZipOutputStream(it.outputStream()).use { out -> entries.forEach { (path, data) ->
        out.putNextEntry(ZipEntry(path)); out.write(data); out.closeEntry()
    } }
}
internal suspend fun editData(zip: File, target: File, edit: (JSONObject) -> Unit): File {
    val entries = zipEntries(zip)
    val data = JSONObject(String(entries.getValue("data.json"), Charsets.UTF_8)); edit(data)
    entries["data.json"] = data.toString().toByteArray()
    val temp = File(target.parentFile, "hash-${id()}").apply { writeBytes(entries.getValue("data.json")) }
    val hash = digest(temp, "data.json"); temp.delete()
    val manifest = JSONObject(String(entries.getValue("manifest.json"), Charsets.UTF_8))
    val list = manifest.getJSONArray("files")
    for (i in 0 until list.length()) if (list.getJSONObject(i).getString("path") == "data.json") {
        list.getJSONObject(i).put("byteSize", hash.byteSize).put("sha256", hash.sha256)
    }
    entries["manifest.json"] = manifest.toString().toByteArray()
    return writeZip(target, entries)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupImportTest {
    private lateinit var f: BackupFixture
    @Before fun before() { f = BackupFixture() }
    @After fun after() { f.close() }
    private fun importer(limits: BackupLimits = BackupLimits(), clock: Clock = f.clock) = BackupImporter(f.root, clock, BackupArchive(limits))
    private fun source(file: File) = BackupSource { file.inputStream() }
    private suspend fun mediaDigest() = f.snapshots.read().media.map { digest(BackupFiles(f.root).file(it.relativePath)) }

    @Test fun previewCancelAndAllFailuresLeaveDatabaseAndFormalPhotosUnchanged() = runBlocking {
        val before = f.seed(targetSleepMinutes = 480); val photos = mediaDigest(); val zip = f.zip(); val service = importer()
        val prepared = (service.preflight(source(zip)) as BackupResult.Success).value
        assertEquals(1, prepared.preview.counts["meals"]); assertEquals(3, prepared.preview.counts["sets"])
        assertTrue(prepared.preview.hasActiveSleep); assertTrue(prepared.preview.replacesAllRecordsAndSettings)
        assertEquals(f.clock.millis(), prepared.preview.earliestUtcMs)
        assertTrue(service.discard(prepared.token) is BackupResult.Success)
        assertEquals(before, f.snapshots.read()); assertEquals(photos, mediaDigest())
        assertEquals(0, File(f.root, "backup-import").listFiles()!!.size)
    }

    @Test fun invalidDataMatrixIsRejectedWithoutWrites() = runBlocking {
        val before = f.seed(); val photos = mediaDigest(); val zip = f.zip()
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("dataVersion", BackupJson.VERSION + 1) },
            { it.getJSONArray("meals").getJSONObject(0).put("photoId", id()) },
            { it.getJSONArray("meals").put(it.getJSONArray("meals").getJSONObject(0)) },
            { it.getJSONArray("sets").getJSONObject(0).put("repetitions", 0) },
            { it.getJSONArray("sets").getJSONObject(0).put("sortOrder", 99) },
            { it.getJSONArray("items").getJSONObject(0).put("exerciseId", id()) },
            { it.getJSONArray("items").getJSONObject(0).put("typeSnapshot", "ALIEN") },
            { it.getJSONArray("meals").getJSONObject(0).put("localDate", "2026-01-01") },
            { it.getJSONArray("meals").getJSONObject(0).put("satiety", "ALIEN") },
            { it.getJSONArray("meals").getJSONObject(0).put("satiety", "CUSTOM") },
            { it.getJSONArray("meals").getJSONObject(0).put("satietyText", "预设值不能带描述") },
            {
                it.getJSONArray("meals").getJSONObject(0)
                    .put("satiety", "CUSTOM")
                    .put("satietyText", "字".repeat(41))
            },
            { it.getJSONArray("sleeps").getJSONObject(0).put("endZoneId", "UTC") },
            { it.getJSONObject("settings").remove("themeMode") },
            { it.getJSONArray("exercises").getJSONObject(0).put("isHidden", "false") },
            { it.getJSONArray("sets").getJSONObject(0).put("weightGrams", 1.5) },
        )
        for ((i, edit) in edits.withIndex()) {
            val bad = editData(zip, File(f.root, "bad-$i.zip"), edit)
            assertTrue("case $i", importer().preflight(source(bad)) is BackupResult.Failure)
            assertEquals(before, f.snapshots.read()); assertEquals(photos, mediaDigest())
            assertEquals(0, File(f.root, "backup-import").listFiles()!!.size)
        }
    }

    @Test fun notFullSatietyIsAcceptedAndLegacyValuesStillRead() = runBlocking {
        val seeded = f.seed()
        val mealId = seeded.meals.single().id
        val zip = f.zip()
        val newValue = editData(zip, File(f.root, "not-full.zip")) { data ->
            data.getJSONArray("meals").getJSONObject(0).put("satiety", "NOT_FULL")
        }

        // The new option is valid data and keeps its meaning through JSON decoding.
        val checked = BackupArchive().verify(newValue, BackupFiles(f.root).mkdir("verify-not-full"))
        assertEquals(Satiety.NOT_FULL, checked.data.meals.single { it.id == mealId }.satiety)
        val service = importer()
        val ready = service.preflight(source(newValue)) as BackupResult.Success
        service.discard(ready.value.token)

        // A backup written before this option existed still decodes its original name.
        val legacyChecked = BackupArchive().verify(zip, BackupFiles(f.root).mkdir("verify-legacy"))
        assertEquals(Satiety.SEVEN, legacyChecked.data.meals.single { it.id == mealId }.satiety)
        val legacy = importer()
        val legacyReady = legacy.preflight(source(zip)) as BackupResult.Success
        legacy.discard(legacyReady.value.token)
        Unit
    }

    @Test fun customSatietyTextRoundTripsAndVersionOneArchivesStillRead() = runBlocking {
        val seeded = f.seed()
        val mealId = seeded.meals.single().id
        val zip = f.zip()

        val customZip = editData(zip, File(f.root, "custom.zip")) { data ->
            data.getJSONArray("meals").getJSONObject(0)
                .put("satiety", "CUSTOM")
                .put("satietyText", "七分饱但还想吃")
        }
        val custom = BackupArchive().verify(customZip, BackupFiles(f.root).mkdir("verify-custom")).data
            .meals.single { it.id == mealId }
        assertEquals(Satiety.CUSTOM, custom.satiety)
        assertEquals("七分饱但还想吃", custom.satietyText)
        val service = importer()
        val ready = service.preflight(source(customZip)) as BackupResult.Success
        service.discard(ready.value.token)

        // A v1 archive has no satietyText field and must decode to null, not fail.
        val v1Data = editData(zip, File(f.root, "v1-data.zip")) { data ->
            data.put("dataVersion", 1)
            val meals = data.getJSONArray("meals")
            for (i in 0 until meals.length()) meals.getJSONObject(i).remove("satietyText")
        }
        val entries = zipEntries(v1Data)
        entries["manifest.json"] = JSONObject(String(entries.getValue("manifest.json"), Charsets.UTF_8))
            .put("formatVersion", 1)
            .toString()
            .toByteArray()
        val v1Zip = writeZip(File(f.root, "v1.zip"), entries)
        val legacy = BackupArchive().verify(v1Zip, BackupFiles(f.root).mkdir("verify-v1")).data
            .meals.single { it.id == mealId }
        assertEquals(Satiety.SEVEN, legacy.satiety)
        assertNull(legacy.satietyText)
        val legacyService = importer()
        val legacyReady = legacyService.preflight(source(v1Zip)) as BackupResult.Success
        legacyService.discard(legacyReady.value.token)
        Unit
    }

    @Test fun hashesMissingPhotosVersionsAndGarbageAreRejected() = runBlocking {
        val before = f.seed(); val zip = f.zip(); val original = zipEntries(zip)
        val variants = listOf(
            original.toMutableMap().apply { this["data.json"] = "{}".toByteArray() },
            original.toMutableMap().apply { remove(keys.first { it.startsWith("media/") }) },
            original.toMutableMap().apply { this["manifest.json"] = JSONObject(String(getValue("manifest.json"))).put("formatVersion", 77).toString().toByteArray() },
            original.toMutableMap().apply { this["extra.txt"] = byteArrayOf(1) },
        )
        variants.forEachIndexed { i, entries ->
            assertTrue(importer().preflight(source(writeZip(File(f.root, "corrupt-$i.zip"), entries))) is BackupResult.Failure)
            assertEquals(before, f.snapshots.read())
        }
        assertTrue(importer().preflight(BackupSource { ByteArrayInputStream(byteArrayOf(1, 2)) }) is BackupResult.Failure)
    }

    @Test fun traversalAbsolutePathsSymlinksAndDuplicateEntriesAreRejected() = runBlocking {
        val before = f.seed(); val zip = f.zip()
        for (path in listOf("../escape", "/absolute.jpg", "C:/drive.jpg", "media\\evil.jpg", "media/../evil", "media//evil", "./data.json")) {
            val entries = zipEntries(zip).apply { this[path] = byteArrayOf(1) }
            assertTrue(path, importer().preflight(source(writeZip(File(f.root, "path-${id()}.zip"), entries))) is BackupResult.Failure)
        }
        val duplicate = writeZip(File(f.root, "duplicate.zip"), linkedMapOf("data.json" to byteArrayOf(1), "dota.json" to byteArrayOf(2)))
        duplicate.writeBytes(duplicate.readBytes().let { bytes ->
            for (i in 0..bytes.size - 9) if (String(bytes, i, 9, Charsets.US_ASCII) == "dota.json") "data.json".toByteArray().copyInto(bytes, i)
            bytes
        })
        assertTrue(importer().preflight(source(duplicate)) is BackupResult.Failure)
        val symlink = f.zip(); val bytes = symlink.readBytes()
        for (i in 0..bytes.size - 46) if (bytes[i] == 0x50.toByte() && bytes[i+1] == 0x4b.toByte() && bytes[i+2] == 1.toByte() && bytes[i+3] == 2.toByte()) {
            bytes[i + 41] = 0xa0.toByte(); break
        }
        symlink.writeBytes(bytes)
        assertTrue(importer().preflight(source(symlink)) is BackupResult.Failure)
        assertFalse(File(f.root.parentFile, "escape").exists()); assertEquals(before, f.snapshots.read())
    }

    @Test fun actualStreamLimitsAtNMinusOneNAndNPlusOne() = runBlocking {
        for (n in listOf(31, 32, 33)) {
            try {
                assertEquals(n.toLong(), copyBounded(ByteArrayInputStream(ByteArray(n)), ByteArrayOutputStream(), 32))
                assertTrue(n <= 32)
            } catch (e: BackupException) { assertEquals(33, n); assertEquals(BackupError.LIMIT, e.code) }
        }
        f.seed(); val zip = f.zip(); val before = f.snapshots.read()
        for (limit in listOf(zip.length() - 1, zip.length(), zip.length() + 1)) {
            val service = importer(BackupLimits(zipBytes = limit))
            val result = service.preflight(source(zip))
            assertEquals(limit >= zip.length(), result is BackupResult.Success)
            if (result is BackupResult.Success) service.discard(result.value.token)
        }
        val bomb = writeZip(File(f.root, "bomb.zip"), linkedMapOf("data.json" to ByteArray(10000)))
        assertEquals(BackupError.LIMIT, (importer(BackupLimits(dataBytes = 100)).preflight(source(bomb)) as BackupResult.Failure).error)
        assertTrue(importer(BackupLimits(entries = 2)).preflight(source(zip)) is BackupResult.Failure)
        assertTrue(importer(BackupLimits(expandedBytes = 100)).preflight(source(zip)) is BackupResult.Failure)
        assertEquals(before, f.snapshots.read())
    }

    @Test fun stagedSourceTamperingAndTokenExpiryRequireNewPreflight() = runBlocking {
        f.seed(); val zip = f.zip(); val service = importer()
        val ready = (service.preflight(source(zip)) as BackupResult.Success).value
        File(f.root, "backup-import/${ready.token.id}/source.zip").appendBytes(byteArrayOf(1))
        try { service.withValidated(ready.token) { }; fail() } catch (e: BackupException) { assertEquals(BackupError.HASH_MISMATCH, e.code) }
        service.discard(ready.token)
        try { service.withValidated(ready.token) { }; fail() } catch (e: BackupException) { assertEquals(BackupError.EXPIRED_TOKEN, e.code) }
        var current = f.clock.instant()
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId) = this
            override fun instant() = current
        }
        val expiring = importer(clock = clock)
        val prepared = (expiring.preflight(source(zip)) as BackupResult.Success).value
        current = current.plusMillis(BackupImporter.TOKEN_LIFETIME_MS)
        try { expiring.withValidated(prepared.token) { }; fail() } catch (e: BackupException) { assertEquals(BackupError.EXPIRED_TOKEN, e.code) }
    }

    @Test fun cancellationDuringReadCleansStagingAndLeavesOriginals() = runBlocking {
        val before = f.seed()
        val source = BackupSource { object : InputStream() { override fun read(): Int { throw CancellationException() } } }
        try { importer().preflight(source); fail() } catch (_: CancellationException) { }
        assertEquals(before, f.snapshots.read()); assertEquals(0, File(f.root, "backup-import").listFiles()!!.size)
    }

    @Test fun strictJsonAndOptionalFieldsAndPhotoSizeLimits() = runBlocking {
        f.seed(); val zip = f.zip()
        val future = editData(zip, File(f.root, "optional.zip")) { it.put("futureOptional", "ignored") }
        val service = importer()
        val ready = service.preflight(source(future)) as BackupResult.Success
        service.discard(ready.value.token)
        val json = File(f.root, "strict.json")
        for (text in listOf("{\"a\":1,\"a\":2}", "{\"a\":1.5}", "{\"a\":9223372036854775808}", "{\"a\":1} {}")) {
            json.writeText(text)
            assertThrows(Exception::class.java) { BackupJson.read(json) }
        }
        json.writeBytes(byteArrayOf(0x7b, 0x22, 0xc0.toByte(), 0x22, 0x3a, 0x31, 0x7d))
        assertThrows(Exception::class.java) { BackupJson.read(json) }
        val photoSize = f.snapshots.read().media.single().byteSize
        assertTrue(importer(BackupLimits(photoBytes = photoSize - 1)).preflight(source(zip)) is BackupResult.Failure)
        val entryCount = zipEntries(zip).size
        for (n in listOf(entryCount - 1, entryCount, entryCount + 1)) {
            val check = importer(BackupLimits(entries = n)); val result = check.preflight(source(zip))
            assertEquals(n >= entryCount, result is BackupResult.Success)
            if (result is BackupResult.Success) check.discard(result.value.token)
        }
    }
}
