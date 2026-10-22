package com.personal.lifelog.data.media

import android.net.Uri
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.domain.DataError

/** Persist only this ID in SavedStateHandle; never persist a gallery URI as a saved photo. */
data class PhotoTicket(val id: String)
data class CaptureRequest(val ticket: PhotoTicket, val uri: Uri)
data class PreparedPhoto(val ticket: PhotoTicket, val asset: MediaAsset)

enum class PhotoError {
    INVALID_IMAGE, INPUT_TOO_LARGE, PIXEL_LIMIT, OUTPUT_TOO_LARGE, RESOURCE_LIMIT,
    SOURCE_UNAVAILABLE, STORAGE_FULL, IO, INVALID_TICKET, MISSING_PHOTO,
    RECOVERY_REQUIRED, DATABASE,
}

sealed interface PhotoResult<out T> {
    data class Success<T>(val value: T) : PhotoResult<T>
    data class Failure(val error: PhotoError, val dataError: DataError? = null) : PhotoResult<Nothing>
    data object Cancelled : PhotoResult<Nothing>
}

data class PhotoCleanup(val deletedFiles: Int, val deletedMediaRows: Int, val expiredTickets: Int)

internal class PhotoException(val error: PhotoError) : java.io.IOException(error.name)
