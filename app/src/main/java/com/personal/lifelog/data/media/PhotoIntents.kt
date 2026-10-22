package com.personal.lifelog.data.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** System picker when available; explicit OPEN_DOCUMENT fallback, without a Play Services install. */
class PickMealPhoto : ActivityResultContract<Unit, Uri?>() {
    private val picker = ActivityResultContracts.PickVisualMedia()

    override fun createIntent(context: Context, input: Unit): Intent =
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            picker.createIntent(context, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = picker.parseResult(resultCode, intent)
}

/** TakePicture plus explicit ClipData grants for system/vendor camera compatibility. */
class TakeMealPhoto : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        clipData = ClipData.newRawUri("meal-photo", input)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
