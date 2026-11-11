package com.tightxu.hermesremote.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tightxu.hermesremote.AppContainer
import com.tightxu.hermesremote.R
import com.tightxu.hermesremote.data.ApiClient
import com.tightxu.hermesremote.data.ApiException
import com.tightxu.hermesremote.data.AppSettings
import com.tightxu.hermesremote.ui.localizedApiMessage
import kotlinx.coroutines.launch

/**
 * Some IMEs insert a newline when the user taps their "enter/OK" key even in
 * a single-line field, which used to turn the password into two lines and
 * break login. Pure so it is unit-testable.
 */
fun sanitizePasswordInput(raw: String): String = raw.replace("\n", "").replace("\r", "")

/**
 * Basic-auth login. On success the server sets hermes_session_* cookies (held
 * by the encrypted cookie store) and we move on to the sessions list.
 *
 * Startup shortcut: probe a PROTECTED endpoint (GET /api/sessions?limit=1).
 * /api/status is public on authenticated gateways and therefore cannot tell
 * "already logged in" from "needs login"; a 200 from /api/sessions means the
 * cookies are valid OR the gateway runs without auth, a 401 means the form.
 *
 * Auth-less gateways (loopback / auth disabled): "Connect directly" (also the
 * plain login button with empty username AND password) skips password-login
 * entirely and enters on the same protected-endpoint probe.
 */
@Composable
fun LoginScreen(
    container: AppContainer,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val errorMissingFields = stringResource(R.string.login_error_fill_all)
    val errorAuthRequired = stringResource(R.string.login_error_auth_required)
    val errorFillServer = stringResource(R.string.login_error_fill_server)

    var settings by remember { mutableStateOf(AppSettings()) }
    var server by remember { mutableStateOf<String?>(null) } // null until loaded
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(true) }
    var savePassword by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var autoChecking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load saved form values once, then — whenever a server is saved — probe a
    // protected endpoint: 200 (valid cookies OR auth-less gateway) goes
    // straight to sessions, 401 or any failure falls back to the form.
    LaunchedEffect(Unit) {
        settings = container.settings.read()
        server = settings.server
        username = settings.username
        rememberMe = settings.remember
        savePassword = settings.savePassword
        if (savePassword) {
            password = container.secretStore.readPassword().orEmpty()
        }
        val savedServer = runCatching {
            ApiClient.normalizeBaseUrl(settings.server)
        }.getOrNull() ?: return@LaunchedEffect
        autoChecking = true
        try {
            container.api.setBaseUrl(savedServer)
            container.api.getSessions(limit = 1) // protected: 200 = in, 401 = form
            autoChecking = false
            onLoggedIn()
        } catch (_: ApiException.Unauthorized) {
            autoChecking = false // cookies expired / gateway wants credentials
        } catch (_: Exception) {
            autoChecking = false // offline / server down — manual login
        }
    }

    if (autoChecking) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    /**
     * Auth-less gateway path: never calls /auth/password-login. A 200 from the
     * protected probe means we are in; 401 means this gateway does want
     * username + password after all.
     */
    fun connectDirect() {
        if (loading) return
        val srv = server.orEmpty().trim()
        if (srv.isBlank()) {
            error = errorFillServer
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                val normalized = ApiClient.normalizeBaseUrl(srv)
                container.api.setBaseUrl(normalized)
                container.api.getSessions(limit = 1)
                container.settings.save(
                    server = srv.trim().trimEnd('/'),
                    username = username.trim(),
                    remember = rememberMe,
                    savePassword = savePassword,
                )
                loading = false
                onLoggedIn()
            } catch (e: ApiException.Unauthorized) {
                loading = false
                error = errorAuthRequired
            } catch (e: Exception) {
                loading = false
                error = localizedApiMessage(context, e)
            }
        }
    }

    fun submit() {
        if (loading) return
        val srv = server.orEmpty()
        val user = username.trim()
        // Clipboard / IME often append a trailing space or newline to a pasted
        // password; the generated dashboard password has no whitespace.
        val pass = password.trim()
        if (srv.isBlank()) {
            error = errorFillServer
            return
        }
        // Empty username AND password: treat as an auth-less gateway and
        // probe instead of calling password-login (it would just 401).
        if (user.isBlank() && pass.isBlank()) {
            connectDirect()
            return
        }
        if (user.isBlank() || pass.isBlank()) {
            error = errorMissingFields
            return
        }
        loading = true
        error = null
        scope.launch {
            val result = container.api.login(srv, user, pass)
            loading = false
            result.fold(
                onSuccess = {
                    container.settings.save(
                        server = srv.trim().trimEnd('/'),
                        username = user,
                        remember = rememberMe,
                        savePassword = savePassword,
                    )
                    if (savePassword) {
                        container.secretStore.savePassword(pass)
                    } else {
                        container.secretStore.clearPassword()
                    }
                    password = ""
                    onLoggedIn()
                },
                onFailure = { e ->
                    error = localizedApiMessage(context, e)
                },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            "hermes remote",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(40.dp))

        OutlinedTextField(
            value = server.orEmpty(),
            onValueChange = { server = it },
            label = { Text(stringResource(R.string.login_server_label)) },
            placeholder = { Text(ApiClient.SERVER_PLACEHOLDER) },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.login_username_label)) },
            singleLine = true,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            // Belt and braces against IMEs that insert \n / \r via their
            // "enter" key even in single-line fields: the password can never
            // contain a newline, so it can never wrap to a second line.
            onValueChange = { password = sanitizePasswordInput(it) },
            label = { Text(stringResource(R.string.login_password_label)) },
            singleLine = true,
            maxLines = 1,
            enabled = !loading,
            visualTransformation =
                if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    password = password.trim()
                    submit()
                    focusManager.clearFocus()
                },
            ),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(
                        stringResource(
                            if (passwordVisible) R.string.login_password_hide
                            else R.string.login_password_show
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        RowToggle(
            checked = rememberMe,
            onChange = { rememberMe = it },
            label = stringResource(R.string.login_remember),
        )
        RowToggle(
            checked = savePassword,
            onChange = { savePassword = it },
            label = stringResource(R.string.login_save_password),
        )
        Spacer(Modifier.height(20.dp))

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        Button(
            onClick = { submit() },
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                stringResource(
                    if (loading) R.string.login_submitting else R.string.login_submit
                )
            )
        }

        Spacer(Modifier.height(8.dp))
        // Discoverable path for auth-less gateways: the user may not know that
        // an empty form + "Log in" would have worked.
        OutlinedButton(
            onClick = { connectDirect() },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.login_connect_direct_hint))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(
                if (savePassword) R.string.login_password_note_saved
                else R.string.login_password_note_memory
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun RowToggle(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
