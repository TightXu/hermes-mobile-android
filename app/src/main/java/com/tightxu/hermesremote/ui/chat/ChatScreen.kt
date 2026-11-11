package com.tightxu.hermesremote.ui.chat

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.tightxu.hermesremote.AppContainer
import com.tightxu.hermesremote.KeepAliveService
import com.tightxu.hermesremote.R
import com.tightxu.hermesremote.data.ApiClient
import com.tightxu.hermesremote.data.ApiException
import com.tightxu.hermesremote.data.DisplayMessage
import com.tightxu.hermesremote.data.LeaseInfo
import com.tightxu.hermesremote.data.SessionInfo
import com.tightxu.hermesremote.data.SessionMessage
import com.tightxu.hermesremote.data.displayText
import com.tightxu.hermesremote.data.hasMorePages
import com.tightxu.hermesremote.data.mergeMessages
import com.tightxu.hermesremote.data.toDisplayMessages
import com.tightxu.hermesremote.ui.localizedApiMessage
import com.tightxu.hermesremote.ui.theme.BgDark
import com.tightxu.hermesremote.ui.theme.StatusError
import com.tightxu.hermesremote.ui.theme.StatusOk
import com.tightxu.hermesremote.ui.theme.StatusWarn
import com.tightxu.hermesremote.ui.theme.SurfaceElevated
import com.tightxu.hermesremote.ui.theme.TextMuted
import io.noties.markwon.Markwon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val PAGE_SIZE = 30
private const val EMPTY_PAGE_PREFETCH = 3 // extra pages when page 1 shows nothing readable
private const val REPLY_POLL_INTERVAL_MS = 1_500L
private const val REPLY_TIMEOUT_MS = 90_000L
private const val DISCOVERY_POLL_INTERVAL_MS = 1_000L
private const val DISCOVERY_TIMEOUT_MS = 30_000L

/**
 * JS → Kotlin bridge for the terminal page. Callbacks arrive on WebView's
 * JavaBridge thread; Compose snapshot writes are thread-safe, so the lambda
 * just assigns state.
 */
class SocketStateBridge(val onState: (String) -> Unit) {
    @JavascriptInterface
    fun onSocketState(state: String) {
        onState(state)
    }
}

/**
 * Native chat page over the gateway.
 *
 *  - The message list is a Compose LazyColumn fed by the REST messages API
 *    (paged, newest page first, older pages lazily prepended).
 *  - The xterm WebView is still here but INVISIBLE (alpha 0, behind the
 *    opaque list): it owns the PTY WebSocket, which remains the send channel
 *    for new messages. It never gets focus, so the keyboard stays on the
 *    native input bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String, // "new" or a real session id
    title: String?,
    onBack: () -> Unit,
    onAuthExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isNew = sessionId == "new"
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    var reconnectAttempt by remember { mutableIntStateOf(0) }

    // One Markwon pipeline for the whole chat page (shared by every
    // assistant bubble) — see MarkdownText.
    val markwon = rememberMarkwon(context)

    // PTY identity: fresh channel per chat page; attach token reused across
    // reconnects within this page (server maps attach token → PTY). Minted
    // once per entry, never across chat pages.
    val channel = remember(sessionId) {
        (if (isNew) "chat-fresh-" else "chat-") + UUID.randomUUID()
    }
    val attachToken = remember(sessionId) { randomAttachToken() }

    var socketState by remember { mutableStateOf("idle") } // idle|connecting|open|closed|error
    var connectError by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }

    // ── message state (REST-backed list) ──────────────────────────────────
    var activeSessionId by remember { mutableStateOf<String?>(if (isNew) null else sessionId) }
    var chatTitle by remember { mutableStateOf(title) }
    var messages by remember { mutableStateOf<List<SessionMessage>>(emptyList()) } // ascending
    var initialLoading by remember { mutableStateOf(!isNew) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var rawOffset by remember { mutableIntStateOf(0) } // accumulates pagination.returned
    var hasMore by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    var waitingForReply by remember { mutableStateOf(false) }
    var sessionCreating by remember { mutableStateOf(false) }
    var nextOptimisticId by remember { mutableIntStateOf(-1) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    var discoveryJob by remember { mutableStateOf<Job?>(null) }

    // ── session-lock state (optional plugin; silent on failure) ───────────
    var sessionLease by remember { mutableStateOf<LeaseInfo?>(null) }
    var showLeaseConfirm by remember { mutableStateOf(false) }
    var releasingLease by remember { mutableStateOf(false) }

    val displayMessages = remember(messages) { toDisplayMessages(messages) } // ascending
    val displayNewestFirst = remember(displayMessages) { displayMessages.asReversed() }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var everConnected by remember { mutableStateOf(false) }

    val bridge = remember { SocketStateBridge { state -> socketState = state } }

    // ── connect / reconnect (PTY = send channel) ──────────────────────────
    // The ticket is single-use (30s TTL): mint a fresh one for every connect.
    fun connect() {
        if (!pageLoaded) return
        scope.launch {
            socketState = "connecting"
            connectError = null
            try {
                val ticket = container.api.getWsTicket()
                val url = ApiClient.buildPtyUrl(
                    baseUrl = container.api.baseUrl,
                    // After a new chat is discovered, reconnects must resume
                    // that session, not mint yet another fresh one.
                    sessionId = activeSessionId ?: if (isNew) null else sessionId,
                    channel = channel,
                    attach = attachToken,
                    ticket = ticket,
                )
                webViewRef.value?.evaluateJavascript(
                    "hermesConnect(${JSONObject.quote(url)})",
                    null,
                )
            } catch (e: ApiException.Unauthorized) {
                onAuthExpired()
            } catch (e: Exception) {
                socketState = "error"
                connectError = localizedApiMessage(context, e)
            }
        }
    }

    // ── auto-reconnect (screen on, app resumed) ───────────────────────────
    // The WS is single-use-ticket; every attempt calls connect() for a fresh
    // ticket. Backoff 1s,2s,4s,8s,16s,30s; reset on open.
    LaunchedEffect(socketState, isResumed) {
        when (socketState) {
            "open" -> reconnectAttempt = 0
            "closed", "error" -> {
                if (!isResumed || reconnectAttempt >= 6) return@LaunchedEffect
                delay((1000L shl reconnectAttempt).coerceAtMost(30_000L))
                reconnectAttempt += 1
                connect()
            }
        }
    }

    // ── message loading ───────────────────────────────────────────────────
    /**
     * Fetch the newest page (plus up to [EMPTY_PAGE_PREFETCH] more pages when
     * the newest one contains nothing readable — all tool traffic) and merge
     * into the local list. Resets the pagination cursor.
     */
    suspend fun fetchNewestPage(sid: String) {
        var offset = 0
        var page = container.api.getMessagesPage(sid, PAGE_SIZE, offset, "latest")
        var collected = page.messages
        var prefetch = 0
        while (
            prefetch < EMPTY_PAGE_PREFETCH &&
            toDisplayMessages(collected).isEmpty() &&
            hasMorePages(page.pagination?.returned ?: 0, PAGE_SIZE)
        ) {
            offset += page.pagination?.returned ?: 0
            page = container.api.getMessagesPage(sid, PAGE_SIZE, offset, "latest")
            collected = page.messages + collected
            prefetch++
        }
        rawOffset = offset + (page.pagination?.returned ?: 0)
        hasMore = hasMorePages(page.pagination?.returned ?: 0, PAGE_SIZE)
        messages = mergeMessages(messages, collected)
    }

    fun reloadMessages() {
        val sid = activeSessionId ?: return
        pollJob?.cancel()
        initialLoading = true
        loadError = null
        scope.launch {
            try {
                fetchNewestPage(sid)
            } catch (e: ApiException.Unauthorized) {
                onAuthExpired()
            } catch (e: Exception) {
                loadError = localizedApiMessage(context, e)
            } finally {
                initialLoading = false
            }
        }
    }

    fun loadOlder() {
        val sid = activeSessionId ?: return
        if (loadingOlder || !hasMore || initialLoading) return
        loadingOlder = true
        scope.launch {
            try {
                // Offset accumulates by pagination.returned (raw count incl.
                // tool messages), NOT by the number of visible bubbles.
                val page = container.api.getMessagesPage(sid, PAGE_SIZE, rawOffset, "latest")
                rawOffset += page.pagination?.returned ?: 0
                hasMore = hasMorePages(page.pagination?.returned ?: 0, PAGE_SIZE)
                messages = mergeMessages(messages, page.messages)
            } catch (e: ApiException.Unauthorized) {
                onAuthExpired()
            } catch (e: Exception) {
                loadError = localizedApiMessage(context, e)
            } finally {
                loadingOlder = false
            }
        }
    }

    // ── session lock: detect + release (explicit, never automatic) ────────
    // Checked once per active session (existing session on entry, new session
    // after discovery). Plugin 404 / network failure → no banner, silently.
    LaunchedEffect(activeSessionId, isResumed) {
        val sid = activeSessionId
        if (!isResumed) return@LaunchedEffect
        sessionLease = null
        if (sid == null) return@LaunchedEffect
        sessionLease = try {
            container.api.getLeases().entries.firstOrNull { it.sessionId == sid }
        } catch (_: Exception) {
            null
        }
    }

    fun releaseCurrentLease() {
        val sid = activeSessionId ?: return
        releasingLease = true
        scope.launch {
            try {
                val resp = container.api.releaseLease(
                    scope = "session",
                    sessionId = sid,
                    // scope=session always requires confirm=true server-side,
                    // whether the owner is alive or dead.
                    confirm = true,
                )
                if (resp.ok) {
                    sessionLease = null
                    reconnectAttempt = 0
                    connect()
                    reloadMessages()
                }
            } catch (e: ApiException.Unauthorized) {
                onAuthExpired()
            } catch (_: Exception) {
                // Release is best-effort from the banner; keep the banner so
                // the user can retry via the sessions screen's lock sheet.
            } finally {
                releasingLease = false
            }
        }
    }

    // ── reply polling (after each send) ───────────────────────────────────
    fun startReplyPolling(sid: String) {
        waitingForReply = true
        pollJob = scope.launch {
            val knownIds = messages.mapNotNull { it.id }.filter { it > 0 }.toSet()
            val startedAt = System.currentTimeMillis()
            try {
                while (isActive && System.currentTimeMillis() - startedAt < REPLY_TIMEOUT_MS) {
                    delay(REPLY_POLL_INTERVAL_MS)
                    try {
                        val page = container.api.getMessagesPage(sid, PAGE_SIZE, 0, "latest")
                        messages = mergeMessages(messages, page.messages)
                        val arrived = page.messages.any { msg ->
                            msg.id != null && msg.id > 0 && msg.id !in knownIds &&
                                msg.role == "assistant" && msg.displayText() != null
                        }
                        if (arrived) return@launch
                    } catch (e: ApiException.Unauthorized) {
                        onAuthExpired()
                        return@launch
                    } catch (e: Exception) {
                        // transient network hiccup: keep polling until timeout
                    }
                }
            } finally {
                waitingForReply = false
            }
        }
    }

    // ── new-session discovery (first send on a "new" page) ────────────────
    // Do not diff against a baseline captured at page-open: the PTY can
    // create the session on connect, before the send, so that baseline may
    // already contain it. Instead, identify the session by the message we
    // actually sent (title or first-page user text). The new session is the
    // newest match, so a same-text older session cannot win.
    fun startDiscovery(sentText: String) {
        sessionCreating = true
        discoveryJob = scope.launch {
            val target = normalizeForMatch(sentText)
            var found: SessionInfo? = null
            val startedAt = System.currentTimeMillis()
            try {
                while (isActive && found == null &&
                    System.currentTimeMillis() - startedAt < DISCOVERY_TIMEOUT_MS
                ) {
                    delay(DISCOVERY_POLL_INTERVAL_MS)
                    try {
                        val sessions = container.api.getSessions(limit = 20, order = "created")
                        for (candidate in sessions) {
                            val titleMatch = candidate.title?.let { normalizeForMatch(it) }
                                ?.let { it == target || target.startsWith(it) } == true
                            // order=oldest: the sent message is at the start of
                            // a brand-new session, even after a burst of tool
                            // messages pushed it out of the latest page.
                            val firstPage = container.api.getMessagesPage(
                                candidate.id, limit = 10, offset = 0, order = "oldest",
                            )
                            val messageMatch = firstPage.messages.any {
                                it.role == "user" && normalizeForMatch(it.content) == target
                            }
                            if (titleMatch || messageMatch) {
                                found = candidate
                                break
                            }
                        }
                    } catch (e: ApiException.Unauthorized) {
                        onAuthExpired()
                        return@launch
                    } catch (e: Exception) {
                        // transient; try again next tick
                    }
                }
            } finally {
                sessionCreating = false
            }
            if (found == null) {
                sendError = context.getString(R.string.chat_discovery_timeout)
                return@launch
            }
            activeSessionId = found.id
            found.title?.takeIf { it.isNotBlank() }?.let { chatTitle = it }
            try {
                initialLoading = true
                fetchNewestPage(found.id)
                // The reply may already be in the fetched page (fast model or
                // a tool-free answer). In that case do not spin "replying"
                // for 90s: compare the latest user id with assistant ids.
                val lastUserId = messages.lastOrNull { it.role == "user" }?.id ?: 0
                val hasReply = messages.any {
                    it.role == "assistant" && it.displayText() != null &&
                        (it.id ?: 0) > lastUserId
                }
                if (!hasReply) startReplyPolling(found.id)
            } catch (e: ApiException.Unauthorized) {
                onAuthExpired()
            } catch (e: Exception) {
                loadError = localizedApiMessage(context, e)
            } finally {
                initialLoading = false
            }
        }
    }

    // ── send (native bar → PTY; the only input path) ──────────────────────
    fun onSendDelivered(text: String) {
        // A new send cancels any previous polling round and starts over.
        pollJob?.cancel()
        discoveryJob?.cancel()
        // Optimistic local echo; superseded by the real message once the
        // server round-trips it (see mergeMessages).
        messages = mergeMessages(
            messages,
            listOf(
                SessionMessage(
                    role = "user",
                    content = text,
                    timestamp = System.currentTimeMillis() / 1000.0,
                    id = nextOptimisticId--,
                ),
            ),
        )
        val sid = activeSessionId
        if (sid != null) {
            startReplyPolling(sid)
        } else if (isNew) {
            startDiscovery(text)
        }
    }

    fun doSend(text: String) {
        val webView = webViewRef.value
        if (webView == null || !pageLoaded) {
            sendError = context.getString(R.string.chat_connecting_wait)
            return
        }
        webView.evaluateJavascript("hermesSend(${JSONObject.quote(text)})") { result ->
            if (result == "true") {
                input = ""
                onSendDelivered(text)
            } else {
                sendError = context.getString(R.string.chat_not_connected)
            }
        }
    }

    fun send() {
        val text = input
        if (text.isBlank()) return
        sendError = null
        doSend(text)
    }

    // ── initial load ──────────────────────────────────────────────────────
    LaunchedEffect(sessionId) {
        if (!isNew) reloadMessages()
    }

    // Page destroyed: stop polling alongside the WebView teardown below.
    DisposableEffect(Unit) {
        onDispose {
            pollJob?.cancel()
            discoveryJob?.cancel()
        }
    }

    // Lock screen / app switch: Android may freeze the process and drop the
    // socket. Hold a short foreground keep-alive while stopped; on return,
    // drop it, reconnect immediately and reload the REST message page.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // ON_PAUSE (not ON_STOP) is the last point where the app is
                // still foreground enough to start a foreground service on
                // Android 12+; the screen lock / app switch fires it.
                Lifecycle.Event.ON_RESUME -> {
                    isResumed = true
                    reconnectAttempt = 0
                    KeepAliveService.stop(context)
                    if (pageLoaded) {
                        connect()
                        reloadMessages()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isResumed = false
                    KeepAliveService.start(context)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            KeepAliveService.stop(context)
        }
    }

    // ── terminal host (hidden send channel) ───────────────────────────────
    var fitJob by remember { mutableStateOf<Job?>(null) }
    val requestFit: () -> Unit = {
        // Debounced: keyboard/rotation settle in bursts of size changes.
        fitJob?.cancel()
        fitJob = scope.launch {
            delay(300)
            webViewRef.value?.evaluateJavascript("hermesFit()", null)
        }
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
                title = {
                    Text(
                        when {
                            isNew && activeSessionId == null -> stringResource(R.string.chat_new)
                            !chatTitle.isNullOrBlank() -> chatTitle!!
                            isNew -> stringResource(R.string.chat_new)
                            else -> stringResource(R.string.chat_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    ConnectionIndicator(socketState, connectError)
                    IconButton(onClick = {
                        reconnectAttempt = 0
                        connect()
                        reloadMessages()
                    }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = BgDark,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Session-lock banner: above the list, only when this chat's
            // session is leased by another client (desktop that didn't exit,
            // dead remote, …).
            sessionLease?.let { lease ->
                SessionLockBanner(
                    lease = lease,
                    sessionTitle = chatTitle,
                    connectTrouble = connectError != null,
                    releasing = releasingLease,
                    onRelease = {
                        // Live owners get an explicit warning first; dead
                        // owners go straight through (still confirm=true
                        // server-side, which scope=session requires).
                        if (lease.alive) showLeaseConfirm = true else releaseCurrentLease()
                    },
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { requestFit() },
            ) {
                // Hidden PTY/xterm WebView: loads, connects and sends, but is
                // fully transparent and covered by the opaque message list.
                TerminalWebView(
                    bridge = bridge,
                    webViewRef = webViewRef,
                    onPageLoaded = {
                        pageLoaded = true
                        if (!everConnected) {
                            everConnected = true
                            connect()
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0f),
                )

                MessageList(
                    newestFirst = displayNewestFirst,
                    markwon = markwon,
                    initialLoading = initialLoading,
                    loadingOlder = loadingOlder,
                    hasMore = hasMore,
                    loadError = loadError,
                    onLoadOlder = { loadOlder() },
                    onRetry = {
                        if (messages.isEmpty()) reloadMessages() else loadOlder()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgDark),
                )
            }

            // Native input bar — the app's entire keyboard story.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (waitingForReply || sessionCreating) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (sessionCreating) R.string.chat_creating else R.string.chat_replying
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
                sendError?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { sendError = null; send() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                        singleLine = false,
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { send() },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showLeaseConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaseConfirm = false },
            title = { Text(stringResource(R.string.locks_confirm_title)) },
            text = { Text(stringResource(R.string.locks_confirm_alive_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaseConfirm = false
                    releaseCurrentLease()
                }) {
                    Text(stringResource(R.string.locks_release))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaseConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    BackHandler { onBack() }
}

/** Compact banner: this session is leased elsewhere; release is explicit. */
@Composable
private fun SessionLockBanner(
    lease: LeaseInfo,
    sessionTitle: String?,
    connectTrouble: Boolean,
    releasing: Boolean,
    onRelease: () -> Unit,
) {
    val displayTitle = lease.title?.takeIf { it.isNotBlank() }
        ?: sessionTitle?.takeIf { it.isNotBlank() }
        ?: lease.sessionId.ifBlank { "?" }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        R.string.chat_lock_banner_title,
                        displayTitle,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(
                        R.string.chat_lock_banner_owner,
                        lease.surface?.takeIf { it.isNotBlank() } ?: "?",
                        lease.pid ?: 0,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                if (connectTrouble) {
                    Text(
                        stringResource(R.string.chat_lock_banner_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
            TextButton(onClick = onRelease, enabled = !releasing) {
                if (releasing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.chat_lock_release))
                }
            }
        }
    }
}

/** Scrollable message list. [newestFirst][0] is the newest (bottom) message. */
@Composable
private fun MessageList(
    newestFirst: List<DisplayMessage>,
    markwon: Markwon,
    initialLoading: Boolean,
    loadingOlder: Boolean,
    hasMore: Boolean,
    loadError: String?,
    onLoadOlder: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // reverseLayout=true: index 0 sits at the BOTTOM; the visual top is the
    // end of the list. Reaching it (last visible index near the end) is the
    // "load older" trigger. The effect restarts when hasMore/loadingOlder
    // change, so holding the finger at the top chains page after page.
    LaunchedEffect(hasMore, loadingOlder) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .collect { nearTop ->
                if (nearTop && hasMore && !loadingOlder) onLoadOlder()
            }
    }

    // Jump to the newest message whenever a NEW message id appears at the
    // bottom (send/receive) — but not when older pages merely extend the top.
    val newestId = newestFirst.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null) listState.scrollToItem(0)
    }

    BoxWithConstraints(modifier) {
        val bubbleMaxWidth = maxWidth * 0.85f

        when {
            initialLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            loadError != null && newestFirst.isEmpty() ->
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        loadError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }

            newestFirst.isEmpty() -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(
                        if (hasMore) R.string.chat_no_readable else R.string.chat_empty
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
                if (hasMore) {
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(onClick = onLoadOlder, enabled = !loadingOlder) {
                        Text(
                            stringResource(
                                if (loadingOlder) R.string.loading else R.string.chat_load_older
                            )
                        )
                    }
                }
            }

            else -> LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(newestFirst, key = { it.id }) { msg ->
                    MessageBubble(msg, markwon, bubbleMaxWidth)
                }
                // Visually the top of the list (end of a reversed list).
                if (loadingOlder) {
                    item(key = "older-spinner") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.chat_loading_older),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                        }
                    }
                } else if (loadError != null && hasMore) {
                    item(key = "older-error") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                loadError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = onRetry) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One chat bubble. User: plain text (their `*`/backticks must never be
 * formatted) in a SelectionContainer. Assistant: Markdown via the shared
 * Markwon instance — the TextView itself is selectable, so no
 * SelectionContainer (the two would fight over the movement method).
 */
@Composable
private fun MessageBubble(
    message: DisplayMessage,
    markwon: Markwon,
    maxWidth: Dp,
) {
    val isUser = message.role == "user"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser) {
            SelectionContainer {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.widthIn(max = maxWidth),
                ) {
                    Text(
                        message.text,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            Surface(
                color = SurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = maxWidth),
            ) {
                MarkdownText(
                    markwon = markwon,
                    text = message.text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        message.timestamp?.let { ts ->
            val timeText = remember(ts) { formatTimestamp(ts) }
            Text(
                timeText,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Normalize a sent draft / stored title or message for new-session matching. */
private fun normalizeForMatch(raw: String): String =
    raw.trim().replace(Regex("\\s+"), " ").take(500)

private fun formatTimestamp(epochSeconds: Double): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))

/** Status dot + label in the app bar (PTY send-channel health). */
@Composable
private fun ConnectionIndicator(socketState: String, connectError: String?) {
    val errorFallback = stringResource(R.string.conn_error)
    val (color, label) = when (socketState) {
        "open" -> StatusOk to stringResource(R.string.conn_connected)
        "connecting" -> StatusWarn to stringResource(R.string.conn_connecting)
        "closed" -> StatusError to stringResource(R.string.conn_disconnected)
        "error" -> StatusError to (connectError ?: errorFallback)
        else -> TextMuted to stringResource(R.string.conn_idle)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * The xterm WebView — hidden send channel. Loads terminal.html through
 * WebViewAssetLoader (https://appassets.androidplatform.net/assets/) so
 * relative script srcs resolve under an https origin. Teardown happens in
 * onDispose below.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun TerminalWebView(
    bridge: SocketStateBridge,
    webViewRef: androidx.compose.runtime.MutableState<WebView?>,
    onPageLoaded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()

            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // Keyboard goes to the native bar, never here.
                isFocusable = false
                isFocusableInTouchMode = false

                addJavascriptInterface(bridge, "AndroidChat")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): Boolean = true // terminal stays local; block any navigation

                    override fun onPageFinished(view: WebView, url: String?) {
                        onPageLoaded()
                    }
                }

                loadUrl("https://appassets.androidplatform.net/assets/terminal.html")
                webViewRef.value = this
            }
        },
        modifier = modifier,
    )

    DisposableEffect(Unit) {
        onDispose {
            // Leaving the page: close the PTY socket, kill the WebView. The
            // attach token dies with it — next chat page mints a new one.
            webViewRef.value?.let { view ->
                runCatching { view.evaluateJavascript("hermesDisconnect()", null) }
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
            }
            webViewRef.value = null
        }
    }
}

private fun randomAttachToken(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
