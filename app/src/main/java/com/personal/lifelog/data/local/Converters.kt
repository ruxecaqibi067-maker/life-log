package com.personal.lifelog.data.local

import androidx.room.TypeConverter
import com.personal.lifelog.domain.ExerciseType
import com.personal.lifelog.domain.Satiety
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.domain.WorkoutEffort
import com.personal.lifelog.domain.HabitFrequency
import com.personal.lifelog.domain.HabitStatus

class Converters {
    @TypeConverter fun habitFrequency(value: String): HabitFrequency = HabitFrequency.valueOf(value)
    @TypeConverter fun habitFrequency(value: HabitFrequency): String = value.name
    @TypeConverter fun habitStatus(value: String): HabitStatus = HabitStatus.valueOf(value)
    @TypeConverter fun habitStatus(value: HabitStatus): String = value.name
    @TypeConverter fun effort(value: String?): WorkoutEffort? = value?.let(WorkoutEffort::valueOf)
    @TypeConverter fun effort(value: WorkoutEffort?): String? = value?.name
    @TypeConverter fun satiety(value: String?): Satiety? = value?.let(Satiety::valueOf)
    @TypeConverter fun satiety(value: Satiety?): String? = value?.name
    @TypeConverter fun exerciseType(value: String): ExerciseType = ExerciseType.valueOf(value)
    @TypeConverter fun exerciseType(value: ExerciseType): String = value.name
    @TypeConverter fun theme(value: String): ThemeMode = ThemeMode.valueOf(value)
    @TypeConverter fun theme(value: ThemeMode): String = value.name
}
