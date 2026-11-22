// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * mDNS discovery on the home network (Sonos, Google Cast, …). Android 11 resolves one service at a time, so found
 * services are resolved one after another from a queue (otherwise most of them fail with "already active").
 */
object Lan {
    data class Found(val name: String, val host: String, val attrs: Map<String, String>, val port: Int = 0)

    private val active = ConcurrentHashMap.newKeySet<String>()
    private val listeners = ConcurrentHashMap<String, NsdManager.DiscoveryListener>()
    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private val pending = ArrayDeque<Pair<NsdServiceInfo, (Found) -> Unit>>()
    private var resolving = false
    private const val MAX_PENDING = 32

    /**
     * Always the application's NsdManager: Android keeps one per context, each with its own listeners, so stopping
     * through another context (the activity) would leave every discovery running.
     */
    private fun manager(c: Context) = c.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discover(c: Context, type: String, onFound: (Found) -> Unit) {
        if (!active.add(type)) return
        val nsd = manager(c)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) = enqueue(nsd, s, onFound)
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) { active.remove(type) }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { active.remove(type) }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        listeners[type] = listener
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { active.remove(type); listeners.remove(type) }
    }

    /** Stops the discovery of one service type (its next [discover] starts again, with the new callback). */
    fun stop(c: Context, type: String) {
        listeners.remove(type)?.let { runCatching { manager(c).stopServiceDiscovery(it) } }
        active.remove(type)
    }

    private var registered: NsdManager.RegistrationListener? = null

    /**
     * Announces one service of MyTV itself on the home network (replacing an earlier one), so other TVs with MyTV
     * find this one. Stays on while MyTV runs; [stopAll] does not touch it.
     */
    fun register(c: Context, type: String, name: String, port: Int, attrs: Map<String, String>) = synchronized(this) {
        unregister(c)
        val info = NsdServiceInfo().apply {
            serviceName = name; serviceType = type; this.port = port
            attrs.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) { android.util.Log.w("MyTV", "nsd register: $e") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        if (runCatching { manager(c).registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.isSuccess) registered = l
    }

    fun unregister(c: Context) = synchronized(this) {
        registered?.let { runCatching { manager(c).unregisterService(it) } }
        registered = null
    }

    /** Stops all discovery (MyTV not in front); the next poll starts it again. */
    fun stopAll(c: Context) {
        val nsd = manager(c)
        listeners.values.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear(); active.clear()
        synchronized(this) { pending.clear() }     // nothing left to resolve while another app is in front
    }

    private fun enqueue(nsd: NsdManager, s: NsdServiceInfo, cb: (Found) -> Unit) = synchronized(this) {
        // A home has a handful of speakers; a device announcing hundreds of services must not queue minutes of work.
        if (pending.size >= MAX_PENDING) return@synchronized
        pending.addLast(s to cb)
        if (!resolving) next(nsd)
    }

    private fun next(nsd: NsdManager) {
        val (s, cb) = pending.removeFirstOrNull() ?: run { resolving = false; return }
        resolving = true
        // A resolve that never answers must not stop all later ones: give up on it after 5 s.
        var finished = false
        val done = { synchronized(this) { if (!finished) { finished = true; watchdog.removeCallbacksAndMessages(s); next(nsd) } } }
        watchdog.postAtTime({ done() }, s, android.os.SystemClock.uptimeMillis() + 5000)
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onServiceResolved(r: NsdServiceInfo) {
                    // Only devices on the home network: an answer naming this TV itself or an internet address is dropped.
                    r.host?.takeIf { Net.isLan(it) }?.hostAddress?.let { host ->
                        val attrs = runCatching { r.attributes.mapValues { (_, v) -> v?.let { String(it) }.orEmpty() } }.getOrDefault(emptyMap())
                        runCatching { cb(Found(r.serviceName, host, attrs, r.port)) }
                    }
                    done()
                }
                override fun onResolveFailed(r: NsdServiceInfo, e: Int) { done() }
            })
        }.onFailure { done() }
    }
}
