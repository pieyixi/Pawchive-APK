package pw.pawchive.app

import android.app.Application
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import pw.pawchive.data.PawRepository
import pw.pawchive.download.*
import pw.pawchive.media.installImageLoader

class AppContainer(context: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val navigationIcons = NavigationIconCache(context, scope)
    val repository = PawRepository(context, scope)
    internal val updater = AppUpdater(context, scope, repository.publicClient)
    val downloads = DownloadCoordinator(context, repository)
    init {
        TransferRuntime.repository = repository; installImageLoader(context)
        scope.launch { downloads.reconcileInterruptedTasks() }
    }
}
class PawApplication : Application() {
    lateinit var container: AppContainer; private set
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}
class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (savedInstanceState == null) incoming = intent?.dataString
        val container = (application as PawApplication).container
        setContent {
            val appearanceFlow = remember(container.repository) { container.repository.dark.map { Appearance(it) } }
            val appearance by appearanceFlow.collectAsStateWithLifecycle<Appearance?>(null)
            // Keep the opaque launch surface until the persisted preference is known.
            // null used to mean both "not loaded" and "follow system", causing a white first frame.
            val loaded = appearance
            if (loaded == null) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(Color(0xFF19191D)))
                return@setContent
            }
            val dark = loaded.dark ?: isSystemInDarkTheme()
            SideEffect {
                enableEdgeToEdge(statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
            }
            PawTheme(dark) { PawApp(container, dark, incoming) { incoming = null } }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming = intent.dataString }
}
private data class Appearance(val dark: Boolean?)
