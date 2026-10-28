package ai.deepseek.dsh.android

import android.app.Application

class DshApplication : Application() {

    lateinit var appScope: AppScope
        private set

    override fun onCreate() {
        super.onCreate()
        appScope = AppScope.create(this)
    }
}
