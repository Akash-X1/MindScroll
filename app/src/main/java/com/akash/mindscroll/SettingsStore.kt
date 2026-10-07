package com.akash.mindscroll

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mindscroll_settings", Context.MODE_PRIVATE)

    companion object {
        const val REMINDER_PHASE_FIRST = "first"
        const val REMINDER_PHASE_REPEAT = "repeat"
    }

    data class SessionState(
        val packageName: String,
        val videoNumber: Int,
        val startedAt: Long,
        val nextInterventionAt: Long,
        val lastSeenAt: Long,
        val watchedMs: Long,
        val reminderPhase: String
    )

    data class DailyStat(
        val date: LocalDate,
        val reels: Int,
        val watchMs: Long
    )

    data class AppStat(
        val key: String,
        val label: String,
        val reels: Int,
        val watchMs: Long
    )

    var protectionEnabled: Boolean
        get() = prefs.getBoolean("protection_enabled", false)
        set(value) = prefs.edit().putBoolean("protection_enabled", value).apply()

    var firstPauseMinutes: Int
        get() = prefs.getInt("first_pause_minutes", 10)
        set(value) = prefs.edit().putInt("first_pause_minutes", value.coerceIn(1, 120)).apply()

    var repeatPauseMinutes: Int
        get() = prefs.getInt("repeat_pause_minutes", 10)
        set(value) = prefs.edit().putInt("repeat_pause_minutes", value.coerceIn(1, 60)).apply()

    var reflectionSeconds: Int
        get() = prefs.getInt("reflection_seconds", 15)
        set(value) = prefs.edit().putInt("reflection_seconds", value.coerceIn(5, 30)).apply()

    var instagramEnabled: Boolean
        get() = prefs.getBoolean("instagram_enabled", true)
        set(value) = prefs.edit().putBoolean("instagram_enabled", value).apply()

    var youtubeEnabled: Boolean
        get() = prefs.getBoolean("youtube_enabled", true)
        set(value) = prefs.edit().putBoolean("youtube_enabled", value).apply()

    var tiktokEnabled: Boolean
        get() = prefs.getBoolean("tiktok_enabled", true)
        set(value) = prefs.edit().putBoolean("tiktok_enabled", value).apply()

    var facebookEnabled: Boolean
        get() = prefs.getBoolean("facebook_enabled", true)
        set(value) = prefs.edit().putBoolean("facebook_enabled", value).apply()

    /**
     * Separate from the master protection switch: when false, counting and watch-time
     * tracking continue but intervention reminders are suppressed. This is what the
     * bubble's long-press quick control toggles.
     */
    var remindersEnabled: Boolean
        get() = prefs.getBoolean("reminders_enabled", true)
        set(value) = prefs.edit().putBoolean("reminders_enabled", value).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("scroll_haptics_enabled_v10", false)
        set(value) = prefs.edit().putBoolean("scroll_haptics_enabled_v10", value).apply()

    var muteDuringIntervention: Boolean
        get() = prefs.getBoolean("mute_during_intervention", true)
        set(value) = prefs.edit().putBoolean("mute_during_intervention", value).apply()

    /**
     * BrainPal-style ad handling: sponsored/promoted short videos stay visible and
     * watch time keeps running, but they are excluded from the watched-video count.
     * This is intentionally not an auto-swipe feature, which keeps the behavior
     * user-controlled and friendlier to Play policy.
     */
    var skipSponsoredVideos: Boolean
        get() = prefs.getBoolean("skip_sponsored_v17", true)
        set(value) = prefs.edit().putBoolean("skip_sponsored_v17", value).apply()

    var showCounter: Boolean
        get() = prefs.getBoolean("show_counter", true)
        set(value) = prefs.edit().putBoolean("show_counter", value).apply()

    var showTimer: Boolean
        get() = prefs.getBoolean("show_timer", true)
        set(value) = prefs.edit().putBoolean("show_timer", value).apply()

    var videoEnabled: Boolean
        get() = prefs.getBoolean("video_enabled", true)
        set(value) = prefs.edit().putBoolean("video_enabled", value).apply()

    var diagnosticsEnabled: Boolean
        get() = prefs.getBoolean("diagnostics_enabled", false)
        set(value) = prefs.edit().putBoolean("diagnostics_enabled", value).apply()

    var hasSeenWelcome: Boolean
        get() = prefs.getBoolean("has_seen_welcome", false)
        set(value) = prefs.edit().putBoolean("has_seen_welcome", value).apply()

    var accessibilityDisclosureAccepted: Boolean
        get() = prefs.getBoolean("accessibility_disclosure_accepted", false)
        set(value) = prefs.edit().putBoolean("accessibility_disclosure_accepted", value).apply()

    private fun countKey(date: LocalDate) = "history_${date}_count"
    private fun watchKey(date: LocalDate) = "history_${date}_watch_ms"
    private fun appCountKey(date: LocalDate, app: String) = "history_${date}_${app}_count"
    private fun appWatchKey(date: LocalDate, app: String) = "history_${date}_${app}_watch_ms"
    private fun sequenceMarkerKey(date: LocalDate) = "v7_sequence_started_$date"

    private fun appKeyForPackage(packageName: String?): String? = when {
        packageName == "com.instagram.android" -> "instagram"
        packageName == "com.google.android.youtube" -> "youtube"
        packageName == "com.facebook.katana" -> "facebook"
        packageName?.contains("musically", ignoreCase = true) == true ||
            packageName?.contains("ugc.trill", ignoreCase = true) == true ||
            packageName?.contains("tiktok", ignoreCase = true) == true -> "tiktok"
        else -> null
    }

    private fun migrateLegacyTodayIfNeeded() {
        val today = LocalDate.now()
        val legacyDate = prefs.getString("count_date", null)
        if (legacyDate == today.toString() && !prefs.contains(countKey(today))) {
            prefs.edit()
                .putInt(countKey(today), prefs.getInt("today_count", 0))
                .apply()
        }
    }

    fun getTodayCount(): Int {
        migrateLegacyTodayIfNeeded()
        return getDayCount(LocalDate.now())
    }

    /**
     * V8 shows one continuous number across Instagram, YouTube, TikTok and Facebook Reels.
     * The first detected short-video surface of the day starts the sequence at 1.
     * Existing V6 data is migrated once by adding the missing initial video.
     */
    fun ensureTodaySequenceStarted(): Int {
        val today = LocalDate.now()
        val marker = sequenceMarkerKey(today)
        if (prefs.getBoolean(marker, false)) return getDayCount(today)

        val current = getDayCount(today)
        val next = current + 1
        prefs.edit()
            .putString("count_date", today.toString())
            .putInt("today_count", next)
            .putInt(countKey(today), next)
            .putBoolean(marker, true)
            .apply()
        return next
    }

    fun incrementTodayCount(packageName: String? = null): Int {
        val today = LocalDate.now()
        val next = getDayCount(today) + 1
        val edit = prefs.edit()
            .putString("count_date", today.toString())
            .putInt("today_count", next)
            .putInt(countKey(today), next)
            .putBoolean(sequenceMarkerKey(today), true)
        appKeyForPackage(packageName)?.let { app ->
            edit.putInt(appCountKey(today, app), prefs.getInt(appCountKey(today, app), 0) + 1)
        }
        edit.apply()
        return next
    }

    fun getDayCount(date: LocalDate): Int = prefs.getInt(countKey(date), 0)

    fun getTodayWatchMs(): Long = getDayWatchMs(LocalDate.now())

    fun getDayWatchMs(date: LocalDate): Long = prefs.getLong(watchKey(date), 0L)

    fun addTodayWatchTime(deltaMs: Long, packageName: String? = null): Long {
        if (deltaMs <= 0L) return getTodayWatchMs()
        val today = LocalDate.now()
        val next = getDayWatchMs(today) + deltaMs
        val edit = prefs.edit().putLong(watchKey(today), next)
        appKeyForPackage(packageName)?.let { app ->
            edit.putLong(appWatchKey(today, app), prefs.getLong(appWatchKey(today, app), 0L) + deltaMs)
        }
        edit.apply()
        return next
    }

    fun getDayAppStats(date: LocalDate): List<AppStat> {
        return listOf(
            AppStat("instagram", "Instagram", prefs.getInt(appCountKey(date, "instagram"), 0), prefs.getLong(appWatchKey(date, "instagram"), 0L)),
            AppStat("youtube", "YouTube", prefs.getInt(appCountKey(date, "youtube"), 0), prefs.getLong(appWatchKey(date, "youtube"), 0L)),
            AppStat("tiktok", "TikTok", prefs.getInt(appCountKey(date, "tiktok"), 0), prefs.getLong(appWatchKey(date, "tiktok"), 0L)),
            AppStat("facebook", "Facebook", prefs.getInt(appCountKey(date, "facebook"), 0), prefs.getLong(appWatchKey(date, "facebook"), 0L))
        )
    }

    fun getMonthStats(month: YearMonth): List<DailyStat> {
        return (1..month.lengthOfMonth()).map { day ->
            val date = month.atDay(day)
            DailyStat(
                date = date,
                reels = getDayCount(date),
                watchMs = getDayWatchMs(date)
            )
        }
    }

    fun getYearStats(year: Int): List<DailyStat> {
        val start = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)
        val days = end.toEpochDay() - start.toEpochDay() + 1
        return (0 until days.toInt()).map { offset ->
            val date = start.plusDays(offset.toLong())
            DailyStat(date, getDayCount(date), getDayWatchMs(date))
        }
    }

    fun resetTodayCount() {
        val today = LocalDate.now()
        val edit = prefs.edit()
            .putString("count_date", today.toString())
            .putInt("today_count", 0)
            .putInt(countKey(today), 0)
            .putLong(watchKey(today), 0L)
            .remove(sequenceMarkerKey(today))
        listOf("instagram", "youtube", "tiktok", "facebook").forEach { app ->
            edit.remove(appCountKey(today, app)).remove(appWatchKey(today, app))
        }
        edit.apply()
    }

    fun saveSession(
        packageName: String,
        videoNumber: Int,
        startedAt: Long,
        nextInterventionAt: Long,
        lastSeenAt: Long = System.currentTimeMillis(),
        watchedMs: Long = 0L,
        reminderPhase: String = REMINDER_PHASE_FIRST
    ) {
        prefs.edit()
            .putString("session_package", packageName)
            .putInt("session_video_number", videoNumber.coerceAtLeast(0))
            .putLong("session_started_at", startedAt)
            .putLong("session_next_intervention_at", nextInterventionAt)
            .putLong("session_last_seen_at", lastSeenAt)
            .putLong("session_watched_ms", watchedMs.coerceAtLeast(0L))
            .putString("session_reminder_phase", reminderPhase)
            .apply()
    }

    fun touchSession(lastSeenAt: Long = System.currentTimeMillis()) {
        if (prefs.contains("session_package")) {
            prefs.edit().putLong("session_last_seen_at", lastSeenAt).apply()
        }
    }

    fun loadSession(): SessionState? {
        val packageName = prefs.getString("session_package", null) ?: return null
        return SessionState(
            packageName = packageName,
            videoNumber = prefs.getInt("session_video_number", 0),
            startedAt = prefs.getLong("session_started_at", 0L),
            nextInterventionAt = prefs.getLong("session_next_intervention_at", 0L),
            lastSeenAt = prefs.getLong("session_last_seen_at", 0L),
            watchedMs = prefs.getLong("session_watched_ms", 0L),
            reminderPhase = prefs.getString("session_reminder_phase", REMINDER_PHASE_FIRST) ?: REMINDER_PHASE_FIRST
        )
    }

    /**
     * Rewrites only the active reminder window while preserving the current
     * video count/watch-time session. `resetWindow=true` starts a fresh window
     * from the moment tracking was last active; otherwise already-used watch
     * time is preserved.
     */
    fun updateActiveReminderWindow(
        phase: String,
        minutes: Int,
        resetWindow: Boolean,
        elapsedUsedMs: Long = 0L
    ): SessionState? {
        val session = loadSession() ?: return null
        if (session.reminderPhase != phase || session.startedAt <= 0L) return session
        val durationMs = minutes.coerceAtLeast(1) * 60_000L
        val anchor = session.lastSeenAt.coerceAtLeast(session.startedAt)
        val usedMs = if (resetWindow) 0L else elapsedUsedMs.coerceAtLeast(0L)
        // Re-anchor the window around active watched time instead of raw wall
        // time. This keeps comments/profile/app-switch pauses from being counted
        // as reminder usage. If usedMs exceeds the new limit, newNextAt ends up
        // at/before the anchor, intentionally making the reminder immediately due.
        val newStartedAt = anchor - usedMs
        val newNextAt = anchor + durationMs - usedMs
        saveSession(
            packageName = session.packageName,
            videoNumber = session.videoNumber,
            startedAt = newStartedAt,
            nextInterventionAt = newNextAt,
            lastSeenAt = session.lastSeenAt,
            watchedMs = session.watchedMs,
            reminderPhase = phase
        )
        return loadSession()
    }

    fun clearSession() {
        prefs.edit()
            .remove("session_package")
            .remove("session_video_number")
            .remove("session_started_at")
            .remove("session_next_intervention_at")
            .remove("session_last_seen_at")
            .remove("session_watched_ms")
            .remove("session_reminder_phase")
            .apply()
    }
    fun clearAllHistory() {
        val edit = prefs.edit()
        prefs.all.keys.filter { key ->
            key.startsWith("history_") ||
                key.startsWith("v7_sequence_started_") ||
                key in setOf("count_date", "today_count")
        }.forEach { edit.remove(it) }
        clearSessionKeys(edit)
        edit.apply()
    }

    fun clearAllData() {
        prefs.edit().clear().putBoolean("protection_enabled", false).apply()
    }

    private fun clearSessionKeys(edit: android.content.SharedPreferences.Editor) {
        edit.remove("session_package")
            .remove("session_video_number")
            .remove("session_started_at")
            .remove("session_next_intervention_at")
            .remove("session_last_seen_at")
            .remove("session_watched_ms")
            .remove("session_reminder_phase")
    }

}
