package com.personal.lifelog

import android.app.Application

class App : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
