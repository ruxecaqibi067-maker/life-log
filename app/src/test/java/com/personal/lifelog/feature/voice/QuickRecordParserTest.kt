package com.personal.lifelog.feature.voice

import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.local.entity.ExerciseDefinition
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class QuickRecordParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val reference = Instant.parse("2026-11-12T10:00:00Z")
    private val exercises = BuiltinExercises.entries.map { ExerciseDefinition(it.id, it.name, it.name, it.type, true, false, it.name, null, 0, 0) }
    private fun parse(text: String) = QuickRecordParser.parse(text, reference, zone, exercises)
    private fun time(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    @Test fun durationAloneNeverInventsSleepOrWorkEndpoints() {
        for (text in listOf("我睡了五个小时", "工作了两个半小时")) {
            val result = parse(text)
            assertNull(result.draft.start); assertNull(result.draft.end)
            assertTrue(result.notices.any { it.contains("时间点") })
        }
    }
    @Test fun explicitOvernightAndChineseNumbersResolveFullDates() {
        val d = parse("昨晚十一点到今天凌晨四点，睡了五个小时").draft
        assertEquals(QuickKind.SLEEP, d.kind)
        assertEquals(time("2026-11-11T23:00"), d.start)
        assertEquals(time("2026-11-12T04:00"), d.end)
        assertEquals("300", d[QuickField.DURATION])
    }
    @Test fun overnightWithoutRepeatedDateUsesMorningOnNextDay() {
        val d = parse("昨晚十一点到早上四点睡了五小时").draft
        assertEquals(time("2026-11-12T04:00"), d.end)
    }
    @Test fun justFinishedCanSuggestFrozenNowAndDeriveStart() {
        val d = parse("刚结束工作，工作了两个小时").draft
        assertEquals(QuickAction.RECORD, d.action)
        assertEquals(reference.toEpochMilli(), d.end)
        assertEquals(reference.minusSeconds(7200).toEpochMilli(), d.start)
        assertEquals(reference.toEpochMilli(), parse("刚醒，睡了五小时").draft.end)
    }
    @Test fun oneExplicitAnchorPlusDurationCanDeriveOtherEndpoint() {
        val d = parse("今天上午九点开始工作了两小时").draft
        assertEquals(time("2026-11-12T09:00"), d.start)
        assertEquals(time("2026-11-12T11:00"), d.end)
    }
    @Test fun conflictPreservesBothSpokenDurationAndExplicitEndpoints() {
        val d = parse("昨晚十一点到今天早上六点睡了五小时").draft
        assertEquals("300", d[QuickField.DURATION])
        assertEquals(7 * 3600000L, d.end!! - d.start!!)
    }
    @Test fun bareClockRangeRemainsAmbiguous() {
        val result = parse("睡觉十一点到四点")
        assertNull(result.draft.start)
        assertTrue(result.notices.any { it.contains("上午/下午") })
    }
    @Test fun chineseHalfAndCombinedDurationsAreSupported() {
        for ((text, expected) in listOf("工作了半个小时" to "30", "工作了一个半小时" to "90", "工作了两小时半" to "150", "工作了二小时三十分钟" to "150")) {
            assertEquals(text, expected, parse(text).draft[QuickField.DURATION])
        }
    }
    @Test fun explicitDatesAndRelativeDatesUseCaptureReference() {
        assertEquals(time("2026-11-10T09:00"), parse("前天上午九点到十一点工作").draft.start)
        assertEquals(time("2026-11-09T09:00"), parse("2026年十一月九日上午九点到十一点工作").draft.start)
    }
    @Test fun mealMetadataIsSeparateFromFoodDescription() {
        val d = parse("今天中午十二点吃了牛肉面和鸡蛋").draft
        assertEquals(QuickKind.MEAL, d.kind)
        assertEquals(time("2026-11-12T12:00"), d.start)
        assertEquals("牛肉面和鸡蛋", d[QuickField.CONTENT])
    }
    @Test fun workoutMatchesExistingProjectAndQuantities() {
        val d = parse("今天下午五点跑步三十分钟五公里").draft
        assertEquals(QuickKind.WORKOUT, d.kind)
        assertEquals(exercises.first().id, d[QuickField.EXERCISE])
        assertEquals("30", d[QuickField.DURATION]); assertEquals("5", d[QuickField.DISTANCE])
        assertEquals(time("2026-11-12T17:00"), d.start)
        val strength = parse("卧推三组每组十次二十公斤").draft
        assertEquals("3", strength[QuickField.SETS]); assertEquals("10", strength[QuickField.REPS]); assertEquals("20", strength[QuickField.WEIGHT])
    }
    @Test fun multipleCategoriesAndMultipleExerciseItemsAreNotAutoSaved() {
        for (text in listOf("睡了五小时又工作了两小时", "跑步三十分钟然后卧推三组十次", "上午九点到十点工作，下午两点到三点工作")) assertTrue(text, parse(text).multiple)
    }
    @Test fun startFinishCommandsRemainExplicitActions() {
        assertEquals(QuickAction.START, parse("准备睡了").draft.action)
        assertEquals(QuickAction.START, parse("现在开始工作").draft.action)
        assertEquals(QuickAction.FINISH, parse("我醒了").draft.action)
        assertEquals(QuickAction.FINISH, parse("结束工作").draft.action)
    }
    @Test fun invalidDateAndMissingClockAreLeftForUserCorrection() {
        assertNull(parse("二月三十日上午九点到十一点工作").draft.start)
        assertNull(parse("昨天吃了面").draft.start)
        assertEquals(reference.toEpochMilli(), parse("吃了面").draft.start)
    }
    @Test fun repeatedOrMissingDstTimeRequiresWheelConfirmation() {
        val p = QuickRecordParser.parse("2026年11月1日凌晨一点到上午三点工作", reference, ZoneId.of("America/New_York"), exercises)
        assertNull(p.draft.start)
        assertTrue(p.notices.any { it.contains("重复时段") })
    }
    @Test fun forcedTypeDoesNotSilenceMultipleRecordDetection() {
        val p = QuickRecordParser.parse("睡了五小时，又工作了两小时", reference, zone, exercises, QuickKind.WORK)
        assertTrue(p.multiple); assertEquals(QuickKind.WORK, p.draft.kind)
    }
    @Test fun chineseClockMinutesAreNotDecimalDurations() {
        for (minute in listOf("五分", "零五分", "十分")) {
            val expected = if (minute == "十分") "08:10" else "08:05"
            assertEquals(time("2026-11-12T$expected"), parse("今天上午八点${minute}吃了面").draft.start)
        }
        assertEquals("8.5", parse("跑步八点五分钟").draft[QuickField.DURATION])
    }
    @Test fun mealDescriptionKeepsOriginalChineseAndMultipleTimesRequireSplitting() {
        assertEquals("三分熟的牛排", parse("今天中午十二点吃了三分熟的牛排").draft[QuickField.CONTENT])
        assertTrue(parse("今天上午九点吃了面，下午六点喝了牛奶").multiple)
    }
    @Test fun inheritedMorningTwelveMeansNoonInsteadOfMidnight() {
        assertEquals(time("2026-11-12T12:00"), parse("今天上午十点到十二点工作").draft.end)
    }
}
