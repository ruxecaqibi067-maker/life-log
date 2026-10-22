package com.personal.lifelog.core

import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.core.validation.Units
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.domain.DataViolation
import com.personal.lifelog.domain.RecordIds
import org.junit.Assert.*
import org.junit.Test

class ValidationTest {
    @Test fun optionalInputsStayNull() {
        listOf(null, "", "  ", "\t\n").forEach {
            assertNull(Units.kilogramsToGrams(it))
            assertNull(Units.kilometersToMeters(it))
            assertNull(Units.minutesToSeconds(it))
            assertNull(Units.repetitions(it))
        }
    }

    @Test fun decimalConversionsAreExact() {
        assertEquals(62_375L, Units.kilogramsToGrams("62.375"))
        assertEquals(1_001L, Units.kilogramsToGrams("1.001"))
        assertEquals(1L, Units.kilometersToMeters("0.001"))
        assertEquals(90L, Units.minutesToSeconds("1.5"))
        assertEquals(0L, Units.kilogramsToGrams("0"))
        assertEquals(1, Units.repetitions("1"))
    }

    @Test fun invalidPrecisionAndOverflowAreRejected() {
        listOf("-1", "1e3", "NaN", "9".repeat(65), "9223372036854775808", "0.0001").forEach {
            assertThrows(DataViolation::class.java) { Units.kilogramsToGrams(it) }
        }
        listOf("0", "-1", "2147483648", "1.5").forEach {
            assertThrows(DataViolation::class.java) { Units.repetitions(it) }
        }
        assertThrows(DataViolation::class.java) { Units.minutesToSeconds("0") }
        assertThrows(DataViolation::class.java) { Units.kilometersToMeters("0") }
    }

    @Test fun textUsesUnicodeCodePointsAndTrimmedLimits() {
        assertNull(Rules.text("　 \n ", 500))
        assertEquals("你好", Rules.text(" 你好 ", 500))
        assertEquals("🙂".repeat(500), Rules.text("🙂".repeat(500), 500))
        assertThrows(DataViolation::class.java) { Rules.text("🙂".repeat(501), 500) }
        assertThrows(DataViolation::class.java) { Rules.exerciseName(" ") }
        assertThrows(DataViolation::class.java) { Rules.exerciseName("动".repeat(41)) }
        assertEquals("bench press", Rules.normalizedName(" Ｂｅｎｃｈ Ｐｒｅｓｓ "))
    }

    @Test fun idsAreCanonicalStableUuids() {
        val id = RecordIds.newId()
        assertEquals(id, Rules.id(id))
        listOf("1-1-1-1-1", "../photo", "A".repeat(36)).forEach {
            assertThrows(DataViolation::class.java) { Rules.id(it) }
        }
    }

    @Test fun mediaPathsAreDerivedAndBounded() {
        val id = RecordIds.newId()
        val generation = RecordIds.newId()
        val valid = MediaAsset(id, generation, MediaRules.relativePath(generation, id), "image/jpeg",
            100, 200, Rules.PHOTO_BYTES_LIMIT, "a".repeat(64), 1, 1)
        MediaRules.validate(valid)
        listOf(valid.copy(relativePath = "../$id.jpg"), valid.copy(relativePath = "C:/$id.jpg"),
            valid.copy(relativePath = "media\\$generation\\$id.jpg"), valid.copy(mimeType = "image/png"),
            valid.copy(byteSize = Rules.PHOTO_BYTES_LIMIT + 1), valid.copy(width = 0),
            valid.copy(sha256 = "bad"), valid.copy(updatedAtUtcMs = 0)).forEach {
            assertThrows(DataViolation::class.java) { MediaRules.validate(it) }
        }
    }
}
