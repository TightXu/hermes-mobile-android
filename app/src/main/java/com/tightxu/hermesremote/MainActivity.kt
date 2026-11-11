package com.tightxu.hermesremote

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tightxu.hermesremote.data.AppSettings
import com.tightxu.hermesremote.ui.chat.ChatScreen
import com.tightxu.hermesremote.ui.login.LoginScreen
import com.tightxu.hermesremote.ui.sessions.SessionsScreen
import com.tightxu.hermesremote.ui.settings.SettingsScreen
import com.tightxu.hermesremote.ui.theme.HermesRemoteTheme
import android.net.Uri
import kotlinx.coroutines.flow.map

// AppCompatActivity (not ComponentActivity): supplies the per-app locale via
// AppCompatDelegate, which the settings screen's language picker relies on.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        val container = (application as HermesApp).container
        setContent {
            // Accent theme follows the persisted setting; changes in the
            // settings screen apply immediately app-wide.
            val themeKey by container.settings.settings
                .map { it.theme }
                .collectAsState(initial = AppSettings().theme)
            HermesRemoteTheme(accentKey = themeKey) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(container)
                }
            }
        }
    }
}

@Composable
fun AppNavHost(container: com.tightxu.hermesremote.AppContainer) {
    val navController: NavHostController = rememberNavController()

    fun toLogin() {
        navController.navigate("login") {
            popUpTo(0) { inclusive = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "login",
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        composable("login") {
            LoginScreen(
                container = container,
                onLoggedIn = {
                    navController.navigate("sessions") {
                        popUpTo("login") { inclusive = true }
                    }
                },
            )
        }

        composable("sessions") {
            SessionsScreen(
                container = container,
                onOpenChat = { sessionId, title ->
                    navController.navigate("chat/$sessionId?title=${Uri.encode(title ?: "")}")
                },
                onOpenSettings = { navController.navigate("settings") },
                onAuthExpired = { toLogin() },
            )
        }

        composable(
            route = "chat/{sessionId}?title={title}",
            arguments = listOf(
                navArgument("sessionId") { defaultValue = "new" },
                navArgument("title") {
                    defaultValue = ""
                    nullable = true
                },
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: "new"
            val title = entry.arguments?.getString("title")?.takeIf { it.isNotBlank() }
            ChatScreen(
                container = container,
                sessionId = sessionId,
                title = title,
                onBack = { navController.popBackStack() },
                onAuthExpired = { toLogin() },
            )
        }

        composable("settings") {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onLoggedOut = { toLogin() },
                onAuthExpired = { toLogin() },
            )
        }
    }
}
