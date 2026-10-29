package com.brahma.jarvis.core.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brahma.jarvis.core.Runtime
import com.brahma.jarvis.core.brain.GreetingEngine

/** Battery / charger / headphone events feed the proactive gate. */
class DeviceEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = Runtime.peek(context) ?: return
        val kind = when (intent.action) {
            Intent.ACTION_BATTERY_LOW -> "low_battery"
            Intent.ACTION_POWER_CONNECTED -> "charging"
            else -> return
        }
        val gate = ProactiveEngine.buildGate(runtime, kind)
        if (ProactiveGate.allow(gate)) {
            val line = GreetingEngine.proactiveFallback(kind, runtime)
            ProactiveEngine.deliver(runtime, kind, line)
        } else {
            runtime.memory.recordSignal("proactive_suppressed", 0.5, kind)
        }
    }
}
