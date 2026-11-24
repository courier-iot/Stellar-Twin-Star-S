package com.stellar.twinstar.data

import android.content.Context
import android.os.Build
import com.stellar.twinstar.crypto.Crypto
import com.stellar.twinstar.crypto.KeystoreBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("twinstar", Context.MODE_PRIVATE)

    inner class BoolPref(private val key: String, def: Boolean) {
        private val flow = MutableStateFlow(sp.getBoolean(key, def))
        val state: StateFlow<Boolean> get() = flow
        val value get() = flow.value
        fun set(v: Boolean) { sp.edit().putBoolean(key, v).apply(); flow.value = v }
    }

    inner class IntPref(private val key: String, def: Int) {
        private val flow = MutableStateFlow(sp.getInt(key, def))
        val state: StateFlow<Int> get() = flow
        val value get() = flow.value
        fun set(v: Int) { sp.edit().putInt(key, v).apply(); flow.value = v }
    }

    inner class StringPref(private val key: String, def: String) {
        private val flow = MutableStateFlow(sp.getString(key, def) ?: def)
        val state: StateFlow<String> get() = flow
        val value get() = flow.value
        fun set(v: String) { sp.edit().putString(key, v).apply(); flow.value = v }
    }

    // Look
    val accent = IntPref("accent", 0xFFB69CFF.toInt())
    val glowEnabled = BoolPref("glow_on", true)
    val glowStrength = IntPref("glow_strength", 60)

    // Books
    val pageFit = BoolPref("page_fit", true)
    val pageAnim = IntPref("page_anim", 0) // 0 flip, 1 curl, 2 slide

    // Security
    val encryption = BoolPref("encryption", true)
    val lockEnabled = BoolPref("lock_enabled", false)
    val biometric = BoolPref("biometric", true)
    val pinHash = StringPref("pin_hash", "")
    val pinSalt = StringPref("pin_salt", "")
    val lockTimeoutMs = IntPref("lock_timeout", 0)
    val hideInRecents = BoolPref("hide_recents", false)

    // Sync
    val backgroundSync = BoolPref("bg_sync", false)
    val askBeforeCatchup = BoolPref("ask_catchup", true)
    val lanEnabled = BoolPref("lan", true)
    val relays = StringPref("relays", DEFAULT_RELAYS.joinToString("\n"))
    val deviceName = StringPref("device_name", Build.MODEL ?: "Android")

    fun relayList(): List<String> = relays.value.lines().map { it.trim() }
        .filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()

    val deviceId: String by lazy {
        sp.getString("device_id", null) ?: Crypto.randomHex(16).also { sp.edit().putString("device_id", it).apply() }
    }

    /** secp256k1 secret for signing relay events, stored sealed by the Keystore. */
    fun nostrSecret(): ByteArray {
        val stored = sp.getString("nostr_sk", null)
        if (stored != null) return Crypto.unhex(KeystoreBox.openString(stored))
        val sk = Crypto.random(32)
        sp.edit().putString("nostr_sk", KeystoreBox.sealString(Crypto.hex(sk))).apply()
        return sk
    }

    var lastTrashSweep: Long
        get() = sp.getLong("trash_sweep", 0)
        set(v) { sp.edit().putLong("trash_sweep", v).apply() }

    companion object {
        val DEFAULT_RELAYS = listOf("wss://nos.lol", "wss://relay.damus.io", "wss://relay.primal.net")
    }
}
