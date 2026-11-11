package com.tightxu.hermesremote.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tightxu.hermesremote.R
import com.tightxu.hermesremote.data.ApiException

/**
 * Map a data-layer [ApiException] to a localized, user-safe message. The
 * exceptions' own `message`s are data-layer diagnostics (sometimes Chinese,
 * sometimes raw codes) and must never be rendered directly.
 */
fun localizedApiMessage(context: Context, e: Throwable?): String = when (e) {
    is ApiException.InvalidServer -> context.getString(R.string.api_error_invalid_server)
    is ApiException.Unauthorized -> context.getString(R.string.api_error_unauthorized)
    is ApiException.RateLimited -> context.getString(R.string.api_error_rate_limited)
    is ApiException.Network -> context.getString(R.string.api_error_network)
    is ApiException.Http -> context.getString(R.string.api_error_http, e.code)
    is ApiException.PluginUnavailable -> context.getString(R.string.locks_plugin_unavailable)
    else -> context.getString(R.string.api_error_unknown, e?.message ?: "")
}

// ── localized relative time ───────────────────────────────────────────────────

/** Coarse age bucket; the caller renders it via string resources. */
enum class TimeAgoUnit { NOW, MINUTES, HOURS, DAYS, MONTHS, YEARS }

data class TimeAgo(val unit: TimeAgoUnit, val count: Long)

/** Bucket an epoch-seconds stamp into a (unit, count) pair; null → unknown. */
fun timeAgoOf(epochSeconds: Double?, nowSeconds: Long = System.currentTimeMillis() / 1000): TimeAgo? {
    if (epochSeconds == null || epochSeconds <= 0) return null
    val delta = nowSeconds - epochSeconds.toLong()
    val d = if (delta < 0) 0L else delta
    return when {
        d < 60 -> TimeAgo(TimeAgoUnit.NOW, d)
        d < 3600 -> TimeAgo(TimeAgoUnit.MINUTES, d / 60)
        d < 86400 -> TimeAgo(TimeAgoUnit.HOURS, d / 3600)
        d < 86400 * 30 -> TimeAgo(TimeAgoUnit.DAYS, d / 86400)
        d < 86400 * 365 -> TimeAgo(TimeAgoUnit.MONTHS, d / (86400 * 30))
        else -> TimeAgo(TimeAgoUnit.YEARS, d / (86400 * 365))
    }
}

/** "3 min ago"-style stamp, localized. Blank when the stamp is unknown. */
@Composable
fun timeAgoText(epochSeconds: Double?): String {
    val ago = timeAgoOf(epochSeconds) ?: return ""
    return when (ago.unit) {
        TimeAgoUnit.NOW -> stringResource(R.string.time_just_now)
        TimeAgoUnit.MINUTES -> stringResource(R.string.time_minutes_ago, ago.count)
        TimeAgoUnit.HOURS -> stringResource(R.string.time_hours_ago, ago.count)
        TimeAgoUnit.DAYS -> stringResource(R.string.time_days_ago, ago.count)
        TimeAgoUnit.MONTHS -> stringResource(R.string.time_months_ago, ago.count)
        TimeAgoUnit.YEARS -> stringResource(R.string.time_years_ago, ago.count)
    }
}

/** Lease age as an epoch stamp so it renders through the same localized buckets. */
fun leaseAgeAsEpoch(ageSeconds: Double?): Double? {
    if (ageSeconds == null || ageSeconds < 0) return null
    return System.currentTimeMillis() / 1000.0 - ageSeconds
}
