package com.personal.lifelog.feature.meal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.ActionButton

/** Fixed-size thumbnails avoid eagerly decoding nine full-screen images. Tap opens one image. */
@Composable
fun MealPhotoGallery(items: List<MealPhotoItem>, onRemove: ((String) -> Unit)? = null, enabled: Boolean = true) {
    var viewing by remember { mutableStateOf<String?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("meal-photo-gallery")) {
        itemsIndexed(items, key = { _, item -> item.photoId }) { index, item ->
            Column(Modifier.width(124.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Box(Modifier.size(124.dp).clickable(enabled = enabled) { viewing = item.photoId }.testTag("meal-photo-${item.photoId}"), contentAlignment = Alignment.Center) {
                        if (item.preview != null) AsyncImage(item.preview, stringResource(R.string.meal_photos_number, index + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(stringResource(R.string.meal_photo_missing), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(stringResource(if (index == 0) R.string.meal_photos_cover else R.string.meal_photos_number, index + 1), style = MaterialTheme.typography.labelSmall)
                if (onRemove != null) ActionButton({ onRemove(item.photoId) }, enabled = enabled, modifier = Modifier.testTag("meal-photo-remove-${item.photoId}")) {
                    Text(stringResource(R.string.meal_photo_remove))
                }
            }
        }
    }
    val selected = items.find { it.photoId == viewing }
    if (selected != null) Dialog(onDismissRequest = { viewing = null }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (selected.preview != null) AsyncImage(selected.preview, stringResource(R.string.meal_photo_alt), Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp), contentScale = ContentScale.Fit)
                else Text(stringResource(R.string.meal_photo_missing))
                ActionButton({ viewing = null }) { Text(stringResource(R.string.meal_photos_close)) }
            }
        }
    }
}
