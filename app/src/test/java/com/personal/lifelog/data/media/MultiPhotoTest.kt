package com.personal.lifelog.data.media

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import com.personal.lifelog.feature.meal.*
import java.io.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MultiPhotoTest {
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var records: RecordRepository
    private lateinit var photos: PhotoStorage
    private val clock = Clock.fixed(Instant.parse("2026-10-26T10:30:00.123Z"), ZoneOffset.UTC)
    private val uri = Uri.parse("content://synthetic/multi")
    private var fault: PhotoFaultPoint? = null
    private var cancelled = false
    private var source = byteArrayOf()
    private val models = mutableListOf<MealEditorViewModel>()
    private fun id() = RecordIds.newId()
    private fun <T> PhotoResult<T>.ok(): T = (this as? PhotoResult.Success)?.value ?: error("Unexpected $this")
    private fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("Unexpected $this")
    private fun draft(id: String = id(), ids: List<String>) = MealDraft(id,clock.millis(),"UTC",photoIds=ids)
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context=ApplicationProvider.getApplicationContext<Context>()
        root=File(context.filesDir,"mp-${id()}").apply { mkdirs() }
        db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addCallback(AppDatabase.creationCallback(clock)).build()
        val writes=WriteCoordinator();records=RecordRepository(db,writes,clock) { ZoneOffset.UTC }
        source=PhotoFixtures.jpeg()
        photos=PhotoStorage(context,db,records,writes,clock,io=Dispatchers.Unconfined,root=root,
            openSource={ ByteArrayInputStream(source) },cameraUri={ Uri.parse("content://synthetic-camera/"+it.name) },syncDirectory={},
            fault={ if (it==fault) { if(cancelled) throw CancellationException("synthetic") else throw IOException("synthetic") } })
    }
    @After fun close()=runBlocking<Unit> {
        models.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        db.close();root.deleteRecursively();Dispatchers.resetMain()
    }
    private suspend fun prepared()=photos.importPhoto(uri).ok()
    private suspend fun checkFiles(ids: List<String>) {
        for(id in ids) {
            val asset=db.metadata().media(id)!!
            val file=File(root,asset.relativePath)
            assertTrue(file.isFile);assertEquals(asset.sha256,PhotoNormalizer.sha256(file))
        }
    }
    @Test fun orderedRefs()=runBlocking<Unit> {
        val images=List(3) { prepared() };val ids=images.map { it.asset.id };val row=photos.saveMealPhotos(draft(ids=ids),images.map { it.ticket }).ok()
        images.forEach { photos.release(it.ticket).ok() };photos.maintain().ok()
        assertEquals(ids,records.mealPhotoIds(row.id));checkFiles(ids)
        assertEquals(ids.first(),row.photoId)
        val oldStyle=records.saveMeal(MealDraft(row.id,row.occurredAtUtcMs,row.zoneId,"only changed text",photoId=row.photoId)).ok()
        assertEquals(ids,records.mealPhotoIds(row.id));assertEquals("only changed text",oldStyle.text)
        val shared=records.saveMeal(MealDraft(id(),row.occurredAtUtcMs,row.zoneId,photoId=ids.last())).ok()
        val changed=listOf(ids[2],ids[1])
        photos.saveMealPhotos(draft(row.id,changed),emptyList()).ok();assertEquals(changed,records.mealPhotoIds(row.id));checkFiles(changed)
        assertNull(db.metadata().media(ids[0]));assertFalse(File(root,images[0].asset.relativePath).exists())
        photos.deleteMeal(row.id).ok();checkFiles(listOf(ids.last()));assertNull(db.metadata().media(ids[1]))
        photos.deleteMeal(shared.id).ok();assertTrue(db.metadata().allMedia().isEmpty());assertTrue(db.mealPhotos().all().isEmpty())
    }
    @Test fun failedTransactionAndCancel()=runBlocking<Unit> {
        val first=prepared();val old=photos.saveMealPhotos(draft(ids=listOf(first.asset.id)),listOf(first.ticket)).ok();photos.release(first.ticket).ok()
        val more=List(2) { prepared() };val ids=listOf(first.asset.id)+more.map { it.asset.id }
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_mp BEFORE INSERT ON MealPhoto WHEN NEW.sortOrder=1 BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertTrue(photos.saveMealPhotos(draft(old.id,ids),more.map { it.ticket }) is PhotoResult.Failure)
        assertEquals(old,records.meal(old.id));assertEquals(listOf(first.asset.id),records.mealPhotoIds(old.id));checkFiles(listOf(first.asset.id))
        more.forEach { photos.resumePrepared(it.ticket).ok() }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_mp")
        fault=PhotoFaultPoint.BEFORE_DATABASE;cancelled=true
        try { photos.saveMealPhotos(draft(old.id,ids),more.map { it.ticket });fail("Expected cancellation") } catch (_:CancellationException) {}
        assertEquals(listOf(first.asset.id),records.mealPhotoIds(old.id))
        fault=null;cancelled=false
        photos.saveMealPhotos(draft(old.id,ids),more.map { it.ticket }).ok();more.forEach { photos.release(it.ticket).ok() }
        assertEquals(ids,records.mealPhotoIds(old.id));checkFiles(ids)
        val bad=records.saveMealPhotos(draft(old.id,List(10) { id() }));assertTrue(bad is DataResult.Failure)
        assertTrue(records.saveMealPhotos(draft(old.id,listOf(ids[0],ids[0]))) is DataResult.Failure)
        assertEquals(ids,records.mealPhotoIds(old.id))
    }
    private fun model(id:String?=null,saved:SavedStateHandle=SavedStateHandle())=MealEditorViewModel(id,saved,records,photos,clock) { ZoneId.of("UTC") }.also { models+=it }
    private suspend fun ready(vm:MealEditorViewModel)=withTimeout(10_000) { vm.state.first { !it.loading } }
    @Test fun draftRestoreAndAppend()=runBlocking<Unit> {
        val saved=SavedStateHandle();val vm=model(saved=saved);ready(vm)
        vm.onPhotoPicked(uri);withTimeout(10_000) { vm.state.first { it.photoItems.size==1 } }
        vm.onPhotoPicked(uri);withTimeout(10_000) { vm.state.first { it.photoItems.size==2 } }
        val ids=vm.state.value.photoItems.map { it.photoId };assertTrue(db.records().allMeals().isEmpty())
        val recreated=model(saved=SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }));ready(recreated)
        assertEquals(ids,recreated.state.value.photoItems.map { it.photoId });assertTrue(recreated.state.value.photoItems.all { it.preview!=null })
        recreated.onPhotoPicked(null);assertEquals(ids,recreated.state.value.photoItems.map { it.photoId })
        source=byteArrayOf(1,2,3);recreated.onPhotoPicked(uri)
        withTimeout(10_000) { recreated.state.first { it.photoError!=null } };assertEquals(ids,recreated.state.value.photoItems.map { it.photoId })
        source=PhotoFixtures.jpeg();recreated.removePhoto(ids.first());withTimeout(10_000) { recreated.state.first { it.photoItems.size==1 } }
        fault=PhotoFaultPoint.BEFORE_DATABASE;recreated.save();withTimeout(10_000) { recreated.state.first { !it.saving&&it.error!=null } }
        assertEquals(listOf(ids.last()),recreated.state.value.photoItems.map { it.photoId });assertTrue(db.records().allMeals().isEmpty())
        fault=null;recreated.save();val retried=withTimeout(10_000) { recreated.state.first { !it.saving } };assertTrue(retried.toString(),retried.savedSuccessfully)
        val row=db.records().allMeals().single();assertEquals(listOf(ids.last()),records.mealPhotoIds(row.id))
        val edit=model(row.id);ready(edit);edit.onPhotoPicked(uri);withTimeout(10_000) { edit.state.first { it.photoItems.size==2 } }
        edit.discard();withTimeout(10_000) { edit.state.first { it.discarded } };assertEquals(listOf(ids.last()),records.mealPhotoIds(row.id));checkFiles(listOf(ids.last()))
    }
    @Test fun cameraAndNineLimit()=runBlocking<Unit> {
        val vm=model();ready(vm)
        vm.beginCapture();val request=withTimeout(10_000) {vm.state.first {it.captureRequest!=null}}.captureRequest!!
        PhotoFiles(root).capture(request.ticket).writeBytes(source)
        vm.onCaptureRequestLaunched();vm.onCaptureResult(true)
        withTimeout(10_000) {vm.state.first {it.photoItems.size==1}}
        val first=vm.state.value.photoItems.single().photoId
        vm.beginCapture();withTimeout(10_000) {vm.state.first {it.captureRequest!=null}}
        vm.onCaptureRequestLaunched();vm.onCaptureResult(false)
        withTimeout(10_000) {vm.state.first {it.captureTicket==null}}
        assertEquals(first,vm.state.value.photoItems.single().photoId)
        vm.beginCapture();withTimeout(10_000) {vm.state.first {it.captureRequest!=null}}
        vm.onCaptureRequestLaunched();vm.onCaptureResult(true)
        withTimeout(10_000) {vm.state.first {it.photoError!=null}}
        vm.cancelPendingCapture();withTimeout(10_000) {vm.state.first {it.captureTicket==null}}
        assertEquals(first,vm.state.value.photoItems.single().photoId)
        for(size in 2..9) {vm.onPhotoPicked(uri);withTimeout(10_000) {vm.state.first {it.photoItems.size==size}}}
        vm.onPhotoPicked(uri);assertEquals(MealUiError.PHOTO_LIMIT,vm.state.value.photoError);assertEquals(9,vm.state.value.photoItems.size)
        vm.beginCapture();assertNull(vm.state.value.captureTicket)
        vm.save();withTimeout(10_000) {vm.state.first {it.savedSuccessfully}}
        assertEquals(9,records.mealPhotoIds(db.records().allMeals().single().id).size)
        checkFiles(vm.state.value.photoItems.map {it.photoId})
    }

}
