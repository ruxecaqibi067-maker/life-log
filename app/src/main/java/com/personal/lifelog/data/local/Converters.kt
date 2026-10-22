package com.personal.lifelog.data.local

import androidx.room.TypeConverter
import com.personal.lifelog.domain.ExerciseType
import com.personal.lifelog.domain.Satiety
import com.personal.lifelog.domain.ThemeMode

class Converters {
    @TypeConverter fun satiety(value: String?): Satiety? = value?.let(Satiety::valueOf)
    @TypeConverter fun satiety(value: Satiety?): String? = value?.name
    @TypeConverter fun exerciseType(value: String): ExerciseType = ExerciseType.valueOf(value)
    @TypeConverter fun exerciseType(value: ExerciseType): String = value.name
    @TypeConverter fun theme(value: String): ThemeMode = ThemeMode.valueOf(value)
    @TypeConverter fun theme(value: ThemeMode): String = value.name
}
