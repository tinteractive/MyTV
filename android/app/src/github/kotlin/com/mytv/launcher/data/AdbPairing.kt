// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mytv.launcher.HomeWatch
import com.mytv.launcher.PairPad
import com.mytv.launcher.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Pair with a code" (Connection to the TV): for ten minutes MyTV watches, by mDNS, for the TV's own pairing service,
 * which only runs while Wireless debugging's "Pair device with pairing code" window is open. Then the Home-button
 * helper shows [PairPad] over that window, and the code typed there pairs MyTV ([AdbTls.pair]).
 */
object AdbPairing {
    enum class State { IDLE, WAITING, PAIRED, NO_HELPER, UNSUPPORTED }

    var state by mutableStateOf(State.IDLE); private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private const val WINDOW_MS = 10 * 60_000L

    fun supported() = Build.VERSION.SDK_INT >= 30

    fun start(c: Context) {
        val app = c.applicationContext
        if (Build.VERSION.SDK_INT < 30) { state = State.UNSUPPORTED; return }
        if (!HomeWatch.connected) { state = State.NO_HELPER; return }
        state = State.WAITING
        if (running) return
        running = true
        Thread({ runCatching { watch(app) }.onFailure { Log.w("MyTV", "pairing: $it") }; running = false }, "mytv-pairing").start()
    }

    fun stop() { running = false; PairPad.hide(); if (state == State.WAITING) state = State.IDLE }

    private fun watch(app: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val until = System.currentTimeMillis() + WINDOW_MS
        while (running && System.currentTimeMillis() < until) {
            val at = AdbTls.find(app, AdbTls.PAIRING, 2500) ?: continue
            Log.i("MyTV", "pairing window open on port ${at.port}")
            if (offer(app, at)) {
                // The TLS port first (the TV opens it a moment after pairing), then "paired": MyTV's Connection page
                // checks the connection as soon as it says so.
                for (i in 1..5) {
                    if (runCatching { AdbTls.connectPort(app, 3000) }.getOrNull() != null) break
                    Thread.sleep(1000)
                }
                main.post { state = State.PAIRED }
                Thread.sleep(2500)
                PairPad.hide()
                // Back to MyTV's Connection page, which now says Connected.
                main.post {
                    runCatching {
                        HomeWatch.service?.startActivity(android.content.Intent(app, com.mytv.launcher.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                }
                running = false
                return
            }
        }
        running = false
        main.post { if (state == State.WAITING) state = State.IDLE }
    }

    /** One open pairing window: the box until it pairs (true), is closed, or the window goes away (false). */
    private fun offer(app: Context, at: AdbTls.Found): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val finished = CountDownLatch(1)
        val paired = java.util.concurrent.atomic.AtomicBoolean(false)
        main.post {
            val service = HomeWatch.service
            if (service == null || !PairPad.show(service, onCode = { code ->
                    Thread({
                        try {
                            AdbTls.pair(app, code, at)
                            paired.set(true)
                            PairPad.say(app.getString(R.string.pad_done))
                            finished.countDown()
                        } catch (e: AdbTls.WrongCode) {
                            Log.i("MyTV", "pairing: $e")
                            PairPad.say(app.getString(R.string.pad_wrong), error = true)
                        } catch (e: Exception) {
                            Log.w("MyTV", "pairing: $e")
                            PairPad.say(app.getString(R.string.pad_failed), error = true)
                        }
                    }, "mytv-pair").start()
                }, onClose = { finished.countDown() })) {
                state = State.NO_HELPER; running = false; finished.countDown()
            }
        }
        // While the box shows: the TV closes its pairing window (and its port) after a pair, a cancel or a wrong code.
        while (running && !finished.await(1, TimeUnit.SECONDS)) {
            if (!paired.get() && AdbTls.connect(at, 500)?.also { runCatching { it.close() } } == null) {
                Thread.sleep(3000)      // long enough to read "wrong code"
                break
            }
        }
        if (!paired.get()) PairPad.hide()
        return paired.get()
    }
}
