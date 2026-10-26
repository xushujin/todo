package app.todo

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.todo.security.BiometricGate
import app.todo.security.LockPolicy
import app.todo.ui.AppRoot
import app.todo.ui.screens.LockScreen
import app.todo.ui.theme.Palettes
import app.todo.ui.theme.TodoTheme

class MainActivity : FragmentActivity() {

    private val graph: AppGraph
        get() = (application as TodoApp).graph

    private lateinit var biometricGate: BiometricGate
    private val locked = mutableStateOf(false)
    private val lockError = mutableStateOf<String?>(null)
    private val pendingTodoId = mutableStateOf<String?>(null)
    private var startedOnce = false
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        biometricGate = BiometricGate(this)
        locked.value = biometricLockEnabled()
        pendingTodoId.value = intent?.getStringExtra(EXTRA_TODO_ID)

        setContent {
            // Keeps the navigation stack and the (unsaved) editor fields alive while the
            // lock screen replaces the app content, so unlocking returns to the same page.
            val stateHolder = rememberSaveableStateHolder()
            val themeId by graph.settings.themeId.collectAsStateWithLifecycle()
            val palette = Palettes.byId(themeId)
            TodoTheme(palette) {
                SystemBars(lightContent = !palette.isDark)
                LockFlag(locked = locked.value)
                Surface(modifier = Modifier.fillMaxSize(), color = palette.background) {
                    Box(Modifier.fillMaxSize()) {
                        if (locked.value) {
                            LockScreen(
                                errorMessage = lockError.value,
                                onUnlock = { requestUnlock() },
                            )
                        } else {
                            stateHolder.SaveableStateProvider(APP_STATE_KEY) {
                                AppRoot(graph = graph, initialTodoId = pendingTodoId.value)
                            }
                        }
                    }
                }
            }
            LaunchedEffect(locked.value) {
                if (locked.value) requestUnlock()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (startedOnce && biometricLockEnabled()) {
            val elapsed = if (backgroundedAt == 0L) null else SystemClock.elapsedRealtime() - backgroundedAt
            if (LockPolicy.shouldLockOnReturn(graph.settings.lockTimeoutSeconds.value, elapsed)) {
                lockError.value = null
                locked.value = true
            }
        }
        startedOnce = true
    }

    override fun onStop() {
        backgroundedAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTodoId.value = intent.getStringExtra(EXTRA_TODO_ID)
    }

    override fun onDestroy() {
        biometricGate.cancel()
        super.onDestroy()
    }

    /** Locking is only allowed when the device can actually authenticate, otherwise the
     *  user would be trapped on the lock screen with no way in. */
    private fun biometricLockEnabled(): Boolean =
        graph.settings.biometricEnabled.value &&
            biometricGate.availability() == BiometricGate.Availability.AVAILABLE

    private fun requestUnlock() {
        if (isFinishing || isDestroyed) return
        biometricGate.authenticate(
            title = getString(R.string.lock_title),
            subtitle = getString(R.string.lock_body),
            onSuccess = {
                lockError.value = null
                locked.value = false
            },
            onError = { message -> lockError.value = message },
        )
    }

    @Composable
    private fun SystemBars(lightContent: Boolean) {
        val view = LocalView.current
        SideEffect {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = lightContent
            controller.isAppearanceLightNavigationBars = lightContent
        }
    }

    @Composable
    private fun LockFlag(locked: Boolean) {
        val view = LocalView.current
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            if (locked) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    companion object {
        const val EXTRA_TODO_ID = "app.todo.extra.TODO_ID"
        private const val APP_STATE_KEY = "app"
    }
}
