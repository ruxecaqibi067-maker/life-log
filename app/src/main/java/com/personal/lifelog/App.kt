package com.personal.lifelog

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        // Reads always see one committed DB state. Every writer independently waits for reconciliation.
        startupScope.launch { container.restoreEngine.recoverPending() }
    }
}
