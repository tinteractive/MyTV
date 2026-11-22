// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** A paired headphone or speaker. */
data class BtDevice(val address: String, val name: String, val connected: Boolean, val battery: Int?)

/**
 * Bluetooth headphones and speakers: connect or disconnect a paired device with one press. Uses the A2DP profile;
 * connect/disconnect are hidden Android calls that Android 11 still allows (tested on Philips by CleanYourTV).
 * Pairing a new device is left to the TV's own settings.
 */
@SuppressLint("MissingPermission")
object Bluetooth {
    @Volatile private var a2dp: BluetoothA2dp? = null

    private fun adapter(c: Context): BluetoothAdapter? =
        (c.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    /** Gets the A2DP profile once (asynchronous on Android); call early, e.g. when the list opens. */
    fun prepare(c: Context, ready: () -> Unit = {}) {
        if (a2dp != null) { ready(); return }
        runCatching {
            adapter(c)?.getProfileProxy(c.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) { a2dp = proxy as BluetoothA2dp; ready() }
                override fun onServiceDisconnected(p: Int) { a2dp = null }
            }, BluetoothProfile.A2DP)
        }.onFailure { Log.w("MyTV", "bluetooth: $it") }
    }

    fun enabled(c: Context) = runCatching { adapter(c)?.isEnabled == true }.getOrDefault(false)

    /** Paired audio devices (headphones, speakers); remotes and keyboards are left out. */
    fun devices(c: Context): List<BtDevice> = runCatching {
        val connected = a2dp?.connectedDevices.orEmpty().map { it.address }.toSet()
        adapter(c)?.bondedDevices.orEmpty().filter { d ->
            val cls = d.bluetoothClass
            cls == null || cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO || cls.hasService(BluetoothClass.Service.AUDIO) ||
                cls.hasService(BluetoothClass.Service.RENDER)
        }.map { d ->
            val battery = runCatching { (d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int).takeIf { it in 0..100 } }.getOrNull()
            BtDevice(d.address, d.name ?: d.address, d.address in connected, battery)
        }.sortedWith(compareBy({ !it.connected }, { it.name.lowercase() }))
    }.onFailure { Log.w("MyTV", "bluetooth list: $it") }.getOrDefault(emptyList())

    /**
     * Connected Bluetooth devices that report a battery level, of any kind (headphones, speakers, a Bluetooth remote):
     * (address, name, level). A device that does not report one (infrared remotes, most speakers) is not listed.
     */
    fun batteries(c: Context): List<Triple<String, String, Int>> = runCatching {
        adapter(c)?.bondedDevices.orEmpty().mapNotNull { d ->
            val level = runCatching { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int }.getOrNull()?.takeIf { it in 0..100 } ?: return@mapNotNull null
            Triple(d.address, d.name ?: d.address, level)
        }
    }.getOrDefault(emptyList())

    /** Connect or disconnect; false when this TV does not allow it (then open the settings). */
    fun toggle(c: Context, address: String, connect: Boolean): Boolean = runCatching {
        val p = a2dp ?: return false
        val dev: BluetoothDevice = adapter(c)!!.getRemoteDevice(address)
        val m = BluetoothA2dp::class.java.getMethod(if (connect) "connect" else "disconnect", BluetoothDevice::class.java)
        m.invoke(p, dev) as Boolean
    }.onFailure { Log.w("MyTV", "bluetooth ${if (connect) "connect" else "disconnect"}: $it") }.getOrDefault(false)

    /** The TV's own pairing screen, which starts searching right away; otherwise the Bluetooth settings. */
    fun pairNew(c: Context) {
        val tries = listOf(
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"),
            Intent("com.google.android.intent.action.CONNECT_INPUT"),
        )
        if (!Net.startFirst(c, *tries.toTypedArray())) openSettings(c)
    }

    fun openSettings(c: Context) {
        Net.startFirst(c, SystemAccess.real(c, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)), SystemAccess.androidTvSettings(), Intent(Settings.ACTION_SETTINGS))
    }
}
