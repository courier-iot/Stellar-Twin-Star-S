package com.stellar.twinstar.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.stellar.twinstar.crypto.Crypto
import com.stellar.twinstar.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow

object AppLock {
    val locked = MutableStateFlow(false)
    /** Locked notes opened since the app was last in the foreground. */
    val unlockedNotes = MutableStateFlow<Set<String>>(emptySet())
    private var backgroundAt = 0L

    fun init(prefs: Prefs) { locked.value = prefs.lockEnabled.value && hasPin(prefs) }

    fun onBackground() { backgroundAt = System.currentTimeMillis() }

    fun onForeground(prefs: Prefs) {
        if (backgroundAt == 0L) return
        if (System.currentTimeMillis() - backgroundAt >= prefs.lockTimeoutMs.value) {
            unlockedNotes.value = emptySet()
            if (prefs.lockEnabled.value && hasPin(prefs)) locked.value = true
        }
    }

    fun hasPin(prefs: Prefs) = prefs.pinHash.value.isNotEmpty()

    fun setPin(prefs: Prefs, pin: String) {
        val salt = Crypto.random(16)
        prefs.pinSalt.set(Crypto.hex(salt))
        prefs.pinHash.set(Crypto.hex(Crypto.pbkdf2(pin.toCharArray(), salt, 60_000)))
    }

    fun checkPin(prefs: Prefs, pin: String): Boolean {
        if (!hasPin(prefs)) return false
        val h = Crypto.pbkdf2(pin.toCharArray(), Crypto.unhex(prefs.pinSalt.value), 60_000)
        return Crypto.constantEquals(h, Crypto.unhex(prefs.pinHash.value))
    }

    fun biometricAvailable(ctx: Context) =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    fun promptBiometric(activity: FragmentActivity, title: String, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .build()
        )
    }
}
