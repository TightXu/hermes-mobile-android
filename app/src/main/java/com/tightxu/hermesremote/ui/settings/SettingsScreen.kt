package com.tightxu.hermesremote.ui.settings

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.tightxu.hermesremote.AppContainer
import com.tightxu.hermesremote.R
import com.tightxu.hermesremote.data.ApiException
import com.tightxu.hermesremote.data.AppSettings
import com.tightxu.hermesremote.data.StatusResponse
import com.tightxu.hermesremote.ui.localizedApiMessage
import com.tightxu.hermesremote.ui.theme.ThemeAccent
import kotlinx.coroutines.launch

/**
 * Status / settings: account info, appearance (accent + language), security,
 * gateway status card, logout. Read-only view over /api/status — platform
 * entries render as name: state pairs with whatever the server sends.
 */

/** Language preference backing the settings picker. */
enum class AppLanguage {
    /** Empty app locales — Android resolves per system setting. */
    SYSTEM,

    /** Explicit zh-CN. */
    ZH,

    /** Explicit en. */
    EN,
}

/** Map the persisted per-app locales to the picker's three options. Pure. */
fun currentAppLanguage(locales: LocaleListCompat = AppCompatDelegate.getApplicationLocales()): AppLanguage {
    if (locales.isEmpty) return AppLanguage.SYSTEM
    val tag = locales.toLanguageTags()
    return when {
        tag.startsWith("zh", ignoreCase = true) -> AppLanguage.ZH
        else -> AppLanguage.EN
    }
}

/** Apply a language choice as per-app locales (empty list = follow system). */
fun applyAppLanguage(choice: AppLanguage) {
    val locales = when (choice) {
        AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
        AppLanguage.ZH -> LocaleListCompat.forLanguageTags("zh-CN")
        AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
    }
    AppCompatDelegate.setApplicationLocales(locales)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onAuthExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var settings by remember { mutableStateOf(AppSettings()) }
    var status by remember { mutableStateOf<StatusResponse?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var statusLoading by remember { mutableStateOf(true) }
    // Local echo of the persisted theme so the radio marks update instantly;
    // MainActivity collects the same Flow, so colors switch immediately too.
    var themeKey by remember { mutableStateOf("yellow") }
    var savePassword by remember { mutableStateOf(true) }
    // Read on every composition: after setApplicationLocales + recreate this
    // is a brand-new composition, and the cheap read keeps the mark correct.
    val language = currentAppLanguage()

    LaunchedEffect(Unit) {
        settings = container.settings.read()
        themeKey = settings.theme
        savePassword = settings.savePassword
        statusLoading = true
        statusError = null
        try {
            status = container.api.getStatus()
        } catch (e: ApiException.Unauthorized) {
            onAuthExpired()
        } catch (e: Exception) {
            statusError = localizedApiMessage(context, e)
        } finally {
            statusLoading = false
        }
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    fun pickLanguage(choice: AppLanguage) {
        applyAppLanguage(choice)
        // Compose can't hot-swap stringResource trees for a locale change;
        // recreate() rebuilds the activity under the new configuration.
        (context as? Activity)?.recreate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_section_account))
            InfoRow(stringResource(R.string.settings_server), settings.server.ifBlank { "—" })
            InfoRow(stringResource(R.string.settings_username), settings.username.ifBlank { "—" })
            InfoRow(stringResource(R.string.settings_version), "v$versionName")

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_section_appearance))
            ThemeAccent.entries.forEach { accent ->
                val selected = themeKey == accent.key
                SelectableRow(
                    selected = selected,
                    onClick = {
                        themeKey = accent.key
                        scope.launch { container.settings.setTheme(accent.key) }
                    },
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(accent.color),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(accent.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        SelectedCheck()
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_section_language))
            SelectableRow(
                selected = language == AppLanguage.SYSTEM,
                onClick = { pickLanguage(AppLanguage.SYSTEM) },
            ) {
                Text(
                    stringResource(R.string.lang_system),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (language == AppLanguage.SYSTEM) SelectedCheck()
            }
            SelectableRow(
                selected = language == AppLanguage.ZH,
                onClick = { pickLanguage(AppLanguage.ZH) },
            ) {
                Text(
                    stringResource(R.string.lang_zh),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (language == AppLanguage.ZH) SelectedCheck()
            }
            SelectableRow(
                selected = language == AppLanguage.EN,
                onClick = { pickLanguage(AppLanguage.EN) },
            ) {
                Text(
                    stringResource(R.string.lang_en),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (language == AppLanguage.EN) SelectedCheck()
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.settings_section_security))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_save_password),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = savePassword,
                    onCheckedChange = { checked ->
                        savePassword = checked
                        scope.launch {
                            container.settings.setSavePassword(checked)
                            if (!checked) container.secretStore.clearPassword()
                        }
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.settings_section_gateway))

            when {
                statusLoading -> Text(
                    stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                statusError != null -> Column {
                    Text(
                        statusError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            statusLoading = true
                            statusError = null
                            try {
                                status = container.api.getStatus()
                            } catch (e: Exception) {
                                statusError = localizedApiMessage(context, e)
                            } finally {
                                statusLoading = false
                            }
                        }
                    }) { Text(stringResource(R.string.action_retry)) }
                }

                status != null -> {
                    val s = status!!
                    InfoRow("version", s.version ?: "—")
                    InfoRow("gateway_running", s.gatewayRunning?.toString() ?: "—")
                    InfoRow("gateway_state", s.gatewayState ?: "—")
                    val platforms = s.gatewayPlatforms
                    if (platforms != null && platforms.isNotEmpty()) {
                        Text(
                            "gateway_platforms",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                        platforms.forEach { (name, value) ->
                            val state = runCatching {
                                value.let {
                                    (it as? kotlinx.serialization.json.JsonObject)
                                        ?.get("state")
                                        ?.let { el -> (el as? kotlinx.serialization.json.JsonPrimitive)?.content }
                                }
                            }.getOrNull()
                            Text(
                                if (state != null) "$name: $state" else "$name: $value",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    scope.launch {
                        container.api.logout()
                        onLoggedOut()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_logout))
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SelectedCheck() {
    Icon(
        Icons.Filled.Check,
        contentDescription = stringResource(R.string.settings_selected),
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** Shared row shape for the theme swatches and the language options. */
@Composable
private fun SelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { if (!selected) onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp, top = 6.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
        )
    }
}
