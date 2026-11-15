package ro.camara.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ro.camara.app.ai.LocalAi
import ro.camara.app.ai.ServerAiPresence
import ro.camara.app.notifications.ReminderScheduler

class CamaraApplication : Application() {
    val serverAiPresence by lazy { ServerAiPresence(this) }
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        background.launch {
            ReminderScheduler.channel(this@CamaraApplication)
            ReminderScheduler.schedule(this@CamaraApplication)
            ro.camara.app.data.SyncEngine.schedule(this@CamaraApplication)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) CoroutineScope(Dispatchers.Default).launch { LocalAi.get(this@CamaraApplication).release() }
    }
}
