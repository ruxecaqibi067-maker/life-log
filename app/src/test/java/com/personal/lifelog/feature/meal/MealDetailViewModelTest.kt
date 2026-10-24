package com.personal.lifelog.feature.meal

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoFixtures
import com.personal.lifelog.data.media.PhotoResult
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.domain.Satiety
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
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

/** Detail/delete read straight from Room and the photo service; no cached copy. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MealDetailViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-22T20:30:00Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var records: RecordRepository
    private lateinit var photos: PhotoStorage

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "t05-detail-${UUID.randomUUID()}").apply { mkdirs() }
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
            openSource = { ByteArrayInputStream(PhotoFixtures.jpeg()) },
            cameraUri = { file -> Uri.parse("content://synthetic-camera/${file.name}") },
            syncDirectory = { check(it.isDirectory) },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        root.deleteRecursively()
    }

    private suspend fun seed(
        text: String?,
        satiety: Satiety? = null,
        withPhoto: Boolean = false,
        satietyText: String? = null,
    ) =
        if (withPhoto) {
            val prepared = (photos.importPhoto(Uri.parse("content://synthetic/photo")) as PhotoResult.Success).value
            val saved = (
                photos.saveMeal(
                    MealDraft(RecordIds.newId(), clock.millis(), zone.id, text, satiety, satietyText = satietyText),
                    prepared.ticket,
                ) as PhotoResult.Success
                ).value
            photos.release(prepared.ticket)
            saved
        } else {
            (photos.saveMeal(
                MealDraft(RecordIds.newId(), clock.millis(), zone.id, text, satiety, satietyText = satietyText),
            ) as PhotoResult.Success).value
        }

    private suspend fun awaitLoaded(viewModel: MealDetailViewModel): MealDetailUiState =
        withTimeout(10_000) { viewModel.state.first { !it.loading && (it.meal != null || it.missing || it.loadError != null) } }

    @Test
    fun detailShowsTextSatietyAndPhotoFromCommittedState() = runBlocking<Unit> {
        val seeded = seed("牛肉面", Satiety.NINE, withPhoto = true)
        val viewModel = MealDetailViewModel(seeded.id, records, photos)
        val state = awaitLoaded(viewModel)

        assertEquals("牛肉面", state.meal?.text)
        assertEquals(Satiety.NINE, state.meal?.satiety)
        assertEquals(zone.id, state.meal?.zoneId)
        assertNotNull(state.photoUri)
        assertTrue(File(state.photoUri!!.path!!).isFile)
        assertFalse(state.photoMissing)
    }

    @Test
    fun notFullSatietyIsShownInDetail() = runBlocking<Unit> {
        val seeded = seed("下午加餐", Satiety.NOT_FULL)
        val viewModel = MealDetailViewModel(seeded.id, records, photos)
        val state = awaitLoaded(viewModel)

        assertEquals(Satiety.NOT_FULL, state.meal?.satiety)
        assertEquals("下午加餐", state.meal?.text)
    }

    @Test
    fun customSatietyDescriptionIsShownInDetail() = runBlocking<Unit> {
        val seeded = seed("下午加餐", Satiety.CUSTOM, satietyText = "七分饱但还想吃")
        val viewModel = MealDetailViewModel(seeded.id, records, photos)
        val state = awaitLoaded(viewModel)

        assertEquals(Satiety.CUSTOM, state.meal?.satiety)
        assertEquals("七分饱但还想吃", state.meal?.satietyText)
    }

    @Test
    fun purePhotoDetailHasNoFabricatedText() = runBlocking<Unit> {
        val seeded = seed(text = null, withPhoto = true)
        val viewModel = MealDetailViewModel(seeded.id, records, photos)
        val state = awaitLoaded(viewModel)

        assertNull(state.meal?.text)
        assertNotNull(state.photoUri)
    }

    @Test
    fun deleteRemovesTheRecordAndItsUnreferencedPhoto() = runBlocking<Unit> {
        val seeded = seed("待删除", withPhoto = true)
        val asset = requireNotNull(seeded.photoId?.let { db.metadata().media(it) })
        val file = File(root, asset.relativePath)
        assertTrue(file.isFile)

        val viewModel = MealDetailViewModel(seeded.id, records, photos)
        awaitLoaded(viewModel)
        viewModel.delete()
        withTimeout(10_000) { viewModel.state.first { it.deleted } }

        assertTrue(db.records().allMeals().isEmpty())
        assertNull(db.metadata().media(asset.id))
        assertFalse(file.exists())
    }

    @Test
    fun missingRecordIsReportedAsMissingNotAsEmptyContent() = runBlocking<Unit> {
        val viewModel = MealDetailViewModel(RecordIds.newId(), records, photos)
        val state = awaitLoaded(viewModel)
        assertTrue(state.missing)
        assertNull(state.meal)
    }
}
