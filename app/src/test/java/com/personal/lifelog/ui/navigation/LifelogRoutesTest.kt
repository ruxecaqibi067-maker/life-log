package com.personal.lifelog.ui.navigation

import androidx.navigation.NavType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the stable route contract established by T03: downstream packages
 * (T05/T06/T08/T10) build editor routes from these helpers, so an accidental
 * signature change must fail loudly here.
 */
class LifelogRoutesTest {

    @Test
    fun topLevelRoutesAreFixedSingleSegments() {
        assertEquals("today", LifelogRoutes.TODAY)
        assertEquals("review", LifelogRoutes.REVIEW)
        assertEquals("settings", LifelogRoutes.SETTINGS)
        assertTrue(LifelogRoutes.TODAY != LifelogRoutes.REVIEW)
        assertTrue(LifelogRoutes.REVIEW != LifelogRoutes.SETTINGS)
    }

    @Test
    fun editorRoutesWithoutIdMeanCreateNew() {
        assertEquals("meal/edit", LifelogRoutes.mealEditor())
        assertEquals("sleep/edit", LifelogRoutes.sleepEditor())
        assertEquals("workout/edit", LifelogRoutes.workoutEditor())
    }

    @Test
    fun editorRoutesWithIdCarryStableArgumentNames() {
        val id = "7e9d4c8b-2f1a-4e6b-9c3d-0a1b2c3d4e5f"
        assertEquals("meal/edit?mealId=$id", LifelogRoutes.mealEditor(id))
        assertEquals("sleep/edit?sleepId=$id", LifelogRoutes.sleepEditor(id))
        assertEquals("workout/edit?workoutId=$id", LifelogRoutes.workoutEditor(id))
    }

    @Test
    fun editorArgumentsAreOptionalStringsWithNullDefault() {
        listOf(
            LifelogRoutes.ARG_MEAL_ID to LifelogRoutes.mealIdArgument,
            LifelogRoutes.ARG_SLEEP_ID to LifelogRoutes.sleepIdArgument,
            LifelogRoutes.ARG_WORKOUT_ID to LifelogRoutes.workoutIdArgument,
        ).forEach { (name, argument) ->
            assertEquals(name, argument.name)
            assertEquals(NavType.StringType, argument.argument.type)
            // defaultValue = null is what makes the argument optional; the null-args path
            // is exercised end-to-end by LifelogNavigationSmokeTest.
            assertNull(argument.argument.defaultValue)
        }
    }

    @Test
    fun registeredPatternsDeclareTheirQueryTemplates() {
        // navigation-compose only parses query arguments declared in the pattern itself;
        // a pattern missing the template silently drops ids passed at navigate time.
        assertEquals("meal/edit?mealId={mealId}", LifelogRoutes.MEAL_EDIT_PATTERN)
        assertEquals("sleep/edit?sleepId={sleepId}", LifelogRoutes.SLEEP_EDIT_PATTERN)
        assertEquals("workout/edit?workoutId={workoutId}", LifelogRoutes.WORKOUT_EDIT_PATTERN)
        // Builder outputs must stay consistent with the fixed part of the pattern.
        assertEquals(
            LifelogRoutes.MEAL_EDIT_PATTERN.substringBefore('?'),
            LifelogRoutes.mealEditor().substringBefore('?'),
        )
        assertEquals(
            LifelogRoutes.SLEEP_EDIT_PATTERN.substringBefore('?'),
            LifelogRoutes.sleepEditor().substringBefore('?'),
        )
        assertEquals(
            LifelogRoutes.WORKOUT_EDIT_PATTERN.substringBefore('?'),
            LifelogRoutes.workoutEditor().substringBefore('?'),
        )
    }
}
