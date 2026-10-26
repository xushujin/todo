package app.todo.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * App lock built on the platform biometric stack.
 *
 * `androidx.biometric` is the Jetpack wrapper around `android.hardware.biometrics`
 * (the platform API is not part of the public SDK surface for third-party apps, so
 * the prompt itself must come from the Jetpack library). Everything the app owns —
 * when to lock, the unlock screen, the FLAG_SECURE snapshot protection — is
 * implemented here.
 *
 * BIOMETRIC_WEAK is combined with DEVICE_CREDENTIAL so the app stays usable on a
 * device without a fingerprint sensor (PIN/pattern/password fallback).
 */
class BiometricGate(private val activity: FragmentActivity) {

    enum class Availability { AVAILABLE, NO_HARDWARE, NONE_ENROLLED, UNAVAILABLE }

    private var prompt: BiometricPrompt? = null

    fun availability(): Availability = availability(activity)

    fun authenticate(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (availability() != Availability.AVAILABLE) {
            onError("Biometric authentication is not available on this device")
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompt = null
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompt = null
                onError(errString.toString())
            }
        }
        val biometricPrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        prompt = biometricPrompt
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED)
            .build()
        biometricPrompt.authenticate(info)
    }

    fun cancel() {
        prompt?.cancelAuthentication()
        prompt = null
    }

    companion object {
        private const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        /** Usable from a plain [Context], so settings can warn before locking the user out. */
        fun availability(context: Context): Availability =
            when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
                BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NONE_ENROLLED
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_HARDWARE
                else -> Availability.UNAVAILABLE
            }
    }
}
