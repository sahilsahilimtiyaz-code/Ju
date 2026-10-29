package com.brahma.jarvis.core

import android.app.Application
import com.brahma.jarvis.core.proactive.ProactiveEngine
import com.brahma.jarvis.core.session.AssistantSession

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val rt = Runtime.get(this)
        AssistantSession.statusLine.value = "BOOTING"
        ProactiveEngine.schedule(this)
    }
}
