package com.personal.lifelog.data.local

import com.personal.lifelog.domain.ExerciseType

object BuiltinExercises {
    data class Entry(val id: String, val name: String, val type: ExerciseType)
    val entries = listOf(
        Entry("00000000-0000-4000-8000-000000000001", "跑步", ExerciseType.CARDIO),
        Entry("00000000-0000-4000-8000-000000000002", "卧推", ExerciseType.STRENGTH),
        Entry("00000000-0000-4000-8000-000000000003", "深蹲", ExerciseType.STRENGTH),
        Entry("00000000-0000-4000-8000-000000000004", "硬拉", ExerciseType.STRENGTH),
        Entry("00000000-0000-4000-8000-000000000005", "划船", ExerciseType.STRENGTH),
        Entry("00000000-0000-4000-8000-000000000006", "肩推", ExerciseType.STRENGTH),
        Entry("00000000-0000-4000-8000-000000000007", "俯卧撑", ExerciseType.STRENGTH),
        Entry("00000000-0000-4000-8000-000000000008", "拉伸", ExerciseType.OTHER),
    )
}
