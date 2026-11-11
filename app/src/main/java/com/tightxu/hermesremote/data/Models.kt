package com.tightxu.hermesremote.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Shared JSON parser: the gateway may add fields at any time, so unknown keys
 * are dropped and mismatched primitives fall back to defaults instead of
 * crashing the list screen.
 */
val hermesJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    // LoginRequest.provider has a default ("basic"); without this the field is
    // omitted from the wire body and the gateway answers "Unknown provider".
    encodeDefaults = true
}

@Serializable
data class SessionInfo(
    val id: String,
    val title: String? = null,
    val source: String? = null,
    val model: String? = null,
    val profile: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("tool_call_count") val toolCallCount: Int? = null,
    val preview: String? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
)

@Serializable
data class SessionsResponse(
    val sessions: List<SessionInfo> = emptyList(),
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

@Serializable
data class SessionMessage(
    val role: String = "user",
    val content: String = "",
    val timestamp: Double? = null,
    val id: Int? = null,
    @SerialName("display_content") val displayContent: String? = null,
    @SerialName("display_kind") val displayKind: String? = null,
)

/** Typed `pagination` block of the messages endpoint. */
@Serializable
data class Pagination(
    val limit: Int = 0,
    val offset: Int = 0,
    val order: String = "latest",
    val returned: Int = 0,
)

@Serializable
data class SessionMessagesResponse(
    @SerialName("session_id") val sessionId: String? = null,
    val messages: List<SessionMessage> = emptyList(),
    val pagination: Pagination? = null,
)

/** A message rendered in the chat list (already filtered + trimmed). */
data class DisplayMessage(
    val id: Int,
    val role: String,
    val text: String,
    val timestamp: Double? = null,
)

/**
 * Human-readable chat text for one server message, or null when it must not
 * be shown: tool/system roles, empty bodies, and assistant messages whose
 * displayKind is "hidden" (compaction wrappers). Assistant text prefers
 * displayContent, falling back to content.
 */
fun SessionMessage.displayText(): String? = when (role) {
    "user" -> content.trim().takeIf { it.isNotEmpty() }
    "assistant" -> {
        if (displayKind == "hidden") null
        else (displayContent?.takeIf { it.isNotBlank() } ?: content)
            .trim().takeIf { it.isNotEmpty() }
    }
    else -> null
}

/** Keep only messages that pass [displayText] (order preserved). */
fun filterDisplayMessages(messages: List<SessionMessage>): List<SessionMessage> =
    messages.filter { it.displayText() != null }

/** Map server messages to renderable [DisplayMessage]s (order preserved). */
fun toDisplayMessages(messages: List<SessionMessage>): List<DisplayMessage> =
    filterDisplayMessages(messages).mapIndexed { index, msg ->
        DisplayMessage(
            // Real ids are positive; the optimistic echo uses -1, -2, ...;
            // messages without an id still get a unique LazyColumn key.
            id = msg.id ?: (Int.MIN_VALUE + index),
            role = msg.role,
            text = msg.displayText()!!,
            timestamp = msg.timestamp,
        )
    }

/** Oldest → newest, timestamp first and id as tiebreaker. */
fun sortMessagesAscending(messages: List<SessionMessage>): List<SessionMessage> =
    messages.sortedWith(compareBy({ it.timestamp ?: 0.0 }, { it.id ?: 0 }))

/**
 * Merge a fetched page into the local set. Real messages (id > 0) replace
 * each other by id; optimistic local echoes (id ≤ 0 / null) are kept unless a
 * real user message with identical content arrived, which supersedes them.
 * Result is sorted ascending.
 */
fun mergeMessages(existing: List<SessionMessage>, incoming: List<SessionMessage>): List<SessionMessage> {
    val real = LinkedHashMap<Int, SessionMessage>()
    val optimistic = mutableListOf<SessionMessage>()
    for (msg in existing) {
        val id = msg.id
        if (id == null || id <= 0) optimistic += msg else real[id] = msg
    }
    val realUserTexts = HashSet<String>()
    for (msg in incoming) {
        val id = msg.id
        if (id != null && id > 0) {
            real[id] = msg
            if (msg.role == "user") realUserTexts += msg.content.trim()
        }
    }
    val keptOptimistic = optimistic.filter { !(it.role == "user" && it.content.trim() in realUserTexts) }
    return sortMessagesAscending(real.values + keptOptimistic)
}

/** True while the server may hold older pages: it filled the whole limit. */
fun hasMorePages(returned: Int, limit: Int): Boolean = returned >= limit && returned > 0

/**
 * /api/status payload. Only a few fields are typed; gateway_platforms is kept
 * as a raw element map because its shape varies between server versions —
 * the settings screen renders whatever is there.
 */
@Serializable
data class StatusResponse(
    val version: String? = null,
    @SerialName("gateway_running") val gatewayRunning: Boolean? = null,
    @SerialName("gateway_state") val gatewayState: String? = null,
    @SerialName("gateway_platforms") val gatewayPlatforms: Map<String, JsonElement>? = null,
)

@Serializable
data class LoginRequest(
    val provider: String = "basic",
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val ok: Boolean = false,
    val next: String? = null,
)

@Serializable
data class WsTicketResponse(
    val ticket: String = "",
    @SerialName("ttl_seconds") val ttlSeconds: Int? = null,
)

// ── session-lock plugin (/api/plugins/session-lock/) ─────────────────────────
// Shapes mirror the plugin's _describe() output; unknown fields are ignored and
// a null `alive` (liveness probe failed server-side) coerces to false.

@Serializable
data class LeaseInfo(
    @SerialName("lease_id") val leaseId: String? = null,
    @SerialName("session_id") val sessionId: String = "",
    /** Human session title resolved by the plugin from the state DB; null for untitled. */
    val title: String? = null,
    val surface: String? = null,
    val pid: Int? = null,
    val alive: Boolean = false,
    @SerialName("age_seconds") val ageSeconds: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("live_session_id") val liveSessionId: String? = null,
)

@Serializable
data class LeaseResponse(
    val path: String? = null,
    val count: Int = 0,
    val entries: List<LeaseInfo> = emptyList(),
)

@Serializable
data class ReleaseResponse(
    val ok: Boolean = false,
    val removed: List<LeaseInfo> = emptyList(),
    val remaining: List<LeaseInfo> = emptyList(),
    val backup: String? = null,
    val path: String? = null,
)

/** Body for POST /release. `confirm` must be true for scope session/all. */
@Serializable
data class LeaseReleaseRequest(
    val scope: String,
    @SerialName("session_id") val sessionId: String? = null,
    val confirm: Boolean = false,
)
