package com.personal.lifelog.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Satiety names are persisted in Room and backup JSON. This guard makes an
 * accidental rename/removal fail loudly; new options are additive.
 */
class SatietyTest {

    @Test
    fun persistedNamesStayStableAndNewValuesAreAdditive() {
        assertEquals(
            listOf("FIVE", "SEVEN", "NINE", "FULL", "NOT_FULL", "CUSTOM"),
            Satiety.entries.map { it.name },
        )
    }

    @Test
    fun everyLegacyBackupNameStillParsesToTheSameMeaning() {
        listOf("FIVE", "SEVEN", "NINE", "FULL").forEach { name ->
            assertEquals(name, Satiety.valueOf(name).name)
        }
        assertEquals(Satiety.NOT_FULL, Satiety.valueOf("NOT_FULL"))
        assertEquals(Satiety.CUSTOM, Satiety.valueOf("CUSTOM"))
    }
}
