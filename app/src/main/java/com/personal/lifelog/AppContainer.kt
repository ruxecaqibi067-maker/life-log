package com.personal.lifelog

import java.time.Clock
import java.time.ZoneId

/** Application-scoped dependencies. Persistence and repositories are added in T02. */
class AppContainer(
    val clock: Clock = Clock.systemUTC(),
    val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
)
