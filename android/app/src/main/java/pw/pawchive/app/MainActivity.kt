package pw.pawchive.app

import android.app.Application
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
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
        val container = (application as PawApplication).container
        val appearanceWindow = AppearanceWindow(this, container.repository.appearance.state.value)
        super.onCreate(savedInstanceState)
        appearanceWindow.awaitFirstComposition()
        if (savedInstanceState == null) incoming = intent?.dataString
        setContent {
            val appearance by container.repository.appearance.state.collectAsStateWithLifecycle()
            val loaded = appearance ?: return@setContent
            val dark = loaded.dark ?: isSystemInDarkTheme()
            SideEffect { appearanceWindow.apply(dark, loaded.dark) }
            PawTheme(dark) { PawApp(container, dark, incoming) { incoming = null } }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming = intent.dataString }
}
