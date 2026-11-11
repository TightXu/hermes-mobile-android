package com.tightxu.hermesremote.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tightxu.hermesremote.AppContainer
import com.tightxu.hermesremote.R
import com.tightxu.hermesremote.data.ApiException
import com.tightxu.hermesremote.data.LeaseInfo
import com.tightxu.hermesremote.data.LeaseResponse
import com.tightxu.hermesremote.data.SessionInfo
import com.tightxu.hermesremote.ui.leaseAgeAsEpoch
import com.tightxu.hermesremote.ui.localizedApiMessage
import com.tightxu.hermesremote.ui.timeAgoText
import com.tightxu.hermesremote.ui.theme.StatusOk
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    container: AppContainer,
    onOpenChat: (sessionId: String, title: String?) -> Unit,
    onOpenSettings: () -> Unit,
    onAuthExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var sessions by remember { mutableStateOf<List<SessionInfo>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // ── session locks (optional plugin) ───────────────────────────────────
    var leases by remember { mutableStateOf<LeaseResponse?>(null) }
    // After a 404 the entry point disappears until the next successful poll;
    // the session list itself is never blocked by lock failures.
    var lockPluginAvailable by remember { mutableStateOf(true) }
    var showLocks by remember { mutableStateOf(false) }
    var releaseBusy by remember { mutableStateOf(false) }
    var releaseResult by remember { mutableStateOf<Int?>(null) }
    var releaseError by remember { mutableStateOf<String?>(null) }
    // scope+sessionId awaiting the user's confirmation (live owners / all).
    var pendingRelease by remember { mutableStateOf<Pair<String, String?>?>(null) }

    /** Silent background refresh: 404 hides the entry, other failures ignored. */
    fun loadLeases() {
        scope.launch {
            try {
                leases = container.api.getLeases()
                lockPluginAvailable = true
            } catch (_: ApiException.PluginUnavailable) {
                lockPluginAvailable = false
            } catch (_: Exception) {
                // silent: the lock sheet is optional chrome
            }
        }
    }

    fun load() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                sessions = container.api.getSessions()
            } catch (e: ApiException.Unauthorized) {
                onAuthExpired()
            } catch (e: Exception) {
                error = localizedApiMessage(context, e)
            } finally {
                loading = false
            }
        }
        loadLeases()
    }

    // Refresh on every return (e.g. back from chat after releasing a lock)
    // and on the first resume; the lock count/session badges must not go stale.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { load() }

    /**
     * Execute a release and refresh local state from the response's
     * `remaining` list. Errors surface inside the sheet only.
     */
    fun performRelease(scopeName: String, sessionId: String?) {
        if (releaseBusy) return
        releaseBusy = true
        releaseError = null
        scope.launch {
            try {
                val resp = container.api.releaseLease(
                    scope = scopeName,
                    sessionId = sessionId,
                    confirm = true, // required for session/all; harmless for dead
                )
                leases = LeaseResponse(
                    path = resp.path,
                    count = resp.remaining.size,
                    entries = resp.remaining,
                )
                releaseResult = resp.removed.size
            } catch (e: ApiException.Unauthorized) {
                onAuthExpired()
            } catch (e: Exception) {
                releaseError = localizedApiMessage(context, e)
            } finally {
                releaseBusy = false
            }
        }
    }

    val leaseCount = leases?.count ?: 0
    val lockedSessionIds = remember(leases) {
        leases?.entries?.mapNotNull { it.sessionId.takeIf { s -> s.isNotBlank() } }?.toSet()
            ?: emptySet()
    }
    // Fallback for gateways whose plugin predates the `title` field: reuse the
    // titles already loaded for the session list, keyed by id.
    val sessionTitles = remember(sessions) {
        sessions.orEmpty()
            .mapNotNull { s -> s.title?.takeIf { it.isNotBlank() }?.let { s.id to it } }
            .toMap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sessions_title)) },
                actions = {
                    if (lockPluginAvailable) {
                        IconButton(onClick = {
                            releaseResult = null
                            releaseError = null
                            loadLeases()
                            showLocks = true
                        }) {
                            BadgedBox(
                                badge = {
                                    if (leaseCount > 0) {
                                        Badge { Text("$leaseCount") }
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = stringResource(R.string.locks_title),
                                )
                            }
                        }
                    }
                    IconButton(onClick = { load() }, enabled = !loading) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onOpenChat("new", null) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = stringResource(R.string.chat_new),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val list = sessions

        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { load() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                list == null && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                list != null && list.isEmpty() && !loading && error == null ->
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.sessions_empty_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sessions_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                error != null && list == null ->
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { load() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }

                list != null -> Column(Modifier.fillMaxSize()) {
                    // A failed refresh shouldn't wipe the list we already have.
                    if (error != null) {
                        Text(
                            error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { session ->
                            SessionRow(session, locked = session.id in lockedSessionIds) {
                                onOpenChat(session.id, session.title)
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLocks) {
        LocksSheet(
            leases = leases,
            sessionTitles = sessionTitles,
            releaseBusy = releaseBusy,
            releaseResult = releaseResult,
            releaseError = releaseError,
            onDismiss = { showLocks = false },
            onRelease = { lease ->
                // Live owners always get the explicit two-writers warning;
                // dead owners release immediately.
                if (lease.alive) pendingRelease = "session" to lease.sessionId
                else performRelease("session", lease.sessionId)
            },
            onClearDead = { performRelease("dead", null) },
            onReleaseAll = { pendingRelease = "all" to null },
        )
    }

    pendingRelease?.let { (pendingScope, pendingSessionId) ->
        val all = pendingScope == "all"
        AlertDialog(
            onDismissRequest = { pendingRelease = null },
            title = {
                Text(
                    stringResource(
                        if (all) R.string.locks_confirm_all_title else R.string.locks_confirm_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (all) R.string.locks_confirm_all_body
                        else R.string.locks_confirm_alive_body
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRelease = null
                    performRelease(pendingScope, pendingSessionId)
                }) {
                    Text(stringResource(R.string.locks_release))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRelease = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Bottom sheet over the lease registry: list, per-row release, dead/all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocksSheet(
    leases: LeaseResponse?,
    sessionTitles: Map<String, String>,
    releaseBusy: Boolean,
    releaseResult: Int?,
    releaseError: String?,
    onDismiss: () -> Unit,
    onRelease: (LeaseInfo) -> Unit,
    onClearDead: () -> Unit,
    onReleaseAll: () -> Unit,
) {
    val entries = leases?.entries.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.locks_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))

            releaseResult?.let { n ->
                Text(
                    stringResource(R.string.locks_released_count, n),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            releaseError?.let {
                Text(
                    stringResource(R.string.locks_release_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (entries.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.locks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    entries.forEach { lease ->
                        LeaseRow(lease, sessionTitles, releaseBusy) { onRelease(lease) }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onClearDead, enabled = !releaseBusy && entries.isNotEmpty()) {
                    Text(stringResource(R.string.locks_clear_dead))
                }
                TextButton(onClick = onReleaseAll, enabled = !releaseBusy && entries.isNotEmpty()) {
                    Text(stringResource(R.string.locks_release_all))
                }
            }
        }
    }
}

/** One lease: session name (id secondary), surface, pid, age, liveness + release. */
@Composable
private fun LeaseRow(
    lease: LeaseInfo,
    sessionTitles: Map<String, String>,
    releaseBusy: Boolean,
    onRelease: () -> Unit,
) {
    val ageText = leaseAgeText(lease)
    val resolvedTitle = lease.title?.takeIf { it.isNotBlank() }
        ?: sessionTitles[lease.sessionId]?.takeIf { it.isNotBlank() }
    val shortId = shortenSessionId(lease.sessionId)
    val primary = resolvedTitle ?: if (shortId.isBlank()) stringResource(R.string.locks_untitled) else shortId
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                primary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            LivenessChip(lease.alive)
            Spacer(Modifier.width(8.dp))
            if (releaseBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TextButton(onClick = onRelease) {
                    Text(stringResource(R.string.locks_release))
                }
            }
        }
        if (resolvedTitle != null && lease.sessionId.isNotBlank()) {
            Text(
                stringResource(R.string.locks_session_id, shortenSessionId(lease.sessionId)),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            lease.surface?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
            }
            lease.pid?.let {
                Text(
                    stringResource(R.string.locks_pid, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            }
            if (ageText.isNotBlank()) {
                Text(
                    ageText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** alive → green dot + label; dead → muted label. */
@Composable
private fun LivenessChip(alive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (alive) StatusOk else MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(if (alive) R.string.locks_alive else R.string.locks_dead),
            style = MaterialTheme.typography.labelMedium,
            color = if (alive) StatusOk else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Lease age rendered through the localized relative-time buckets. */
@Composable
private fun leaseAgeText(lease: LeaseInfo): String =
    timeAgoText(leaseAgeAsEpoch(lease.ageSeconds))

/** "20261006_223450_ff4bdb" → "20261006…ff4bdb" (fits narrow sheets). */
private fun shortenSessionId(id: String): String = when {
    id.length <= 16 -> id
    else -> id.take(8) + "…" + id.takeLast(6)
}

@Composable
private fun SessionRow(session: SessionInfo, locked: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (session.isActive == true) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(StatusOk)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                session.title?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.sessions_untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (locked) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.sessions_locked_session),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                timeAgoText(session.lastActive),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            session.source?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
            }
            session.messageCount?.let {
                Text(
                    stringResource(R.string.sessions_msg_count, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            }
            session.model?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        session.preview?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(3.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
