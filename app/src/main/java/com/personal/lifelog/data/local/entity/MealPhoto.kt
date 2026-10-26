package com.personal.lifelog.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Ordered photos of one meal. photoId on MealRecord remains the first-photo compatibility pointer. */
@Entity(tableName = "MealPhoto", primaryKeys = ["mealId", "photoId"],
    foreignKeys = [ForeignKey(entity = MealRecord::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaAsset::class, parentColumns = ["id"], childColumns = ["photoId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("photoId"), Index(value = ["mealId", "sortOrder"], unique = true)])
data class MealPhoto(val mealId: String, val photoId: String, val sortOrder: Int)
