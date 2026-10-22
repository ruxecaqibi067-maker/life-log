package com.personal.lifelog.ui.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument

/**
 * Stable navigation contract for v0.1, established by T03 and consumed by T05/T06/T08/T10.
 *
 * Conventions:
 * - TODAY and REVIEW are the only bottom-tab destinations; TODAY is the start destination.
 * - SETTINGS and the editor routes are full-screen entries pushed on top of the current tab,
 *   so the back gesture/button returns to the tab that opened them.
 * - Editor routes take one optional query parameter: absent id means create a new record,
 *   a present id means edit the record with that canonical lowercase UUID (domain.RecordIds).
 * - The query template must appear in the registered route pattern itself, otherwise
 *   navigation-compose matches the deep link but never parses query arguments (the
 *   arguments bundle stays null even for ids passed at navigate time). Keep builders and
 *   patterns in sync; LifelogRoutesTest and LifelogNavigationSmokeTest guard both.
 * - Route strings and argument names are persisted navigation state; keep them stable.
 *   New parameters must be added with `defaultValue` so older saved entries stay valid.
 */
object LifelogRoutes {
    const val TODAY = "today"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val SLEEP_HISTORY = "sleep/history"

    // Reserved editor routes; placeholder screens exist today, real editors arrive later.
    const val MEAL_EDIT = "meal/edit"
    const val SLEEP_EDIT = "sleep/edit"
    const val WORKOUT_EDIT = "workout/edit"

    /** Read-only detail; T05 adds it so a saved meal can be opened, edited and deleted. */
    const val MEAL_DETAIL = "meal/detail"

    const val ARG_MEAL_ID = "mealId"
    const val ARG_SLEEP_ID = "sleepId"
    const val ARG_WORKOUT_ID = "workoutId"

    // Patterns registered with composable(): the query template is required for
    // navigation-compose to parse optional query arguments (see the class comment).
    const val MEAL_EDIT_PATTERN = "$MEAL_EDIT?$ARG_MEAL_ID={$ARG_MEAL_ID}"
    const val MEAL_DETAIL_PATTERN = "$MEAL_DETAIL?$ARG_MEAL_ID={$ARG_MEAL_ID}"
    const val SLEEP_EDIT_PATTERN = "$SLEEP_EDIT?$ARG_SLEEP_ID={$ARG_SLEEP_ID}"
    const val WORKOUT_EDIT_PATTERN = "$WORKOUT_EDIT?$ARG_WORKOUT_ID={$ARG_WORKOUT_ID}"

    /** Optional record UUIDs are URL-encoded by the navigation library when navigating. */
    fun mealEditor(id: String? = null): String = editorRoute(MEAL_EDIT, ARG_MEAL_ID, id)
    fun mealDetail(id: String): String = "$MEAL_DETAIL?$ARG_MEAL_ID=$id"
    fun sleepEditor(id: String? = null): String = editorRoute(SLEEP_EDIT, ARG_SLEEP_ID, id)
    fun workoutEditor(id: String? = null): String = editorRoute(WORKOUT_EDIT, ARG_WORKOUT_ID, id)

    private fun editorRoute(base: String, arg: String, id: String?): String =
        if (id == null) base else "$base?$arg=$id"

    val mealIdArgument = navArgument(ARG_MEAL_ID) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
    val sleepIdArgument = navArgument(ARG_SLEEP_ID) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
    val workoutIdArgument = navArgument(ARG_WORKOUT_ID) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
}
