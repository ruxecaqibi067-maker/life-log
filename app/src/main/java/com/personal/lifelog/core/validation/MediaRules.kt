package com.personal.lifelog.core.validation

import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.reject

object MediaRules {
    fun relativePath(generationId: String, photoId: String): String =
        "media/${Rules.id(generationId)}/${Rules.id(photoId)}.jpg"

    /** Metadata only; T04 must finish and verify the private file before supplying this object. */
    fun validate(asset: MediaAsset) {
        if (asset.relativePath != relativePath(asset.generationId, asset.id) ||
            asset.mimeType != "image/jpeg" || asset.width <= 0 || asset.height <= 0 ||
            asset.width.toLong() * asset.height > 100_000_000L ||
            asset.byteSize !in 1..Rules.PHOTO_BYTES_LIMIT ||
            !asset.sha256.matches(Regex("[a-f0-9]{64}")) || asset.updatedAtUtcMs < asset.createdAtUtcMs
        ) reject(ErrorCode.INVALID_MEDIA)
    }
}
