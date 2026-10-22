package com.personal.lifelog.feature.meal

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.media.PhotoFaultPoint
import com.personal.lifelog.data.media.PhotoFixtures
import com.personal.lifelog.data.media.PhotoResult
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.media.PhotoTicket
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.domain.Satiety
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * Host-side meal editor tests against real Room and the real PhotoStorage file
 * lifecycle. Graphs are synthetic; no personal photo or location data is used.
 * Device-only paths (system camera/photo picker UI) are recorded as unexecuted
 * in the T05 handoff and are not claimed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MealEditorViewModelTest {

    private class TestClock(var now: Long = Instant.parse("2026-10-22T20:30:00Z").toEpochMilli()) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val clock = TestClock()
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var records: RecordRepository
    private lateinit var photos: PhotoStorage
    private val photoUri = Uri.parse("content://synthetic-gallery/meal")
    private var captureFile: File? = null
    private var failNextDatabase = false
    private var saveGate: CompletableDeferred<Unit>? = null
    private var photoBytes = byteArrayOf()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "t05-${UUID.randomUUID()}").apply { mkdirs() }
        photoBytes = PhotoFixtures.jpeg()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock))
            .build()
        val writes = WriteCoordinator()
        records = RecordRepository(db, writes, clock) { zone }
        photos = PhotoStorage(
            context = context,
            db = db,
            records = records,
            writes = writes,
            clock = clock,
            io = Dispatchers.Unconfined,
            root = root,
            openSource = { ByteArrayInputStream(photoBytes) },
            cameraUri = { file ->
                captureFile = file
                Uri.parse("content://synthetic-camera/${file.name}")
            },
            syncDirectory = { check(it.isDirectory) },
            fault = { point ->
                saveGate?.let { gate ->
                    if (point == PhotoFaultPoint.BEFORE_DATABASE) gate.await()
                }
                if (point == PhotoFaultPoint.BEFORE_DATABASE && failNextDatabase) {
                    failNextDatabase = false
                    throw IOException("injected database failure")
                }
            },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        root.deleteRecursively()
    }

    private fun editor(mealId: String? = null, handle: SavedStateHandle = SavedStateHandle()) =
        MealEditorViewModel(mealId, handle, records, photos, clock) { zone }

    private suspend fun awaitLoaded(viewModel: MealEditorViewModel): MealEditorUiState =
        withTimeout(10_000) { viewModel.state.first { !it.loading } }

    private suspend fun awaitSaved(viewModel: MealEditorViewModel): MealEditorUiState =
        withTimeout(10_000) { viewModel.state.first { it.savedSuccessfully } }

    private suspend fun awaitReady(viewModel: MealEditorViewModel): MealEditorUiState =
        withTimeout(10_000) { viewModel.state.first { it.photoStatus == MealPhotoStatus.READY } }

    private suspend fun singleMeal(): MealRecord = db.records().allMeals().single()

    private fun formalFile(asset: MediaAsset): File = File(root, asset.relativePath)

    private fun stagingDir(ticket: PhotoTicket): File = File(root, "photo-staging/${ticket.id}")

    private suspend fun seedMeal(text: String? = "旧文字", withPhoto: Boolean = false): MealRecord {
        val draft = MealDraft(
            id = RecordIds.newId(),
            occurredAtUtcMs = clock.millis(),
            zoneId = zone.id,
            text = text,
        )
        if (!withPhoto) {
            return (photos.saveMeal(draft) as PhotoResult.Success).value
        }
        val prepared = (photos.importPhoto(photoUri) as PhotoResult.Success).value
        val saved = (photos.saveMeal(draft, prepared.ticket) as PhotoResult.Success).value
        photos.release(prepared.ticket)
        return saved
    }

    @Test
    fun pureTextMealSavesWithTrimmedTextAndInjectedClockZone() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.onTextChanged("  牛肉面，加一个蛋  ")
        vm.save()
        awaitSaved(vm)

        val meal = singleMeal()
        assertEquals("牛肉面，加一个蛋", meal.text)
        assertEquals("2026-10-23", meal.localDate)
        assertEquals(zone.id, meal.zoneId)
        assertEquals(null, meal.satiety)
        assertEquals(null, meal.photoId)
        assertEquals(0, db.metadata().allMedia().size)
    }

    @Test
    fun emptyOrSatietyOnlyMealIsRejectedWithoutWriting() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)

        vm.save()
        assertEquals(MealUiError.EMPTY_MEAL, vm.state.value.error)
        assertTrue(db.records().allMeals().isEmpty())

        vm.toggleSatiety(Satiety.FIVE)
        vm.save()
        assertEquals(MealUiError.EMPTY_MEAL, vm.state.value.error)
        assertTrue(db.records().allMeals().isEmpty())

        vm.onTextChanged("   \n  ")
        vm.save()
        assertEquals(MealUiError.EMPTY_MEAL, vm.state.value.error)
        assertTrue(db.records().allMeals().isEmpty())
    }

    @Test
    fun textOverFiveHundredCharactersIsRejectedAndPreserved() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        val longText = "字".repeat(501)
        vm.onTextChanged(longText)
        vm.save()

        assertEquals(MealUiError.TEXT_TOO_LONG, vm.state.value.error)
        assertEquals(longText, vm.state.value.text)
        assertTrue(db.records().allMeals().isEmpty())
    }

    @Test
    fun photoAloneIsACompleteMealAndKeepsItsFormalFile() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.onPhotoPicked(photoUri)
        val ready = awaitReady(vm)
        val ticket = requireNotNull(ready.newPhotoTicket)
        assertTrue(stagingDir(ticket).isDirectory)

        vm.save()
        awaitSaved(vm)

        val meal = singleMeal()
        assertEquals(null, meal.text)
        val asset = requireNotNull(meal.photoId?.let { db.metadata().media(it) })
        assertTrue(formalFile(asset).isFile)
        assertEquals(asset.byteSize, formalFile(asset).length())
        assertFalse(stagingDir(ticket).exists())
    }

    @Test
    fun satietyIsOptionalAndOnlyPersistsWhenChosen() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.onTextChanged("酸奶")
        vm.save()
        awaitSaved(vm)
        assertNull(singleMeal().satiety)

        val chosen = editor()
        awaitLoaded(chosen)
        chosen.onTextChanged("牛肉面")
        chosen.toggleSatiety(Satiety.SEVEN)
        chosen.save()
        awaitSaved(chosen)
        assertEquals(Satiety.SEVEN, db.records().allMeals().first { it.text == "牛肉面" }.satiety)
    }

    @Test
    fun backfilledDateAndTimeAreStoredInTheRecordZone() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.onTextChanged("补录午餐")
        vm.onDateChanged(LocalDate.of(2026, 10, 20))
        vm.onTimeChanged(LocalTime.of(13, 5))
        vm.save()
        awaitSaved(vm)

        val meal = singleMeal()
        assertEquals("2026-10-20", meal.localDate)
        assertEquals(
            RecordTime.resolve(LocalDateTime.of(2026, 10, 20, 13, 5), zone.id),
            meal.occurredAtUtcMs,
        )
        assertEquals(zone.id, meal.zoneId)
    }

    @Test
    fun editKeepsIdAndCreatedAtAndUpdatesTheSameRecord() = runBlocking<Unit> {
        val seeded = seedMeal(text = "旧文字")
        val vm = editor(seeded.id)
        val loaded = awaitLoaded(vm)
        assertEquals("旧文字", loaded.text)
        assertFalse(loaded.dirty)

        vm.onTextChanged("新文字")
        vm.save()
        awaitSaved(vm)

        val meal = singleMeal()
        assertEquals(seeded.id, meal.id)
        assertEquals("新文字", meal.text)
        assertEquals(seeded.createdAtUtcMs, meal.createdAtUtcMs)
        assertTrue(meal.updatedAtUtcMs >= seeded.updatedAtUtcMs)
    }

    @Test
    fun replacingPhotoSwitchesTheReferenceAndCleansTheOldFile() = runBlocking<Unit> {
        val seeded = seedMeal(text = "带照片", withPhoto = true)
        val oldAsset = requireNotNull(seeded.photoId?.let { db.metadata().media(it) })
        assertTrue(formalFile(oldAsset).isFile)

        val vm = editor(seeded.id)
        awaitLoaded(vm)
        vm.onPhotoPicked(photoUri)
        awaitReady(vm)
        vm.save()
        awaitSaved(vm)

        val meal = singleMeal()
        assertNotEquals(oldAsset.id, meal.photoId)
        assertNull(db.metadata().media(oldAsset.id))
        assertFalse(formalFile(oldAsset).exists())
        val newAsset = requireNotNull(meal.photoId?.let { db.metadata().media(it) })
        assertTrue(formalFile(newAsset).isFile)
    }

    @Test
    fun removingPhotoOnEditClearsTheReferenceAndCleansTheFile() = runBlocking<Unit> {
        val seeded = seedMeal(text = "带照片", withPhoto = true)
        val oldAsset = requireNotNull(seeded.photoId?.let { db.metadata().media(it) })

        val vm = editor(seeded.id)
        awaitLoaded(vm)
        vm.removePhoto()
        withTimeout(10_000) { vm.state.first { it.photoRemoved } }
        vm.save()
        awaitSaved(vm)

        val meal = singleMeal()
        assertNull(meal.photoId)
        assertNull(db.metadata().media(oldAsset.id))
        assertFalse(formalFile(oldAsset).exists())
    }

    @Test
    fun pickerCancelLeavesTextAndPhotoAloneAndWritesNothing() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.onTextChanged("保留文字")
        vm.onPhotoPicked(null)

        assertEquals("保留文字", vm.state.value.text)
        assertEquals(MealPhotoStatus.NONE, vm.state.value.photoStatus)
        assertTrue(db.records().allMeals().isEmpty())
    }

    @Test
    fun explicitDiscardReleasesThePreparedTicketAndCreatesNoRecord() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.onTextChanged("不要了")
        vm.onPhotoPicked(photoUri)
        val ticket = requireNotNull(awaitReady(vm).newPhotoTicket)

        vm.discard()
        withTimeout(10_000) { vm.state.first { it.discarded } }

        assertTrue(db.records().allMeals().isEmpty())
        assertFalse(stagingDir(ticket).exists())
    }

    @Test
    fun failedSaveKeepsTextAndTicketThenRetrySucceeds() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.onTextChanged("失败后重试")
        vm.onPhotoPicked(photoUri)
        val ticket = requireNotNull(awaitReady(vm).newPhotoTicket)
        failNextDatabase = true

        vm.save()
        val failed = withTimeout(10_000) { vm.state.first { !it.saving && it.error != null } }
        assertEquals(MealUiError.STORAGE, failed.error)
        assertEquals("失败后重试", failed.text)
        assertEquals(ticket, failed.newPhotoTicket)
        assertTrue(db.records().allMeals().isEmpty())

        vm.save()
        awaitSaved(vm)
        val meal = singleMeal()
        assertEquals("失败后重试", meal.text)
        assertNotNull(meal.photoId)
    }

    @Test
    fun secondSaveWhileTheFirstIsInFlightIsIgnored() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.onTextChanged("只保存一次")
        val gate = CompletableDeferred<Unit>()
        saveGate = gate

        vm.save()
        withTimeout(10_000) { vm.state.first { it.saving } }
        vm.save()
        gate.complete(Unit)
        awaitSaved(vm)

        assertEquals(1, db.records().allMeals().size)
        assertEquals("只保存一次", singleMeal().text)
    }

    @Test
    fun draftAndPreparedTicketSurviveSavedStateHandleRecreation() = runBlocking<Unit> {
        val handle = SavedStateHandle()
        val first = editor(handle = handle)
        awaitLoaded(first)
        first.onTextChanged("重建后继续")
        first.toggleSatiety(Satiety.NINE)
        first.onPhotoPicked(photoUri)
        val originalTicket = requireNotNull(awaitReady(first).newPhotoTicket)

        // Same SavedStateHandle + same photo service = process/activity recreation.
        val restored = editor(handle = handle)
        val loaded = awaitLoaded(restored)
        assertEquals("重建后继续", loaded.text)
        assertEquals(Satiety.NINE, loaded.satiety)
        assertEquals(originalTicket, loaded.newPhotoTicket)
        val ready = awaitReady(restored)
        assertNotNull(ready.photoPreview)

        restored.save()
        awaitSaved(restored)
        val meal = singleMeal()
        assertEquals("重建后继续", meal.text)
        assertEquals(Satiety.NINE, meal.satiety)
        assertNotNull(meal.photoId)
    }

    @Test
    fun cameraCancelCreatesNoRecordAndRemovesTheCaptureFile() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.beginCapture()
        withTimeout(10_000) { vm.state.first { it.captureRequest != null } }
        val file = requireNotNull(captureFile)
        file.writeBytes(photoBytes)
        vm.onCaptureRequestLaunched()

        vm.onCaptureResult(false)
        withTimeout(10_000) { vm.state.first { it.captureTicket == null } }

        assertTrue(db.records().allMeals().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun cameraSuccessProducesAPhotoOnlyMeal() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.beginCapture()
        withTimeout(10_000) { vm.state.first { it.captureRequest != null } }
        val file = requireNotNull(captureFile)
        file.writeBytes(photoBytes)
        vm.onCaptureRequestLaunched()

        vm.onCaptureResult(true)
        val ready = awaitReady(vm)
        assertNotNull(ready.newPhotoTicket)
        vm.save()
        awaitSaved(vm)

        val meal = singleMeal()
        assertNull(meal.text)
        assertNotNull(meal.photoId)
        assertTrue(formalFile(requireNotNull(meal.photoId?.let { db.metadata().media(it) })).isFile)
    }

    @Test
    fun cameraLaunchFailureReleasesTheTicketAndKeepsTheDraft() = runBlocking<Unit> {
        val vm = editor()
        awaitLoaded(vm)
        vm.onTextChanged("相机不可用")
        vm.beginCapture()
        withTimeout(10_000) { vm.state.first { it.captureRequest != null } }
        val ticket = requireNotNull(vm.state.value.captureTicket)
        vm.onCaptureRequestLaunched()

        vm.onCaptureLaunchFailed()
        withTimeout(10_000) { vm.state.first { it.captureTicket == null } }

        assertEquals("相机不可用", vm.state.value.text)
        assertEquals(MealUiError.SOURCE_UNAVAILABLE, vm.state.value.photoError)
        assertFalse(stagingDir(ticket).exists())
        assertTrue(db.records().allMeals().isEmpty())
    }
}
