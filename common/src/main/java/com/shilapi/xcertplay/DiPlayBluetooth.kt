package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.provider.Settings
import java.io.File

/**
 * The head unit's own Bluetooth address, as advertised to the iPhone. iOS uses it to recognise the
 * CarPlay accessory as the car it is already paired with, hand calls to CarPlay and release the
 * car's hands-free link. When no real address is readable the synthetic device id is advertised and
 * the car's own phone app keeps answering calls next to CarPlay.
 */
internal object DiPlayBluetooth {
    data class Resolved(val address: String, val source: String)

    private val MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")

    /** Hidden `BluetoothProfile.HEADSET_CLIENT`: the hands-free role a head unit plays towards a phone. */
    private const val PROFILE_HEADSET_CLIENT = 16

    fun isValid(candidate: String?): Boolean =
        candidate != null && MAC.matches(candidate) &&
            !candidate.startsWith("02:00:00:00:00:") && candidate != "00:00:00:00:00:00"

    /** Accepts `AA:BB:CC:DD:EE:FF`, `aa-bb-cc-dd-ee-ff` or `AABBCCDDEEFF`; returns the canonical form or null. */
    fun normalize(input: String): String? {
        val hex = input.trim().replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) return null
        val address = hex.chunked(2).joinToString(":").uppercase()
        return address.takeIf { isValid(it) }
    }

    /** The address to advertise, preferring the user's own entry, then whatever this firmware exposes. */
    fun resolve(context: Context): Resolved? {
        DiPlayPreferences.carBluetoothAddress(context)?.let { if (isValid(it)) return Resolved(it, "settings") }
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull()
        if (isValid(adapter)) return Resolved(adapter!!.uppercase(), "adapter")
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        if (isValid(setting)) return Resolved(setting!!.uppercase(), "system setting")
        val kernel = runCatching { File("/sys/class/bluetooth/hci0/address").readText().trim() }.getOrNull()
        if (isValid(kernel)) return Resolved(kernel!!.uppercase(), "kernel")
        return null
    }

    fun localAddress(context: Context): String? = resolve(context)?.address

    /**
     * Whether the head unit still holds a hands-free link to a phone. Profile-wide, not per device:
     * the per-device calls are system-only. Null when the adapter cannot be read (usually the
     * Nearby devices permission on Android 12+).
     */
    fun handsFreeLinkConnected(context: Context): Boolean? {
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
            ?: return null
        if (runCatching { adapter.isEnabled }.getOrNull() == false) return false
        var readAny = false
        for (profile in intArrayOf(PROFILE_HEADSET_CLIENT, BluetoothProfile.HEADSET)) {
            val state = runCatching { adapter.getProfileConnectionState(profile) }.getOrNull() ?: continue
            readAny = true
            if (state == BluetoothAdapter.STATE_CONNECTED) return true
        }
        return if (readAny) false else null
    }
}
