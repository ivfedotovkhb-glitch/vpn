package org.torproject.android.service.circumvention

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.torproject.android.util.Prefs
import java.util.Timer
import java.util.TimerTask
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * AntiBlock transport selector.
 * Tries censorship-resistant transports explicitly instead of relying on Orbot's
 * shorter default SmartConnect chain.
 */
object SmartConnect {
    private val ioScope by lazy { CoroutineScope(Dispatchers.IO) }
    private val mainScope by lazy { CoroutineScope(Dispatchers.Main) }

    private var progress = 0
    private var connectionTimeout = TimeSource.Monotonic.markNow()
    private var connectionGuard: Timer? = null

    private val chain = listOf(
        Transport.WEBTUNNEL,
        Transport.SNOWFLAKE,
        Transport.SNOWFLAKE_AMP,
        Transport.OBFS4,
        Transport.MEEK,
        Transport.DNSTT,
        Transport.NONE
    )

    @JvmStatic
    fun handle(
        context: Context,
        startTor: () -> Exception?,
        reconfigure: () -> Boolean,
        stopTor: (e: Exception?) -> Unit,
        completed: () -> Unit
    ) {
        progress = 0

        if (!Prefs.smartConnect) {
            val exception = startTor()
            return if (exception != null) stopTor(exception) else completed()
        }

        ioScope.launch {
            var index = 0
            Prefs.transport = chain[index]

            try {
                Prefs.transport.start(context)
            } catch (_: Throwable) {
                index++
                if (!selectNext(context, index)) {
                    mainScope.launch { stopTor(Exception("AntiBlock: no transport could start")) }
                    return@launch
                }
            }

            val exception = startTor()
            if (exception != null) {
                mainScope.launch { stopTor(exception) }
                return@launch
            }

            connectionAlive()
            connectionGuard = Timer()
            connectionGuard?.schedule(object : TimerTask() {
                override fun run() {
                    if (progress >= 100) {
                        stopConnectionGuard()
                        mainScope.launch { completed() }
                        return
                    }

                    if (TimeSource.Monotonic.markNow() < connectionTimeout) return
                    connectionAlive()

                    try { Prefs.transport.stop() } catch (_: Throwable) {}
                    index++

                    if (!selectNext(context, index)) {
                        stopConnectionGuard()
                        mainScope.launch { stopTor(Exception("AntiBlock: all transports failed")) }
                        return
                    }

                    if (!reconfigure()) {
                        connectionTimeout = TimeSource.Monotonic.markNow()
                    }
                }
            }, 1000, 1000)
        }
    }

    private fun selectNext(context: Context, startIndex: Int): Boolean {
        var i = startIndex
        while (i < chain.size) {
            Prefs.transport = chain[i]
            try {
                Prefs.transport.start(context)
                return true
            } catch (_: Throwable) {
                try { Prefs.transport.stop() } catch (_: Throwable) {}
                i++
            }
        }
        return false
    }

    @JvmStatic
    fun updateProgress(value: Int) {
        if (!Prefs.smartConnect) return
        if (value > progress) {
            connectionAlive()
            progress = value
        }
    }

    @JvmStatic
    fun cancel() = stopConnectionGuard()

    private fun connectionAlive() {
        connectionTimeout = TimeSource.Monotonic.markNow() + 12.seconds
    }

    private fun stopConnectionGuard() {
        connectionGuard?.cancel()
        connectionGuard?.purge()
        connectionGuard = null
    }
}
