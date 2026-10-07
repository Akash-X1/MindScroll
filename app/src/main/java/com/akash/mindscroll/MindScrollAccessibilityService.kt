package com.akash.mindscroll

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MindScroll V27 — Play-ready MVP.
 *
 * The user-supplied BrainPal APK was statically inspected as a behavioral
 * reference. Its detector produces DetectionData(isDetected, videoIdentifier,
 * isPanelOpen, isAd), and its ReelsScrollManager logs a 750 ms dwell before a
 * reel is counted. MindScroll independently implements the same high-level
 * strategy without copying BrainPal source code.
 *
 * Core rules:
 * 1. Accessibility scroll gestures never count by themselves.
 * 2. Each supported app gets a dedicated short-video surface detector.
 * 3. The currently visible video is represented by a stable local identifier.
 * 4. A new identifier must remain current for 750 ms before it counts.
 * 5. Half-swipes/snap-backs, comments, Profile/Home/Search grids and recently
 *    seen videos do not increment the counter.
 * 6. Sponsored/promoted videos can be ignored from the count while watch time
 *    continues.
 */

class MindScrollAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_TEST_INTERVENTION = "com.akash.mindscroll.TEST_INTERVENTION"
        const val ACTION_RESET_COUNTER = "com.akash.mindscroll.RESET_COUNTER"
        const val ACTION_PROTECTION_CHANGED = "com.akash.mindscroll.PROTECTION_CHANGED"
        const val ACTION_REMINDER_TIMING_CHANGED = "com.akash.mindscroll.REMINDER_TIMING_CHANGED"
        const val ACTION_DELETE_ALL_DATA = "com.akash.mindscroll.DELETE_ALL_DATA"

        private const val INSTAGRAM = "com.instagram.android"
        private const val YOUTUBE = "com.google.android.youtube"
        private const val TIKTOK = "com.zhiliaoapp.musically"
        private const val TIKTOK_ALT = "com.ss.android.ugc.trill"
        private const val TIKTOK_LITE = "com.zhiliaoapp.musically.go"
        private const val TIKTOK_LITE_ALT = "com.ss.android.ugc.tiktok.lite"
        private const val FACEBOOK = "com.facebook.katana"

        private const val BUBBLE_SIZE_DP = 44
        private const val BUBBLE_MENU_HIDE_MS = 3_500L
        private const val BUBBLE_LONG_PRESS_MS = 520L

        // Session/foreground stability.
        private const val FOREIGN_APP_EXIT_CONFIRM_MS = 650L
        private const val FOREIGN_APP_SECOND_CHECK_MS = 450L
        private const val SESSION_RESUME_GRACE_MS = 10 * 60_000L
        private const val SESSION_PERSIST_INTERVAL_MS = 5_000L
        private const val WATCH_FLUSH_INTERVAL_MS = 5_000L

        // Pager state stability. Instagram may temporarily expose an incomplete
        // accessibility tree while a Reel snaps into place.
        private const val PAGER_TREE_GRACE_MS = 850L
        private const val OPEN_SETTLE_MS = 400L
        private const val OPEN_REPOSITION_MS = 120L
        private const val FALLBACK_SCROLL_SETTLE_MS = 220L
        private const val FINGERPRINT_SETTLE_MS = 340L
        private const val MIN_COUNT_INTERVAL_MS = 260L
        private const val GENERIC_GESTURE_COOLDOWN_MS = 430L
        private const val EXPLORE_CONTEXT_MEMORY_MS = 60_000L
        private const val SURFACE_DISPLAY_GRACE_MS = 0L
        private const val WATCHDOG_AWAY_CONFIRM_MS = 650L
        private const val SURFACE_WATCHDOG_INTERVAL_MS = 650L
        private const val SURFACE_REFRESH_MIN_GAP_MS = 280L
        private const val YOUTUBE_COMMENT_GUARD_MS = 2_000L
        private const val YOUTUBE_COUNT_CONFIRM_MS = 520L
        private const val TIKTOK_IDENTITY_POLL_MS = 450L
        private const val TIKTOK_IDENTITY_STABLE_MS = 320L
        private const val TIKTOK_CONTENT_BURST_GAP_MS = 520L
        private const val TIKTOK_CONTENT_SETTLE_MS = 230L
        private const val TIKTOK_CONTENT_MIN_EVENTS = 2
        private const val NON_VIDEO_NAV_SUPPRESS_MS = 1_100L
        private const val TIKTOK_GESTURE_CONFIRM_WINDOW_MS = 1_600L

        private const val DIAG_FILE = "mindscroll_diagnostics.txt"
        private const val MAX_DIAG_BYTES = 220_000L

        // V18: BrainPal-inspired stable-video pipeline. The uploaded BrainPal APK
        // does not count gestures directly: it identifies the currently visible
        // short video, waits ~750 ms, then counts only if that identifier remains
        // changed. We independently reimplement that behavior here.
        private const val BP_EVENT_COALESCE_MS = 85L
        private const val BP_VIDEO_CONFIRM_MS = 750L
        private const val BP_SURFACE_WATCHDOG_MS = 360L
        private const val BP_RECENT_ID_LIMIT = 24
        private const val BP_ROOT_LOSS_GRACE_MS = 600L
    }

    private enum class ScreenMode {
        OTHER,
        INSTAGRAM_REELS,
        INSTAGRAM_COMMENTS,
        YOUTUBE_SHORTS,
        YOUTUBE_COMMENTS,
        TIKTOK_FEED,
        TIKTOK_COMMENTS,
        FACEBOOK_REELS,
        FACEBOOK_COMMENTS
    }

    private data class SurfaceState(
        val viewerOpen: Boolean,
        val commentsOpen: Boolean,
        val selectedTab: Boolean = false
    )

    private data class PagerState(
        var active: Boolean = false,
        var page: Int = -1,
        var armedAt: Long = 0L,
        var lastSeenAt: Long = 0L,
        var lastIndexEventAt: Long = 0L,
        var fallbackNetDy: Int = 0,
        var fallbackPositiveDy: Int = 0,
        var fallbackNegativeDy: Int = 0,
        var fallbackToken: Int = 0,
        var fallbackLastAcceptedAt: Long = 0L,
        var fallbackUnknownEvents: Int = 0,
        var settledIndexCandidate: Int = -1,
        var settledIndexDirectionHint: Int = 0,
        var settledIndexToken: Int = 0
    )

    /**
     * Secondary video-change detector used when an app hides pager indexes from
     * Accessibility.  BrainPal's public privacy documentation describes the same
     * general strategy: supported-package filtering plus view IDs/content
     * descriptions/basic screen metadata.  We never persist the metadata itself;
     * only this in-memory hash is kept long enough to recognize a video change.
     */
    private data class FingerprintState(
        var current: Int = 0,
        val recent: ArrayDeque<Int> = ArrayDeque(),
        var settleToken: Int = 0,
        var settlePending: Boolean = false,
        var pendingDirectionHint: Int = 0
    )


    private data class BPDetection(
        val detected: Boolean,
        val videoId: String = "",
        val panelOpen: Boolean = false,
        val isAd: Boolean = false,
        val debug: String = ""
    )

    private data class BPTrackState(
        /** The last video that survived the 750 ms dwell confirmation. */
        var stableId: String? = null,
        /** A newly observed video that has not yet survived the dwell window. */
        var pendingId: String? = null,
        var pendingIsAd: Boolean = false,
        var pendingToken: Int = 0,
        var lastDetectedAt: Long = 0L,
        /** Recently confirmed videos are not counted again when swiping backwards. */
        val recentIds: ArrayDeque<String> = ArrayDeque()
    )

    private lateinit var store: SettingsStore
    private lateinit var motivationLibrary: MotivationLibrary
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var bubbleCountText: TextView? = null
    private var bubbleTimerText: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleMenuView: View? = null
    private var interventionView: View? = null

    private var hasTransientAudioFocus = false
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { /* transient reminder focus only */ }

    private var currentTargetPackage: String? = null
    private var currentMode = ScreenMode.OTHER
    private var sessionStartedAt = 0L
    private var nextInterventionAt = 0L
    private var currentReminderPhase = SettingsStore.REMINDER_PHASE_FIRST
    private var sessionVideoNumber = 0
    private var interventionShowing = false
    private var lastCountedAt = 0L
    private var lastTargetEventAt = 0L
    private var lastSessionPersistAt = 0L
    private var exitCheckToken = 0
    private var lastShortSurfaceSeenAt = 0L
    private var watchdogAwayPackage: String? = null
    private var watchdogAwaySince = 0L
    private val surfaceRefreshTokens = HashMap<String, Int>()
    private val surfaceRefreshPending = HashSet<String>()
    private val lastSurfaceRefreshAt = HashMap<String, Long>()
    private var lastSurfaceWatchdogAt = 0L
    private var instagramExploreContextSeenAt = 0L
    private var instagramStrictPagerVisible = false
    private var lastInstagramNavigationSignalAt = 0L
    private var lastYouTubeNavigationSignalAt = 0L
    private var lastTikTokNavigationSignalAt = 0L
    private var youtubeCommentGuardUntil = 0L
    private var youtubePendingCount = false
    private var youtubePendingCountToken = 0
    private var youtubePendingReason = ""
    private var tiktokPollLastAt = 0L
    private var tiktokPollCandidate = 0
    private var tiktokPollCandidateSince = 0L
    private var tiktokContentBurstStartedAt = 0L
    private var tiktokContentBurstLastAt = 0L
    private var tiktokContentBurstEvents = 0
    private var tiktokContentBurstToken = 0
    private var instagramNonVideoNavSuppressUntil = 0L
    private var youtubeNonVideoNavSuppressUntil = 0L
    private var tiktokNonVideoNavSuppressUntil = 0L
    private var facebookNonVideoNavSuppressUntil = 0L
    private var tiktokGestureCandidateUntil = 0L

    // Reel/Short watch time is measured only while the actual short-video feed is visible.
    // Comments, home screen, profiles and other apps pause this clock.
    private var sessionWatchedMs = 0L
    private var watchSegmentStartedAt = 0L
    private var interventionPausedAt = 0L

    private val instagramPager = PagerState()
    private val youtubePager = PagerState()
    private val tiktokPager = PagerState()
    private val facebookPager = PagerState()

    private val instagramFingerprint = FingerprintState()
    private val youtubeFingerprint = FingerprintState()
    private val tiktokFingerprint = FingerprintState()
    private val facebookFingerprint = FingerprintState()


    // V18 detector state. One stable identifier history per app prevents a
    // half-swipe / snap-back from becoming a count, and avoids recounting a
    // recently visited video when the user swipes backwards.
    private val bpTrackStates = HashMap<String, BPTrackState>()
    private var bpScanScheduled = false
    private var bpScanToken = 0
    private var bpLastSurfaceWatchdogAt = 0L
    private var bpLastGoodRootAt = 0L

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!store.protectionEnabled) {
                pauseWatchClock("protection disabled")
                hideBubble()
                if (currentTargetPackage != null) suspendTargetSession("protection disabled")
                handler.postDelayed(this, 300L)
                return
            }

            val target = currentTargetPackage
            if (target != null) {
                val now = System.currentTimeMillis()
                val foreground = resolveForegroundPackage()

                if (!foreground.isNullOrBlank() && foreground != target && !isTransientSystemPackage(foreground)) {
                    if (interventionShowing) dismissIntervention()
                    pauseWatchClock("foreground=$foreground")
                    suspendTargetSession("foreground=$foreground")
                } else {
                    // Same-app navigation (Reels -> Profile/Search/Home) does not
                    // change package name. Re-scan only the active root at a modest
                    // cadence so the bubble disappears quickly without expensive
                    // full-window scans.
                    if (now - bpLastSurfaceWatchdogAt >= BP_SURFACE_WATCHDOG_MS) {
                        bpLastSurfaceWatchdogAt = now
                        bpScheduleScan(target, 0L, "watchdog")
                    }

                    flushActiveWatchTime()
                    updateBubble()
                    persistSessionIfNeeded()
                    if (store.remindersEnabled && nextInterventionAt > 0L &&
                        now >= nextInterventionAt && isShortVideoMode(currentMode)) {
                        showIntervention(testMode = false)
                    }
                }
            }
            handler.postDelayed(this, 300L)
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TEST_INTERVENTION -> showIntervention(testMode = true)
                ACTION_RESET_COUNTER -> {
                    pauseWatchClock("manual reset")
                    store.resetTodayCount()
                    sessionWatchedMs = 0L
                    sessionVideoNumber = 0
                    if (isShortVideoMode(currentMode)) resumeWatchClock("manual reset resume")
                    persistSession(force = true)
                    updateBubble()
                    diag("COUNTER manual reset session=$sessionVideoNumber")
                }
                ACTION_DELETE_ALL_DATA -> {
                    pauseWatchClock("delete all data")
                    dismissIntervention()
                    hideBubble()
                    currentTargetPackage = null
                    currentMode = ScreenMode.OTHER
                    sessionStartedAt = 0L
                    nextInterventionAt = 0L
                    currentReminderPhase = SettingsStore.REMINDER_PHASE_FIRST
                    sessionVideoNumber = 0
                    sessionWatchedMs = 0L
                    watchSegmentStartedAt = 0L
                    bpTrackStates.clear()
                    store.clearSession()
                    disableSelf()
                }
                ACTION_PROTECTION_CHANGED -> {
                    if (!store.protectionEnabled) {
                        pauseWatchClock("protection toggled off")
                        hideBubble()
                        suspendTargetSession("protection toggled off")
                    } else {
                        if (!store.remindersEnabled) {
                            nextInterventionAt = 0L
                        } else if (currentTargetPackage != null && isShortVideoMode(currentMode) && nextInterventionAt <= 0L) {
                            val minutes = if (currentReminderPhase == SettingsStore.REMINDER_PHASE_REPEAT) {
                                store.repeatPauseMinutes
                            } else {
                                store.firstPauseMinutes
                            }
                            val now = System.currentTimeMillis()
                            sessionStartedAt = now
                            nextInterventionAt = now + minutes * 60_000L
                        }
                        updateBubble()
                        persistSession(force = true)
                        diag("PROTECTION/settings changed reminders=${store.remindersEnabled}")
                    }
                }
                ACTION_REMINDER_TIMING_CHANGED -> {
                    // The app may edit the currently-paused reminder window while
                    // MindScroll itself is foreground. Reload the persisted schedule
                    // so split-screen / active-service cases stay perfectly synced.
                    val saved = store.loadSession()
                    val target = currentTargetPackage
                    if (saved != null && target != null && saved.packageName == target) {
                        sessionStartedAt = saved.startedAt
                        nextInterventionAt = saved.nextInterventionAt
                        currentReminderPhase = saved.reminderPhase
                        if (store.remindersEnabled && nextInterventionAt > 0L &&
                            System.currentTimeMillis() >= nextInterventionAt && isShortVideoMode(currentMode)) {
                            showIntervention(testMode = false)
                        } else {
                            updateBubble()
                            persistSession(force = true)
                        }
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = SettingsStore(this)
        motivationLibrary = MotivationLibrary(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ACTION_TEST_INTERVENTION)
            addAction(ACTION_RESET_COUNTER)
            addAction(ACTION_PROTECTION_CHANGED)
            addAction(ACTION_REMINDER_TIMING_CHANGED)
            addAction(ACTION_DELETE_ALL_DATA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(controlReceiver, filter)
        }

        diag("SERVICE connected V27")
        handler.post(timerRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!store.protectionEnabled) {
            pauseWatchClock("protection disabled")
            hideBubble()
            return
        }

        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank()) return

        if (isEnabledTarget(eventPackage)) {
            onTargetEventSeen(eventPackage)
            // Never count TYPE_VIEW_SCROLLED itself. It only tells us that the
            // accessibility snapshot may have changed. A coalesced fresh-root scan
            // decides whether a real Reel/Short is visible and which video it is.
            val delay = when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> 35L
                else -> BP_EVENT_COALESCE_MS
            }
            bpScheduleScan(eventPackage, delay, "event=${event.eventType}")
            return
        }

        if (currentTargetPackage != null && !interventionShowing) {
            schedulePossibleTargetExit(eventPackage)
        }
    }

    private fun onTargetEventSeen(packageName: String) {
        val now = System.currentTimeMillis()
        lastTargetEventAt = now
        exitCheckToken++ // Cancels every pending exit check.
        watchdogAwayPackage = null
        watchdogAwaySince = 0L

        if (currentTargetPackage != packageName) {
            if (currentTargetPackage != null) {
                suspendTargetSession("switch target -> $packageName")
            }
            beginOrResumeTargetSession(packageName)
        }

        if (now - lastSessionPersistAt >= SESSION_PERSIST_INTERVAL_MS) {
            persistSession(force = true)
        }
    }

    private fun beginOrResumeTargetSession(packageName: String) {
        val now = System.currentTimeMillis()
        resetPagerState(instagramPager)
        resetPagerState(youtubePager)
        resetPagerState(tiktokPager)
        resetPagerState(facebookPager)
        resetFingerprintState(instagramFingerprint)
        resetFingerprintState(youtubeFingerprint)
        resetFingerprintState(tiktokFingerprint)
        resetFingerprintState(facebookFingerprint)
        lastInstagramNavigationSignalAt = 0L
        lastYouTubeNavigationSignalAt = 0L
        lastTikTokNavigationSignalAt = 0L
        youtubeCommentGuardUntil = 0L
        cancelYouTubePendingCount("session begin")
        tiktokPollLastAt = 0L
        tiktokPollCandidate = 0
        tiktokPollCandidateSince = 0L
        tiktokContentBurstStartedAt = 0L
        tiktokContentBurstLastAt = 0L
        tiktokContentBurstEvents = 0
        tiktokContentBurstToken++
        instagramNonVideoNavSuppressUntil = 0L
        youtubeNonVideoNavSuppressUntil = 0L
        tiktokNonVideoNavSuppressUntil = 0L
        facebookNonVideoNavSuppressUntil = 0L
        tiktokGestureCandidateUntil = 0L
        pauseWatchClock("begin/resume target")
        currentTargetPackage = packageName
        currentMode = ScreenMode.OTHER
        lastShortSurfaceSeenAt = 0L
        lastTargetEventAt = now

        val saved = store.loadSession()
        val canResume = saved != null &&
            saved.packageName == packageName &&
            saved.lastSeenAt > 0L &&
            now - saved.lastSeenAt in 0..SESSION_RESUME_GRACE_MS

        if (canResume && saved != null) {
            val awayMs = (now - saved.lastSeenAt).coerceAtLeast(0L)
            sessionVideoNumber = saved.videoNumber.coerceAtLeast(0)
            sessionWatchedMs = saved.watchedMs.coerceAtLeast(0L)
            watchSegmentStartedAt = 0L
            interventionPausedAt = 0L
            currentReminderPhase = saved.reminderPhase
            sessionStartedAt = if (saved.startedAt > 0L) saved.startedAt + awayMs else now
            nextInterventionAt = if (saved.nextInterventionAt > 0L) {
                saved.nextInterventionAt + awayMs
            } else {
                if (store.remindersEnabled) now + store.firstPauseMinutes * 60_000L else 0L
            }
            diag("SESSION resume pkg=$packageName number=$sessionVideoNumber away=${awayMs}ms")
        } else {
            sessionVideoNumber = 0
            sessionWatchedMs = 0L
            watchSegmentStartedAt = 0L
            interventionPausedAt = 0L
            currentReminderPhase = SettingsStore.REMINDER_PHASE_FIRST
            sessionStartedAt = now
            nextInterventionAt = if (store.remindersEnabled) now + store.firstPauseMinutes * 60_000L else 0L
            diag("SESSION fresh pkg=$packageName")
        }

        persistSession(force = true)
        hideBubble()
        bpResetPendingFor(packageName, keepRecent = true)
        bpScheduleScan(packageName, 0L, "session begin")
    }

    private fun suspendTargetSession(reason: String) {
        val target = currentTargetPackage ?: return
        diag("SESSION suspend pkg=$target number=$sessionVideoNumber reason=$reason")
        pauseWatchClock("suspend: $reason")
        persistSession(force = true)
        currentTargetPackage = null
        currentMode = ScreenMode.OTHER
        lastShortSurfaceSeenAt = 0L
        resetPagerState(instagramPager)
        resetPagerState(youtubePager)
        resetPagerState(tiktokPager)
        resetPagerState(facebookPager)
        resetFingerprintState(instagramFingerprint)
        resetFingerprintState(youtubeFingerprint)
        resetFingerprintState(tiktokFingerprint)
        resetFingerprintState(facebookFingerprint)
        instagramExploreContextSeenAt = 0L
        instagramStrictPagerVisible = false
        youtubeCommentGuardUntil = 0L
        cancelYouTubePendingCount("session suspend")
        tiktokPollCandidate = 0
        tiktokPollCandidateSince = 0L
        tiktokContentBurstEvents = 0
        tiktokContentBurstToken++
        instagramNonVideoNavSuppressUntil = 0L
        youtubeNonVideoNavSuppressUntil = 0L
        tiktokNonVideoNavSuppressUntil = 0L
        facebookNonVideoNavSuppressUntil = 0L
        tiktokGestureCandidateUntil = 0L
        bpResetPendingFor(target, keepRecent = true)
        hideBubble()
    }

    private fun hardEndSession(reason: String) {
        diag("SESSION hard end reason=$reason")
        pauseWatchClock("hard end: $reason")
        currentTargetPackage = null
        currentMode = ScreenMode.OTHER
        lastShortSurfaceSeenAt = 0L
        sessionVideoNumber = 0
        sessionWatchedMs = 0L
        watchSegmentStartedAt = 0L
        interventionPausedAt = 0L
        sessionStartedAt = 0L
        nextInterventionAt = 0L
        resetPagerState(instagramPager)
        resetPagerState(youtubePager)
        resetPagerState(tiktokPager)
        resetPagerState(facebookPager)
        resetFingerprintState(instagramFingerprint)
        resetFingerprintState(youtubeFingerprint)
        resetFingerprintState(tiktokFingerprint)
        resetFingerprintState(facebookFingerprint)
        instagramExploreContextSeenAt = 0L
        instagramStrictPagerVisible = false
        store.clearSession()
        bpCancelAllPending("hard end")
        hideBubble()
    }

    private fun schedulePossibleTargetExit(triggerPackage: String) {
        val target = currentTargetPackage ?: return
        val token = ++exitCheckToken
        diag("EXIT candidate target=$target eventPkg=$triggerPackage token=$token")

        handler.postDelayed({
            verifyTargetExit(target, token, triggerPackage, secondCheck = false)
        }, FOREIGN_APP_EXIT_CONFIRM_MS)
    }

    private fun verifyTargetExit(
        target: String,
        token: Int,
        triggerPackage: String,
        secondCheck: Boolean
    ) {
        if (token != exitCheckToken || currentTargetPackage != target || interventionShowing) return

        val now = System.currentTimeMillis()
        val foreground = resolveForegroundPackage()

        if (foreground == target) {
            diag("EXIT cancelled target still foreground")
            return
        }

        // If the active window is temporarily unavailable or belongs to System
        // UI/keyboard, one extra check is safer than destroying the session.
        if (foreground == null || isTransientSystemPackage(foreground)) {
            if (!secondCheck) {
                handler.postDelayed({
                    verifyTargetExit(target, token, triggerPackage, secondCheck = true)
                }, FOREIGN_APP_SECOND_CHECK_MS)
                return
            }

            // Target activity has been quiet for long enough and still cannot be
            // found. Suspend, but keep the persisted session for a fast resume.
            if (now - lastTargetEventAt >= FOREIGN_APP_EXIT_CONFIRM_MS) {
                suspendTargetSession("verified away; foreground=$foreground trigger=$triggerPackage")
            }
            return
        }

        suspendTargetSession("foreground=$foreground trigger=$triggerPackage")
    }

    private fun resolveForegroundPackage(): String? {
        // Prefer a real application window. Accessibility overlays and temporary
        // system panels can become the "active" accessibility window for a moment
        // and were the main reason the V6 circle blinked on/off.
        val applicationWindow = runCatching {
            windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }
                .firstOrNull { it.isActive || it.isFocused }
                ?.root
                ?.packageName
                ?.toString()
        }.getOrNull()
        if (!applicationWindow.isNullOrBlank()) return applicationWindow

        return runCatching {
            rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
    }

    private fun isTransientSystemPackage(packageName: String): Boolean {
        return packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName == "com.google.android.inputmethod.latin" ||
            packageName == "com.samsung.android.honeyboard"
    }

    private fun setMode(newMode: ScreenMode, reason: String) {
        val now = System.currentTimeMillis()
        if (currentMode == newMode) {
            if (isBubbleSurfaceMode(newMode)) lastShortSurfaceSeenAt = now
            if (isShortVideoMode(newMode)) {
                resumeWatchClock("mode stable: $reason")
            }
            return
        }

        val old = currentMode
        if (isShortVideoMode(old) && !isShortVideoMode(newMode)) {
            pauseWatchClock("mode $old -> $newMode: $reason")
        }

        currentMode = newMode
        if (isBubbleSurfaceMode(newMode)) lastShortSurfaceSeenAt = now

        if (!isShortVideoMode(old) && isShortVideoMode(newMode)) {
            resumeWatchClock("mode $old -> $newMode: $reason")
        }
        diag("MODE $old -> $newMode reason=$reason")
    }

    private fun resumeWatchClock(reason: String) {
        if (!store.protectionEnabled || !isShortVideoMode(currentMode)) return
        if (watchSegmentStartedAt > 0L) return
        val now = System.currentTimeMillis()
        if (interventionPausedAt > 0L && nextInterventionAt > 0L) {
            val pausedFor = (now - interventionPausedAt).coerceAtLeast(0L)
            // Shift both ends of the reminder window. This keeps the interval
            // length constant so Home's progress ring and live setting changes
            // are based only on actual short-video watch time.
            sessionStartedAt += pausedFor
            nextInterventionAt += pausedFor
        }
        interventionPausedAt = 0L
        watchSegmentStartedAt = now
        diag("WATCH resume reason=$reason sessionMs=$sessionWatchedMs")
    }

    private fun flushActiveWatchTime() {
        val started = watchSegmentStartedAt
        if (started <= 0L) return
        val now = System.currentTimeMillis()
        if (now - started < WATCH_FLUSH_INTERVAL_MS) return
        val delta = (now - started).coerceAtLeast(0L)
        watchSegmentStartedAt = now
        sessionWatchedMs += delta
        store.addTodayWatchTime(delta, currentTargetPackage)
    }

    private fun pauseWatchClock(reason: String) {
        val started = watchSegmentStartedAt
        if (started <= 0L) return
        val now = System.currentTimeMillis()
        val delta = (now - started).coerceIn(0L, 10 * 60_000L)
        watchSegmentStartedAt = 0L
        sessionWatchedMs += delta
        store.addTodayWatchTime(delta, currentTargetPackage)
        if (nextInterventionAt > 0L && interventionPausedAt == 0L) {
            interventionPausedAt = now
        }
        diag("WATCH pause +${delta}ms reason=$reason sessionMs=$sessionWatchedMs")
    }

    private fun currentSessionWatchMs(): Long {
        val active = if (watchSegmentStartedAt > 0L) {
            (System.currentTimeMillis() - watchSegmentStartedAt).coerceAtLeast(0L)
        } else 0L
        return sessionWatchedMs + active
    }

    /** Returns every currently interactive root that belongs to the target app.
     * Some media apps render their full-screen short-video viewer in a window
     * that is not rootInActiveWindow for a brief period. Scanning interactive
     * windows makes Search/Explore openings and asynchronously-created players
     * much more reliable without treating unrelated apps as short-video feeds.
     */
    private fun targetRootsFor(packageName: String): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(4)
        val seen = HashSet<Int>()
        fun addRoot(root: AccessibilityNodeInfo?) {
            if (root == null) return
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isNotBlank() && pkg != packageName) return
            val key = System.identityHashCode(root)
            if (seen.add(key)) result.add(root)
        }
        addRoot(rootInActiveWindow)
        try {
            for (window in windows) addRoot(window.root)
        } catch (_: Exception) {
            // rootInActiveWindow is still available as the fallback.
        }
        return result
    }

    /** Direct resource-id lookup is much more reliable than walking a huge
     * accessibility tree. Modern Instagram Explore can leave the grid mounted
     * behind the full-screen viewer, and YouTube can expose a very large tree.
     * findAccessibilityNodeInfosByViewId lets us locate the actual player node
     * regardless of where it sits in that tree.
     */
    private fun findVisibleNodesBySimpleIds(packageName: String, simpleIds: List<String>): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        for (root in targetRootsFor(packageName)) {
            for (simple in simpleIds) {
                val fullId = if (simple.contains(":id/")) simple else "$packageName:id/$simple"
                val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(fullId) }.getOrNull().orEmpty()
                for (node in nodes) {
                    if (!node.isVisibleToUser) continue
                    val key = System.identityHashCode(node)
                    if (seen.add(key)) out.add(node)
                }
            }
        }
        return out
    }

    private fun nodeScreenCoverage(node: AccessibilityNodeInfo): Pair<Float, Float> {
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return Pair(rect.width().toFloat() / screenW, rect.height().toFloat() / screenH)
    }

    private fun nodeStateText(node: AccessibilityNodeInfo): String {
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString().orEmpty()
        } else ""
        return listOf(
            node.text?.toString().orEmpty(),
            node.contentDescription?.toString().orEmpty(),
            state
        ).joinToString(" ").trim().lowercase(Locale.ROOT)
    }

    private fun isSemanticallySelected(node: AccessibilityNodeInfo, expected: String): Boolean {
        val label = nodeStateText(node)
        val wanted = expected.lowercase(Locale.ROOT)
        if (!label.contains(wanted)) return false
        return node.isSelected || node.isChecked ||
            label.contains("selected") || label.contains("current") || label.contains("active")
    }

    /** Player trees such as YouTube Shorts are often attached a few hundred
     * milliseconds after the first window event. Retry only surface recognition;
     * never increment a counter from these retries.
     */
    private fun requestSurfaceRefresh(packageName: String, delayMs: Long = 90L) {
        if (currentTargetPackage != packageName) return
        if (surfaceRefreshPending.contains(packageName)) return

        val now = System.currentTimeMillis()
        val last = lastSurfaceRefreshAt[packageName] ?: 0L
        val throttleDelay = (SURFACE_REFRESH_MIN_GAP_MS - (now - last)).coerceAtLeast(0L)
        val delay = max(delayMs, throttleDelay)
        surfaceRefreshPending.add(packageName)
        handler.postDelayed({
            surfaceRefreshPending.remove(packageName)
            if (currentTargetPackage != packageName) return@postDelayed
            lastSurfaceRefreshAt[packageName] = System.currentTimeMillis()
            when {
                packageName == INSTAGRAM -> refreshInstagramSurface()
                packageName == YOUTUBE -> refreshYouTubeSurface()
                isTikTokPackage(packageName) -> refreshTikTokSurface(packageName)
                packageName == FACEBOOK -> refreshFacebookSurface()
            }
        }, delay)
    }

    /**
     * Retry only after a real window/navigation transition. Content-change events
     * use the coalesced requestSurfaceRefresh() path instead, so a playing video
     * cannot trigger dozens of expensive accessibility-tree scans per second.
     */
    private fun refreshTargetSurfaceWithRetries(packageName: String) {
        val token = (surfaceRefreshTokens[packageName] ?: 0) + 1
        surfaceRefreshTokens[packageName] = token
        requestSurfaceRefresh(packageName, 0L)
        longArrayOf(280L, 720L).forEach { delay ->
            handler.postDelayed({
                if (currentTargetPackage != packageName) return@postDelayed
                if (surfaceRefreshTokens[packageName] != token) return@postDelayed
                requestSurfaceRefresh(packageName, 0L)
            }, delay)
        }
    }

    private fun forceLeaveShortSurface(platform: String, reason: String) {
        when (platform) {
            "IG" -> {
                resetPagerState(instagramPager)
                resetFingerprintState(instagramFingerprint)
                instagramStrictPagerVisible = false
            }
            "YT" -> {
                resetPagerState(youtubePager)
                resetFingerprintState(youtubeFingerprint)
                cancelYouTubePendingCount("leave surface: $reason")
            }
            "TT" -> {
                resetPagerState(tiktokPager)
                resetFingerprintState(tiktokFingerprint)
                tiktokPollCandidate = 0
                tiktokPollCandidateSince = 0L
            }
            "FB" -> {
                resetPagerState(facebookPager)
                resetFingerprintState(facebookFingerprint)
            }
        }
        lastShortSurfaceSeenAt = 0L
        setMode(ScreenMode.OTHER, "$platform leave short surface: $reason")
        hideBubble()
    }

    // ---------------------------------------------------------------------
    // V18 stable-video detector pipeline
    // ---------------------------------------------------------------------

    private fun bpState(packageName: String): BPTrackState =
        bpTrackStates.getOrPut(packageName) { BPTrackState() }

    private fun bpResetPendingFor(packageName: String, keepRecent: Boolean) {
        val state = bpState(packageName)
        state.pendingToken++
        state.pendingId = null
        state.pendingIsAd = false
        state.stableId = null
        state.lastDetectedAt = 0L
        if (!keepRecent) state.recentIds.clear()
    }

    private fun bpCancelAllPending(reason: String) {
        bpScanToken++
        bpScanScheduled = false
        for ((_, state) in bpTrackStates) {
            state.pendingToken++
            state.pendingId = null
            state.pendingIsAd = false
            state.stableId = null
        }
        diag("BP pending cancelled: $reason")
    }

    private fun bpScheduleScan(packageName: String, delayMs: Long, reason: String) {
        if (currentTargetPackage != packageName || !isEnabledTarget(packageName)) return
        if (bpScanScheduled && delayMs > 0L) return
        bpScanScheduled = true
        val token = ++bpScanToken
        handler.postDelayed({
            if (token != bpScanToken) return@postDelayed
            bpScanScheduled = false
            bpRunScan(packageName, reason)
        }, delayMs.coerceAtLeast(0L))
    }

    private fun bpRunScan(packageName: String, reason: String) {
        if (currentTargetPackage != packageName || !isEnabledTarget(packageName)) return

        val root = rootInActiveWindow
        if (root == null) {
            // BrainPal explicitly avoids making decisions from an accessibility
            // snapshot that changes underneath detection. Keep the last state for
            // a short grace period and retry on the next event/watchdog scan.
            if (System.currentTimeMillis() - bpLastGoodRootAt > BP_ROOT_LOSS_GRACE_MS) {
                bpApplyDetection(packageName, BPDetection(false, debug = "root-null"), reason)
            }
            return
        }
        val rootPackage = root.packageName?.toString().orEmpty()
        if (rootPackage != packageName) {
            if (!isTransientSystemPackage(rootPackage) && rootPackage.isNotBlank()) {
                bpApplyDetection(packageName, BPDetection(false, debug = "root=$rootPackage"), reason)
            }
            return
        }
        bpLastGoodRootAt = System.currentTimeMillis()

        val detection = when {
            packageName == INSTAGRAM -> bpDetectInstagram(root)
            packageName == YOUTUBE -> bpDetectYouTube(root)
            isTikTokPackage(packageName) -> bpDetectTikTok(root, packageName)
            packageName == FACEBOOK -> bpDetectFacebook(root)
            else -> BPDetection(false)
        }
        bpApplyDetection(packageName, detection, reason)
    }

    private fun bpApplyDetection(packageName: String, detection: BPDetection, reason: String) {
        val state = bpState(packageName)
        val mode = bpModeFor(packageName, detection)

        if (!detection.detected || detection.panelOpen) {
            state.pendingToken++
            state.pendingId = null
            state.pendingIsAd = false
            setMode(mode, "BP ${detection.debug} $reason")
            hideBubble()
            return
        }

        state.lastDetectedAt = System.currentTimeMillis()
        setMode(mode, "BP ${detection.debug} $reason")
        updateBubble()

        val id = detection.videoId
        if (id.isBlank()) {
            diag("BP ${bpTag(packageName)} surface detected without stable video id")
            return
        }
        bpTrackVideoIdentifier(packageName, id, detection.isAd)
    }

    private fun bpModeFor(packageName: String, detection: BPDetection): ScreenMode {
        if (!detection.detected) return ScreenMode.OTHER
        if (detection.panelOpen) {
            return when {
                packageName == INSTAGRAM -> ScreenMode.INSTAGRAM_COMMENTS
                packageName == YOUTUBE -> ScreenMode.YOUTUBE_COMMENTS
                isTikTokPackage(packageName) -> ScreenMode.TIKTOK_COMMENTS
                packageName == FACEBOOK -> ScreenMode.FACEBOOK_COMMENTS
                else -> ScreenMode.OTHER
            }
        }
        return when {
            packageName == INSTAGRAM -> ScreenMode.INSTAGRAM_REELS
            packageName == YOUTUBE -> ScreenMode.YOUTUBE_SHORTS
            isTikTokPackage(packageName) -> ScreenMode.TIKTOK_FEED
            packageName == FACEBOOK -> ScreenMode.FACEBOOK_REELS
            else -> ScreenMode.OTHER
        }
    }

    private fun bpTag(packageName: String): String = when {
        packageName == INSTAGRAM -> "IG"
        packageName == YOUTUBE -> "YT"
        isTikTokPackage(packageName) -> "TT"
        packageName == FACEBOOK -> "FB"
        else -> "??"
    }

    private fun bpTrackVideoIdentifier(packageName: String, id: String, isAd: Boolean) {
        val state = bpState(packageName)
        val tag = bpTag(packageName)

        // A half-swipe commonly exposes the next item's accessibility subtree for
        // a moment.  Never count on that first observation.  BrainPal's own
        // ReelsScrollManager logs that a reel "counts at 750ms"; we independently
        // reproduce that dwell-confirmation model here.
        if (id == state.stableId) {
            if (state.pendingId != null) {
                state.pendingId = null
                state.pendingIsAd = false
                state.pendingToken++
                diag("BP $tag candidate snapped back to stable video")
            }
            return
        }

        if (state.pendingId == id) {
            // Same candidate is still visible. The original confirmation timer
            // remains authoritative; do not keep restarting it on noisy events.
            state.pendingIsAd = isAd
            return
        }

        state.pendingId = id
        state.pendingIsAd = isAd
        val token = ++state.pendingToken
        diag("BP $tag candidate=$id ad=$isAd (confirm ${BP_VIDEO_CONFIRM_MS}ms)")

        handler.postDelayed({
            if (token != state.pendingToken || state.pendingId != id) return@postDelayed
            if (currentTargetPackage != packageName || interventionShowing) return@postDelayed

            val root = rootInActiveWindow ?: return@postDelayed
            if (root.packageName?.toString() != packageName) return@postDelayed
            val fresh = when {
                packageName == INSTAGRAM -> bpDetectInstagram(root)
                packageName == YOUTUBE -> bpDetectYouTube(root)
                isTikTokPackage(packageName) -> bpDetectTikTok(root, packageName)
                packageName == FACEBOOK -> bpDetectFacebook(root)
                else -> BPDetection(false)
            }

            // The candidate must still be the same fully-visible video after the
            // dwell window. If a drag snapped back, comments opened, profile/home
            // replaced the viewer, or the accessibility snapshot changed, cancel.
            if (!fresh.detected || fresh.panelOpen || fresh.videoId != id) {
                state.pendingId = null
                state.pendingIsAd = false
                diag("BP $tag candidate did not survive dwell; no count")
                bpApplyDetection(packageName, fresh, "750ms confirmation changed")
                return@postDelayed
            }

            state.pendingId = null
            state.pendingIsAd = false
            state.stableId = id
            state.lastDetectedAt = System.currentTimeMillis()

            // Backward navigation: once a video has been counted recently, making
            // it current again updates the stable position but does not add +1.
            if (state.recentIds.contains(id)) {
                diag("BP $tag Recently counted, skipping: $id")
                return@postDelayed
            }

            bpRememberRecent(state, id)

            // BrainPal exposes isAd in its DetectionData.  MindScroll uses that
            // signal as a local-only "ignore sponsored" feature: the ad stays on
            // screen and watch time continues, but it does not increase the count.
            if (fresh.isAd && store.skipSponsoredVideos) {
                diag("BP $tag Sponsored/promoted video ignored: $id")
                updateBubble()
                return@postDelayed
            }

            commitForwardTransition(tag, bpModeFor(packageName, fresh), "stable-video-id-750ms")
            diag("BP $tag New scroll after dwell: $id")
            updateBubble()
        }, BP_VIDEO_CONFIRM_MS)
    }

    private fun bpRememberRecent(state: BPTrackState, id: String) {
        state.recentIds.remove(id)
        state.recentIds.addLast(id)
        while (state.recentIds.size > BP_RECENT_ID_LIMIT) state.recentIds.removeFirst()
    }

    private fun bpVisibleById(root: AccessibilityNodeInfo, fullId: String): List<AccessibilityNodeInfo> {
        return runCatching { root.findAccessibilityNodeInfosByViewId(fullId) }
            .getOrNull().orEmpty().filter { it.isVisibleToUser }
    }

    private fun bpVisibleBySimpleId(root: AccessibilityNodeInfo, packageName: String, simpleId: String): List<AccessibilityNodeInfo> =
        bpVisibleById(root, "$packageName:id/$simpleId")

    private fun bpBestCentered(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        if (nodes.isEmpty()) return null
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val cx = screenW / 2f
        val cy = screenH / 2f
        return nodes.maxByOrNull { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return@maxByOrNull Float.NEGATIVE_INFINITY
            val ncx = (r.left + r.right) / 2f
            val ncy = (r.top + r.bottom) / 2f
            val centerPenalty = (abs(ncx - cx) / screenW) + (abs(ncy - cy) / screenH)
            val coverage = (r.width().toFloat() / screenW) * (r.height().toFloat() / screenH)
            coverage * 3f - centerPenalty
        }
    }

    private fun bpNodeCoverage(node: AccessibilityNodeInfo): Float {
        val r = Rect()
        node.getBoundsInScreen(r)
        val sw = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        return (r.width().coerceAtLeast(0).toFloat() / sw) * (r.height().coerceAtLeast(0).toFloat() / sh)
    }

    private fun bpNormalizeText(raw: CharSequence?): String? {
        var value = raw?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (value.isBlank()) return null
        value = value.replace(Regex("\\s+"), " ").take(180)
        if (value.length < 2) return null
        if (value.matches(Regex("^[\\d\\s.,:/%+\\-–—km]+$", RegexOption.IGNORE_CASE))) return null
        if (value.contains("video progress") || value.contains("seconds of") || value.contains("minute of") ||
            value.contains("minutes of") || value.matches(Regex("^\\d+:\\d+( / \\d+:\\d+)?$"))) return null

        // Dynamic counters/timestamps must never become part of a video identity.
        if (value.matches(Regex("^[\\d.,]+[kmb]?\\s*(likes?|views?|comments?|replies?|shares?)$"))) return null
        if (value.matches(Regex("^\\d+\\s*(seconds?|minutes?|hours?|days?|weeks?|months?|years?)\\s+ago$"))) return null
        if (value.matches(Regex("^\\d+[smhdwy]$"))) return null

        val exactControls = setOf(
            "home", "shorts", "reels", "library", "you", "create", "search", "more options",
            "subscriptions", "like", "dislike", "comment", "comments", "share", "remix", "save",
            "follow", "following", "for you", "friends", "profile", "back", "send", "close",
            "subscribe", "subscribed", "live", "trends", "reactions", "not interested",
            "content available", "new content is available", "view", "see more", "go to", "donate"
        )
        if (value in exactControls) return null
        if (value.startsWith("like this video") || value.startsWith("dislike this video") ||
            value.startsWith("share this") || value.startsWith("remix this") || value.startsWith("view comments") ||
            value.startsWith("navigate to ")) return null
        return value
    }

    private fun bpStableSemantic(node: AccessibilityNodeInfo?, maxNodes: Int = 220): String {
        node ?: return ""
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes && out.size < 28) {
            val n = queue.removeFirst()
            visited++
            if (n.isVisibleToUser) {
                bpNormalizeText(n.text)?.let(out::add)
                bpNormalizeText(n.contentDescription)?.let(out::add)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return out.joinToString("|")
    }

    private fun bpHash(prefix: String, vararg pieces: String): String {
        val material = pieces.filter { it.isNotBlank() }.joinToString("|")
        if (material.isBlank()) return ""
        return prefix + "_" + Integer.toHexString(material.hashCode())
    }

    private fun bpAnyVisibleText(root: AccessibilityNodeInfo, needles: List<String>, maxNodes: Int = 320): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val n = queue.removeFirst()
            visited++
            if (n.isVisibleToUser) {
                val text = ((n.text?.toString().orEmpty()) + " " + (n.contentDescription?.toString().orEmpty()))
                    .lowercase(Locale.ROOT)
                if (needles.any { text.contains(it) }) return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return false
    }

    private fun bpAnySelectedLabel(root: AccessibilityNodeInfo, labels: Set<String>, maxNodes: Int = 260): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val n = queue.removeFirst()
            visited++
            if (n.isVisibleToUser && n.isSelected) {
                val raw = ((n.text?.toString().orEmpty()) + " " + (n.contentDescription?.toString().orEmpty()))
                    .trim().lowercase(Locale.ROOT)
                if (labels.any { raw == it || raw.startsWith("$it,") || raw.contains("$it tab") }) return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return false
    }

    private fun bpDetectInstagram(root: AccessibilityNodeInfo): BPDetection {
        val pager = bpVisibleById(root, "com.instagram.android:id/clips_viewer_view_pager")
        if (pager.isEmpty()) return BPDetection(false, debug = "IG no clips pager")

        val commentEditor = bpVisibleById(root, "com.instagram.android:id/layout_comment_thread_edittext").isNotEmpty()
        // The actual comment composer is the strongest panel signal. A snackbar is
        // transient and therefore is not used alone to classify the whole screen.
        if (commentEditor) return BPDetection(true, panelOpen = true, debug = "IG comments")

        val videoNodes = bpVisibleById(root, "com.instagram.android:id/clips_video_container") +
            bpVisibleById(root, "com.instagram.android:id/clips_media_component")
        val currentVideo = bpBestCentered(videoNodes)
        val captions = bpVisibleById(root, "com.instagram.android:id/clips_caption_component")
        val currentCaption = bpBestCentered(captions)
        val videoSemantic = bpStableSemantic(currentVideo, 180)
        val captionSemantic = bpStableSemantic(currentCaption, 100)
        val fallbackSemantic = if (videoSemantic.isBlank() && captionSemantic.isBlank()) {
            bpStableSemantic(bpBestCentered(pager), 260)
        } else ""
        val combined = listOf(videoSemantic, captionSemantic, fallbackSemantic).filter { it.isNotBlank() }.joinToString("|")
        val ad = combined.contains("sponsored reel by") || combined.contains("sponsored")
        return BPDetection(
            detected = true,
            videoId = bpHash(if (ad) "ig_ad" else "ig", combined),
            panelOpen = false,
            isAd = ad,
            debug = "IG clips pager"
        )
    }

    private fun bpDetectYouTube(root: AccessibilityNodeInfo): BPDetection {
        val recycler = bpVisibleById(root, "com.google.android.youtube:id/reel_recycler")
        if (recycler.isEmpty()) return BPDetection(false, debug = "YT no reel_recycler")

        val engagement = bpVisibleById(root, "com.google.android.youtube:id/app_engagement_panel").isNotEmpty()
        val bottomSheet = bpVisibleById(root, "com.google.android.youtube:id/design_bottom_sheet").isNotEmpty()
        if (engagement || bottomSheet) {
            return BPDetection(true, panelOpen = true, debug = "YT engagement panel")
        }

        val pageNodes = bpVisibleById(root, "com.google.android.youtube:id/reel_player_page_content")
        val subtitleNodes = bpVisibleById(root, "com.google.android.youtube:id/subtitle_window_identifier")
        val page = bpBestCentered(pageNodes) ?: bpBestCentered(subtitleNodes) ?: bpBestCentered(recycler)
        val semantic = bpStableSemantic(page, 260)
        val adCtas = listOf("get offer", "download now", "shop now", "visit site", "install", "order now", "learn more", "sign up")
        val ad = adCtas.any { semantic.contains(it) } || semantic.contains("sponsored")
        return BPDetection(
            detected = true,
            videoId = bpHash(if (ad) "yt_ad" else "yt", semantic),
            panelOpen = false,
            isAd = ad,
            debug = "YT reel_recycler"
        )
    }

    /**
     * TikTok detector rebuilt from the behavior of the user-supplied BrainPal APK.
     *
     * BrainPal's current TikTok path is intentionally much simpler than the old
     * MindScroll heuristic detector:
     *   1) find the first *visible* `<package>:id/view_rootview`;
     *   2) read `<package>:id/title` text;
     *   3) if title is unavailable, read the content-description from one of two
     *      obfuscated ids (trill: s4q/fb6, musically: wbs/go5);
     *   4) read `<package>:id/desc` text;
     *   5) build a stable video id from those values.
     *
     * Crucially, the feed is considered detected as soon as a visible
     * view_rootview exists, even if metadata is temporarily missing. This is what
     * makes BrainPal show its tracker reliably while TikTok is rebuilding the
     * current video subtree.
     */
    private fun bpDetectTikTok(root: AccessibilityNodeInfo, packageName: String): BPDetection {
        val primaryIds = if (packageName == TIKTOK_ALT) {
            listOf("s4q", "fb6")
        } else {
            listOf("wbs", "go5")
        }

        // BrainPal asks findAccessibilityNodeInfosByViewId for view_rootview and
        // uses the first visible result. Do the same rather than scoring generic
        // full-screen containers.
        val videoRoot = bpFirstVisibleByFullId(root, "$packageName:id/view_rootview")
            ?: return BPDetection(false, debug = "TT no visible view_rootview")

        val titleId = "$packageName:id/title"
        val descId = "$packageName:id/desc"
        val primaryId = "$packageName:id/${primaryIds[0]}"
        val secondaryId = "$packageName:id/${primaryIds[1]}"

        // Match BrainPal's q.C/q.B behavior: q.C reads TEXT from the first
        // visible matching node; q.B reads CONTENT DESCRIPTION.
        val main = bpFirstVisibleTextByFullId(videoRoot, titleId)
            ?: bpFirstVisibleContentDescriptionByFullId(videoRoot, primaryId)
            ?: bpFirstVisibleContentDescriptionByFullId(videoRoot, secondaryId)

        val desc = bpFirstVisibleTextByFullId(videoRoot, descId)

        // BrainPal still reports the TikTok short-video surface as detected when
        // title/description are briefly absent. Keeping detection separate from
        // identity prevents the bubble/timer from disappearing during subtree
        // replacement. Counting starts as soon as a stable id is available.
        val videoId = when {
            !main.isNullOrBlank() && !desc.isNullOrBlank() ->
                "tt_${main.hashCode()}_${desc.hashCode()}"
            !main.isNullOrBlank() -> "tt_${main.hashCode()}"
            else -> ""
        }

        val adMaterial = listOfNotNull(main, desc).joinToString(" ").lowercase(Locale.ROOT)
        val isAd = listOf(
            "sponsored", "paid partnership", "shop now", "learn more",
            "install now", "download now", "get offer"
        ).any(adMaterial::contains)

        return BPDetection(
            detected = true,
            videoId = videoId,
            panelOpen = false,
            isAd = isAd,
            debug = if (videoId.isBlank()) {
                "TT view_rootview visible; waiting for BrainPal-style identity"
            } else {
                "TT BrainPal-style view_rootview identity"
            }
        )
    }

    private fun bpFirstVisibleByFullId(
        scope: AccessibilityNodeInfo,
        fullId: String
    ): AccessibilityNodeInfo? {
        return runCatching { scope.findAccessibilityNodeInfosByViewId(fullId) }
            .getOrNull().orEmpty().firstOrNull { it.isVisibleToUser }
    }

    private fun bpWhitespaceNormalized(raw: CharSequence?): String? {
        val value = raw?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return value.takeIf { it.isNotBlank() }
    }

    private fun bpFirstVisibleTextByFullId(
        scope: AccessibilityNodeInfo,
        fullId: String
    ): String? {
        val node = bpFirstVisibleByFullId(scope, fullId) ?: return null
        return bpWhitespaceNormalized(node.text)
    }

    private fun bpFirstVisibleContentDescriptionByFullId(
        scope: AccessibilityNodeInfo,
        fullId: String
    ): String? {
        val node = bpFirstVisibleByFullId(scope, fullId) ?: return null
        return bpWhitespaceNormalized(node.contentDescription)
    }

    private fun bpDetectFacebook(root: AccessibilityNodeInfo): BPDetection {
        var commentsOpen = false
        var hardNonReels = false
        var reelEvidence = 0
        var controls = 0
        var largeVideo: AccessibilityNodeInfo? = null
        val stable = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val sw = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(1)

        while (queue.isNotEmpty() && visited < 520) {
            val n = queue.removeFirst()
            visited++
            if (n.isVisibleToUser) {
                val id = n.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                val raw = ((n.text?.toString().orEmpty()) + " " + (n.contentDescription?.toString().orEmpty()))
                    .trim().lowercase(Locale.ROOT)
                val normalized = bpNormalizeText(raw)
                val rect = Rect(); n.getBoundsInScreen(rect)
                val wr = rect.width().toFloat() / sw
                val hr = rect.height().toFloat() / sh

                if (id.contains("comment") && (id.contains("sheet") || id.contains("thread") || id.contains("composer") || id.contains("list")) ||
                    raw.contains("write a comment") || raw.contains("add a comment") || raw.contains("view replies")) {
                    commentsOpen = true
                }
                if (raw.contains("edit profile") || raw.contains("manage posts") || id.contains("profile_header") ||
                    id.contains("profile_tab_content") || ((raw == "profile" || raw.startsWith("profile,")) && n.isSelected)) {
                    hardNonReels = true
                }
                if (((raw == "home" || raw.startsWith("home,")) || raw.startsWith("friends,") || raw.startsWith("menu,")) && n.isSelected) {
                    hardNonReels = true
                }

                if (id.contains("reel") || id.contains("short_form_video") || raw == "reels" || raw.startsWith("reels,")) reelEvidence++
                if (raw == "like" || raw.startsWith("like,") || raw == "comment" || raw.startsWith("comment,") ||
                    raw == "share" || raw.startsWith("share,")) controls++

                if (wr >= 0.58f && hr >= 0.50f &&
                    (id.contains("video") || id.contains("player") || id.contains("media") || id.contains("reel"))) {
                    if (largeVideo == null || bpNodeCoverage(n) > bpNodeCoverage(largeVideo!!)) largeVideo = n
                }
                normalized?.let { if (it.length <= 180) stable.add(it) }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }

        val detected = !hardNonReels && !commentsOpen && (reelEvidence >= 1 && controls >= 2 || largeVideo != null && controls >= 3)
        if (!detected) return BPDetection(false, panelOpen = commentsOpen, debug = "FB no verified reel surface")

        val videoSemantic = bpStableSemantic(largeVideo, 200)
        val fallback = stable.take(24).joinToString("|")
        val semantic = if (videoSemantic.isNotBlank()) videoSemantic else fallback
        val fbAdCtas = listOf(
            "sponsored", "paid partnership", "get directions", "watch more", "shop now", "learn more",
            "sign up", "install", "install now", "book now", "order now", "get offer", "download",
            "send message", "send whatsapp message", "contact us", "get quote", "apply now", "see menu",
            "use app", "play game"
        )
        val ad = fbAdCtas.any { semantic.contains(it) }
        return BPDetection(
            detected = true,
            videoId = bpHash(if (ad) "fb_ad" else "fb", semantic),
            panelOpen = commentsOpen,
            isAd = ad,
            debug = "FB stable semantic reel"
        )
    }

    // ---------------------------------------------------------------------
    // Instagram detector — pager-index based
    // ---------------------------------------------------------------------

    private fun handleInstagramEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                refreshTargetSurfaceWithRetries(INSTAGRAM)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                requestSurfaceRefresh(INSTAGRAM, 110L)
                // Search/Explore full-screen videos do not always expose the same
                // pager index callbacks as the dedicated Reels tab.  Once that
                // full-screen surface is already verified, a settled metadata
                // change is a second, conservative way to confirm a new video.
                if (currentMode == ScreenMode.INSTAGRAM_REELS && !instagramStrictPagerVisible) {
                    scheduleFingerprintTransition("IG", instagramFingerprint, ScreenMode.INSTAGRAM_REELS, 0)
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val now = System.currentTimeMillis()
                val nav = instagramNavigationDestination(event.source)
                if (nav != null && nav != "reels") {
                    if (nav == "search" || nav == "explore") {
                        instagramExploreContextSeenAt = now
                        diag("IG EXPLORE context armed by click")
                    } else {
                        // Instagram can leave the old Reel pager mounted for a
                        // short moment after Home/Profile/Messages is selected.
                        // Suppress re-arming during that stale-tree window.
                        instagramNonVideoNavSuppressUntil = now + NON_VIDEO_NAV_SUPPRESS_MS
                    }
                    forceLeaveShortSurface("IG", "navigation=$nav")
                    refreshTargetSurfaceWithRetries(INSTAGRAM)
                } else if (isInstagramSearchNavigationClick(event.source)) {
                    instagramExploreContextSeenAt = now
                    diag("IG EXPLORE context armed by click")
                    forceLeaveShortSurface("IG", "search navigation")
                    refreshTargetSurfaceWithRetries(INSTAGRAM)
                } else if (instagramExploreContextSeenAt > 0L &&
                    now - instagramExploreContextSeenAt <= EXPLORE_CONTEXT_MEMORY_MS &&
                    currentMode != ScreenMode.INSTAGRAM_REELS
                ) {
                    // A tap inside Search/Explore usually means a grid item was
                    // opened. Keep the context alive while Instagram swaps the
                    // grid for its full-screen media viewer.
                    instagramExploreContextSeenAt = now
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleInstagramPagerScroll(event)

            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                val nav = instagramNavigationDestination(event.source)
                if (nav != null && nav != "reels") {
                    val now = System.currentTimeMillis()
                    if (nav == "search" || nav == "explore") {
                        instagramExploreContextSeenAt = now
                    } else {
                        instagramNonVideoNavSuppressUntil = now + NON_VIDEO_NAV_SUPPRESS_MS
                    }
                    forceLeaveShortSurface("IG", "selected navigation=$nav")
                    refreshTargetSurfaceWithRetries(INSTAGRAM)
                } else {
                    if (nav == "reels") instagramNonVideoNavSuppressUntil = 0L
                    handleInstagramPagerScroll(event)
                }
            }
        }
    }

    private fun refreshInstagramSurface() {
        if (currentTargetPackage != INSTAGRAM) return
        val now = System.currentTimeMillis()
        if (now < instagramNonVideoNavSuppressUntil) {
            resetPagerState(instagramPager)
            setMode(ScreenMode.OTHER, "IG non-video navigation suppression")
            hideBubble()
            return
        }

        val state = scanInstagramSurface()

        // V16 rule: the bubble/timer are driven by what is visible NOW, not by a
        // stale "pager was active recently" flag. Instagram keeps old fragments
        // mounted after switching tabs; using pager.active as a display signal is
        // what made Profile/Home look like Reels and even let their scrolls count.
        if (state.viewerOpen) {
            instagramPager.lastSeenAt = now
            if (!instagramPager.active) {
                instagramPager.active = true
                instagramPager.page = -1
                instagramPager.armedAt = now
                instagramPager.lastIndexEventAt = 0L
                cancelPagerFallback(instagramPager)
                diag("IG PAGER armed")
            }
        } else {
            if (instagramPager.active && now - instagramPager.lastSeenAt > 220L) {
                instagramPager.active = false
                instagramPager.page = -1
                instagramPager.armedAt = 0L
                cancelPagerFallback(instagramPager)
                diag("IG PAGER hidden/disarmed after verified viewer absence")
            }
        }

        val newMode = when {
            state.commentsOpen -> ScreenMode.INSTAGRAM_COMMENTS
            state.viewerOpen -> ScreenMode.INSTAGRAM_REELS
            else -> ScreenMode.OTHER
        }
        setMode(newMode, "IG visibleViewer=${state.viewerOpen} comments=${state.commentsOpen}")

        if (currentMode == ScreenMode.INSTAGRAM_REELS && sessionVideoNumber == 0) {
            sessionVideoNumber = 1
            persistSession(force = true)
            diag("IG first visible reel -> session=1")
        }
        if (currentMode == ScreenMode.INSTAGRAM_REELS) primeFingerprint("IG", instagramFingerprint)

        updateBubble()
    }

    private fun handleInstagramPagerScroll(event: AccessibilityEvent) {
        if (interventionShowing || currentTargetPackage != INSTAGRAM) return

        val surface = scanInstagramSurface()
        val exactPagerSource = isInstagramPagerSource(event.source)

        // Never trust a stale pager.active flag for a new scroll event. Instagram
        // leaves old Reel fragments mounted while Home/Profile is visible. A
        // scroll is Reel navigation only when the current tree still proves the
        // fullscreen viewer, or the event source itself belongs to the Reel pager.
        if (!surface.viewerOpen && !exactPagerSource) {
            diagScrollRejected(event, "IG rejected: current screen is not Reel viewer")
            setMode(if (surface.commentsOpen) ScreenMode.INSTAGRAM_COMMENTS else ScreenMode.OTHER,
                "IG non-Reel scroll")
            hideBubble()
            requestSurfaceRefresh(INSTAGRAM, 0L)
            return
        }

        val now = System.currentTimeMillis()
        lastInstagramNavigationSignalAt = now
        if (!instagramPager.active) {
            instagramPager.active = true
            instagramPager.page = -1
            instagramPager.armedAt = now
            instagramPager.lastSeenAt = now
            diag("IG PAGER re-armed source=$exactPagerSource tree=${surface.viewerOpen}")
        } else {
            instagramPager.lastSeenAt = now
        }

        setMode(
            if (surface.commentsOpen) ScreenMode.INSTAGRAM_COMMENTS else ScreenMode.INSTAGRAM_REELS,
            "IG pager scroll comments=${surface.commentsOpen} exact=$exactPagerSource"
        )
        if (surface.commentsOpen) {
            diagScrollRejected(event, "IG comments open")
            updateBubble()
            return
        }

        if (sessionVideoNumber == 0) {
            sessionVideoNumber = 1
            persistSession(force = true)
        }

        val from = event.fromIndex
        val to = event.toIndex
        val semanticIndex = extractCollectionIndex(event.source)
        val cur = when {
            to >= 0 -> to
            event.currentItemIndex >= 0 -> event.currentItemIndex
            semanticIndex >= 0 -> semanticIndex
            from >= 0 -> from
            else -> -1
        }
        val dy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        val directionHint = directionHint(from, to, dy)
        diagPagerScroll("IG", event, cur, dy)

        // Keep the proven Instagram page-index path. Only the display/surface
        // qualification above changed in V16.
        when {
            cur >= 0 -> {
                instagramPager.lastIndexEventAt = now
                cancelPagerFallback(instagramPager)
                handlePagerIndex("IG", instagramPager, from, to, cur, ScreenMode.INSTAGRAM_REELS)
                primeFingerprint("IG", instagramFingerprint)
            }
            (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
                (exactPagerSource || surface.viewerOpen) -> {
                collectPagerFallback("IG", instagramPager, dy, ScreenMode.INSTAGRAM_REELS)
            }
            else -> scheduleFingerprintTransition("IG", instagramFingerprint, ScreenMode.INSTAGRAM_REELS, directionHint)
        }
        updateBubble()
    }

    private fun instagramNavigationDestination(source: AccessibilityNodeInfo?): String? {
        var node = source
        var depth = 0
        while (node != null && depth < 7) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            fun exact(name: String): Boolean = label == name || label.startsWith("$name,") ||
                label.contains("$name tab") || id.contains("${name}_tab") || id.endsWith("/$name")
            when {
                exact("reels") || exact("reel") -> return "reels"
                exact("home") -> return "home"
                exact("profile") -> return "profile"
                exact("search") -> return "search"
                exact("explore") || label.contains("search and explore") -> return "explore"
                exact("messages") || exact("inbox") || label.contains("direct") -> return "messages"
            }
            node = node.parent
            depth++
        }
        return null
    }

    private fun isInstagramSearchNavigationClick(source: AccessibilityNodeInfo?): Boolean {
        var node = source
        var depth = 0
        while (node != null && depth < 7) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (label.contains("search") || label.contains("explore") ||
                id.contains("search") || id.contains("explore") ||
                (cls.contains("edittext") && label.contains("search"))) {
                return true
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun scanInstagramSurface(): SurfaceState {
        val roots = targetRootsFor(INSTAGRAM)
        if (roots.isEmpty()) {
            instagramStrictPagerVisible = false
            return SurfaceState(false, false, false)
        }

        // The dedicated Reels tab is the easy case: current Instagram builds
        // expose one shared full-screen clips pager.  Search/Explore grid opens
        // can transiently hide that exact id, so V12 keeps a second geometric +
        // semantic fallback rather than treating the entire Instagram app as a
        // Reel surface.
        val strongIds = listOf(
            "clips_viewer_view_pager",
            "root_clips_layout",
            "clips_viewer_container",
            "clips_video_container",
            "reel_viewer",
            "reels_viewer",
            "reel_pager"
        )
        val directPlayerNodes = findVisibleNodesBySimpleIds(INSTAGRAM, strongIds)
        var strictPagerDetected = directPlayerNodes.any { node ->
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val (w, h) = nodeScreenCoverage(node)
            id.contains("clips_viewer_view_pager") ||
                id.contains("root_clips_layout") ||
                ((w >= 0.55f && h >= 0.50f) && isInstagramPagerId(id))
        }
        var viewerOpen = strictPagerDetected

        var commentsOpen = false
        var reelsTabSelected = false
        var exploreContext = false
        var searchScreenEvidence = false
        var homeTabSelected = false
        var nonVideoTabSelected = false
        var profileScreenEvidence = 0
        var explicitFullscreenMediaViewer = false
        var fullscreenGeometryCandidate = false
        val actionKinds = HashSet<String>()

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 2600) {
                val node = queue.removeFirst()
                visited++
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                val combined = nodeStateText(node)
                val (w, h) = nodeScreenCoverage(node)
                val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()

                if (isInstagramPagerId(id) && node.isVisibleToUser && (w >= 0.55f && h >= 0.50f)) {
                    strictPagerDetected = true
                    viewerOpen = true
                }

                if ((id.contains("clips_tab") || id.contains("reels_tab")) &&
                    isSemanticallySelected(node, "reel")) {
                    reelsTabSelected = true
                }

                // Instagram's bottom navigation labels vary by build.  Do not
                // require the resource id itself to contain "search"; the
                // selected contentDescription/text is enough to remember that a
                // full-screen viewer was entered from Search/Explore.
                if ((combined == "search" || combined.startsWith("search,") ||
                        combined.contains("search and explore") || combined == "explore") &&
                    (node.isSelected || node.isChecked || combined.contains("selected") ||
                        combined.contains("current") || id.contains("search") || id.contains("explore"))) {
                    exploreContext = true
                }

                // Search results screens normally expose a real search field,
                // even when the bottom navigation icon itself does not report
                // selected=true. This is a much safer Search/Explore signal than
                // merely seeing the always-visible search icon on Home.
                val searchFieldLike = cls.contains("edittext") ||
                    id.contains("search_bar") || id.contains("search_input") ||
                    id.contains("search_edit") || id.contains("search_box")
                if (searchFieldLike && (id.contains("search") || combined.contains("search"))) {
                    searchScreenEvidence = true
                }
                if ((combined == "home" || combined.startsWith("home,")) &&
                    (node.isSelected || node.isChecked || combined.contains("selected") || combined.contains("current"))) {
                    homeTabSelected = true
                    nonVideoTabSelected = true
                }
                if ((combined == "profile" || combined.startsWith("profile,") ||
                        combined == "messages" || combined.startsWith("messages,") ||
                        combined == "inbox" || combined.startsWith("inbox,")) &&
                    (node.isSelected || node.isChecked || combined.contains("selected") || combined.contains("current"))) {
                    nonVideoTabSelected = true
                }

                // Own-profile screens expose controls that never belong to the
                // full-screen Reel viewer. Instagram sometimes keeps the old
                // clips pager mounted behind Profile, so these visible profile
                // controls must override that stale pager.
                if (combined.contains("edit profile") ||
                    combined.contains("share profile") ||
                    combined.contains("professional dashboard") ||
                    combined.contains("profile settings") ||
                    id.contains("edit_profile") ||
                    id.contains("profile_header")) {
                    profileScreenEvidence++
                }

                if (
                    (id.contains("comment") &&
                        (id.contains("sheet") || id.contains("list") || id.contains("composer") || id.contains("thread"))) ||
                    id.contains("comments_container") || id.contains("bottom_sheet") || id.contains("bottomsheet") ||
                    combined.contains("add a comment") || combined.contains("view replies") ||
                    combined.contains("hide replies") || combined.contains("replying to")
                ) commentsOpen = true

                val viewerNamed = id.contains("media_viewer") || id.contains("post_viewer") ||
                    id.contains("fullscreen_media") || id.contains("full_screen_media") ||
                    (id.contains("clips") && (id.contains("container") || id.contains("root"))) ||
                    cls.contains("viewpager") || cls.contains("viewpager2")
                if (!commentsOpen && w >= 0.72f && h >= 0.68f && viewerNamed) {
                    explicitFullscreenMediaViewer = true
                }

                // Geometry fallback for Search/Explore opens where IG hides the
                // pager id.  A normal Home post is not tall enough to satisfy
                // this full-screen requirement on the S25-class aspect ratio.
                val mediaLike = id.contains("video") || id.contains("player") || id.contains("clips") ||
                    id.contains("media") || cls.contains("surfaceview") || cls.contains("textureview") ||
                    cls.contains("viewpager") || cls.contains("recyclerview") || node.isScrollable
                if (!commentsOpen && node.isVisibleToUser && w >= 0.82f && h >= 0.76f && mediaLike) {
                    fullscreenGeometryCandidate = true
                }

                fun action(kind: String, vararg needles: String) {
                    if (needles.any { combined.contains(it) || id.contains(it) }) actionKinds.add(kind)
                }
                action("like", "like")
                action("comment", "comment")
                action("share", "share", "send")
                action("save", "save")
                action("audio", "audio", "sound", "music")

                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }

        val now = System.currentTimeMillis()
        if (nonVideoTabSelected || profileScreenEvidence > 0) {
            // Do not let an old off-screen pager resurrect the bubble after the
            // user has clearly selected Home/Profile/Messages. Profile evidence
            // is intentionally allowed to override a stale visible pager node.
            viewerOpen = false
            strictPagerDetected = false
        }
        if (exploreContext || searchScreenEvidence) instagramExploreContextSeenAt = now
        val recentExplore = instagramExploreContextSeenAt > 0L &&
            now - instagramExploreContextSeenAt <= EXPLORE_CONTEXT_MEMORY_MS

        // Search/Explore Reel fallback.  We require a remembered Search context,
        // a genuinely full-screen media surface and several Reel controls. This
        // avoids the V10 regression where simply opening Instagram Home started
        // the timer.
        val alreadyVerifiedFullscreenViewer = instagramPager.active &&
            currentMode == ScreenMode.INSTAGRAM_REELS
        if (!viewerOpen && !commentsOpen && !homeTabSelected &&
            (exploreContext || searchScreenEvidence || recentExplore || alreadyVerifiedFullscreenViewer)) {
            // Explore/Search full-screen viewers are not consistent across IG
            // builds: some expose clips_viewer_view_pager, others expose only
            // the familiar Reel action stack.  Requiring 3 independent controls
            // (for example Like + Comment + Share) plus remembered Search context
            // is specific enough to avoid arming on the normal Home feed.
            val semanticFullscreenViewer = actionKinds.size >= 3
            if (semanticFullscreenViewer ||
                ((explicitFullscreenMediaViewer || fullscreenGeometryCandidate) && actionKinds.size >= 2)) {
                viewerOpen = true
            }
        }

        instagramStrictPagerVisible = strictPagerDetected
        return SurfaceState(viewerOpen, commentsOpen, reelsTabSelected)
    }

    private fun isInstagramPagerId(rawId: String): Boolean {
        val id = rawId.lowercase(Locale.ROOT)
        return id.contains("clips_viewer_view_pager") ||
            id.contains("root_clips_layout") ||
            id.contains("clips_viewer_container") ||
            id.contains("reel_viewer") || id.contains("reels_viewer") ||
            id.contains("reel_pager") || id.contains("clips_video_container")
    }

    private fun isInstagramPagerSource(source: AccessibilityNodeInfo?): Boolean {
        var node = source
        var depth = 0
        while (node != null && depth < 8) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            if (isInstagramPagerId(id)) return true

            // A comment/reply container is explicitly not the Reel pager even
            // if a large Reels container is somewhere above it.
            if (
                id.contains("comment") ||
                id.contains("reply") ||
                id.contains("bottom_sheet") ||
                id.contains("bottomsheet")
            ) {
                return false
            }
            node = node.parent
            depth++
        }
        return false
    }

    // ---------------------------------------------------------------------
    // YouTube Shorts detector — same pager-index model where available
    // ---------------------------------------------------------------------

    private fun handleYouTubeEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshTargetSurfaceWithRetries(YOUTUBE)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content changes alone are never a watched-video transition.
                // Likes/progress/captions/comments can all rebuild the tree while
                // the same Short remains visible. V16 only counts after a user
                // scroll gesture AND a stable final video identity change.
                requestSurfaceRefresh(YOUTUBE, 140L)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (isYouTubeCommentInteraction(event.source)) {
                    youtubeCommentGuardUntil = System.currentTimeMillis() + YOUTUBE_COMMENT_GUARD_MS
                    cancelYouTubePendingCount("comment control clicked")
                    setMode(ScreenMode.YOUTUBE_COMMENTS, "YT comment control clicked")
                    hideBubble()
                    refreshTargetSurfaceWithRetries(YOUTUBE)
                } else {
                    val destination = youtubeNavigationDestination(event.source)
                    if (destination != null && destination != "shorts") {
                        youtubeNonVideoNavSuppressUntil = System.currentTimeMillis() + NON_VIDEO_NAV_SUPPRESS_MS
                        forceLeaveShortSurface("YT", "navigation=$destination")
                        refreshTargetSurfaceWithRetries(YOUTUBE)
                    } else if (destination == "shorts") {
                        youtubeNonVideoNavSuppressUntil = 0L
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleYouTubePagerScroll(event)
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                val destination = youtubeNavigationDestination(event.source)
                if (destination != null && destination != "shorts") {
                    youtubeNonVideoNavSuppressUntil = System.currentTimeMillis() + NON_VIDEO_NAV_SUPPRESS_MS
                    forceLeaveShortSurface("YT", "selected navigation=$destination")
                    refreshTargetSurfaceWithRetries(YOUTUBE)
                } else {
                    if (destination == "shorts") youtubeNonVideoNavSuppressUntil = 0L
                    handleYouTubePagerScroll(event)
                }
            }
        }
    }

    private fun refreshYouTubeSurface() {
        if (currentTargetPackage != YOUTUBE) return
        val now = System.currentTimeMillis()
        if (now < youtubeNonVideoNavSuppressUntil) {
            resetPagerState(youtubePager)
            cancelYouTubePendingCount("non-video navigation suppression")
            setMode(ScreenMode.OTHER, "YT non-video navigation suppression")
            hideBubble()
            return
        }

        val scannedState = scanYouTubeSurface()
        val state = if (now < youtubeCommentGuardUntil) {
            scannedState.copy(commentsOpen = true)
        } else scannedState

        if (state.viewerOpen) {
            youtubePager.lastSeenAt = now
            if (!youtubePager.active) {
                youtubePager.active = true
                youtubePager.page = -1
                youtubePager.armedAt = now
                youtubePager.lastIndexEventAt = 0L
                cancelPagerFallback(youtubePager)
                diag("YT PAGER armed")
            }
        } else if (youtubePager.active && now - youtubePager.lastSeenAt > 220L) {
            youtubePager.active = false
            youtubePager.page = -1
            youtubePager.armedAt = 0L
            cancelPagerFallback(youtubePager)
            diag("YT PAGER hidden/disarmed after verified viewer absence")
        }

        if (state.commentsOpen) cancelYouTubePendingCount("surface scan found comments")

        // The visible player decides whether the bubble/timer are active. Do not
        // keep Shorts mode alive from a stale pager flag after switching tabs.
        val newMode = when {
            state.commentsOpen -> ScreenMode.YOUTUBE_COMMENTS
            state.viewerOpen -> ScreenMode.YOUTUBE_SHORTS
            else -> ScreenMode.OTHER
        }
        setMode(newMode, "YT visibleViewer=${state.viewerOpen} comments=${state.commentsOpen}")

        if (currentMode == ScreenMode.YOUTUBE_SHORTS && sessionVideoNumber == 0) {
            sessionVideoNumber = 1
            persistSession(force = true)
        }
        if (currentMode == ScreenMode.YOUTUBE_SHORTS) primeFingerprint("YT", youtubeFingerprint)
        updateBubble()
    }

    private fun handleYouTubePagerScroll(event: AccessibilityEvent) {
        if (interventionShowing || currentTargetPackage != YOUTUBE) return

        val now = System.currentTimeMillis()
        val sourceLooksLikeComments = isYouTubeLikelyCommentsScrollSource(event.source)
        if (sourceLooksLikeComments) {
            youtubeCommentGuardUntil = now + YOUTUBE_COMMENT_GUARD_MS
        }

        val surface = scanYouTubeSurface()
        val exactPagerSource = isYouTubePagerSource(event.source, surface.viewerOpen)
        if (surface.commentsOpen || sourceLooksLikeComments || now < youtubeCommentGuardUntil) {
            cancelYouTubePendingCount("comment panel/scroll")
            setMode(ScreenMode.YOUTUBE_COMMENTS, "YT comments open/guarded")
            diagScrollRejected(event, "YT comment panel/scroll")
            hideBubble()
            return
        }

        // A stale Shorts fragment can remain in YouTube after leaving Shorts.
        // Require the current tree (or the event source itself) to prove that the
        // fullscreen Shorts player is actually on screen.
        if (!surface.viewerOpen && !exactPagerSource) {
            diagScrollRejected(event, "YT rejected: current screen is not Shorts")
            setMode(ScreenMode.OTHER, "YT non-Shorts scroll")
            hideBubble()
            requestSurfaceRefresh(YOUTUBE, 0L)
            return
        }

        if (!youtubePager.active) {
            youtubePager.active = true
            youtubePager.page = -1
            youtubePager.armedAt = now
            diag("YT PAGER armed source=$exactPagerSource tree=${surface.viewerOpen}")
        }
        youtubePager.lastSeenAt = now
        setMode(ScreenMode.YOUTUBE_SHORTS, "YT verified short-video gesture")
        if (sessionVideoNumber == 0) sessionVideoNumber = 1

        val from = event.fromIndex
        val to = event.toIndex
        val semanticIndex = extractCollectionIndex(event.source)
        val cur = when {
            to >= 0 -> to
            event.currentItemIndex >= 0 -> event.currentItemIndex
            semanticIndex >= 0 -> semanticIndex
            from >= 0 -> from
            else -> -1
        }
        val dy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        val dir = directionHint(from, to, dy)
        diagPagerScroll("YT", event, cur, dy)

        // Critical V16 change: a gesture is NOT a count. YouTube emits scroll
        // events while a Short is only being dragged and may then snap back.
        // Count only if the final visible video's stable identity is different
        // after the gesture has fully settled.
        scheduleStableGestureFingerprint(
            platform = "YT",
            state = youtubeFingerprint,
            mode = ScreenMode.YOUTUBE_SHORTS,
            directionHint = dir
        )
        updateBubble()
    }

    private fun scanYouTubeSurface(): SurfaceState {
        val roots = targetRootsFor(YOUTUBE)
        if (roots.isEmpty()) return SurfaceState(false, false, false)

        val strongIds = listOf(
            "reel_watch_fragment_root",
            "reel_recycler",
            "reel_player",
            "reel_progress_bar",
            "shorts_container",
            "shorts_player",
            "shorts_pager",
            "shorts_recycler"
        )
        val direct = findVisibleNodesBySimpleIds(YOUTUBE, strongIds)
        var directStrongPlayer = direct.any { node ->
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val (w, h) = nodeScreenCoverage(node)
            id.contains("reel_watch_fragment_root") || id.contains("reel_recycler") ||
                id.contains("shorts_container") ||
                ((w >= 0.48f && h >= 0.42f) && isYouTubeShortsPlayerId(id))
        }

        var comments = false
        var shortsSelected = false
        var nonShortsTabSelected = false
        var secondaryScrollablePanel = false
        var largeScrollableCandidate = false
        var largePlayerCandidate = false
        var actionSignals = 0
        val seenActionKinds = HashSet<String>()

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 2200) {
                val node = queue.removeFirst()
                visited++
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                val combined = nodeStateText(node)
                val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
                val (w, h) = nodeScreenCoverage(node)

                if (isYouTubeCommentNode(id, combined)) comments = true

                if (isSemanticallySelected(node, "shorts") ||
                    (combined == "shorts selected") || combined.contains("shorts, selected")) {
                    shortsSelected = true
                }
                val selectedNav = node.isSelected || node.isChecked ||
                    combined.contains("selected") || combined.contains("current")
                if (selectedNav && (
                        combined == "home" || combined.startsWith("home,") ||
                        combined == "subscriptions" || combined.startsWith("subscriptions,") ||
                        combined == "you" || combined.startsWith("you,") ||
                        combined == "library" || combined.startsWith("library,"))) {
                    nonShortsTabSelected = true
                }

                if (isYouTubeShortsPlayerId(id) && node.isVisibleToUser) {
                    if (id.contains("reel_watch_fragment_root") || id.contains("reel_recycler") ||
                        id.contains("shorts_container") || (w >= 0.48f && h >= 0.42f)) {
                        directStrongPlayer = true
                    }
                }

                val containerLike = node.isScrollable || cls.contains("recyclerview") || cls.contains("viewpager") ||
                    id.contains("recycler") || id.contains("pager")
                if (!comments && w >= 0.70f && h >= 0.60f && containerLike) largeScrollableCandidate = true

                val rect = Rect()
                node.getBoundsInScreen(rect)
                val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
                if (containerLike && w >= 0.72f &&
                    rect.top >= (screenH * 0.16f).toInt() && h in 0.30f..0.88f) {
                    secondaryScrollablePanel = true
                }

                val playerLike = id.contains("player") || id.contains("video") || id.contains("surface") ||
                    id.contains("reel") || id.contains("short") || cls.contains("surfaceview") || cls.contains("textureview")
                if (!comments && w >= 0.68f && h >= 0.54f && playerLike) largePlayerCandidate = true

                fun action(kind: String, vararg needles: String) {
                    if (needles.any { combined.contains(it) || id.contains(it) }) seenActionKinds.add(kind)
                }
                action("like", "like")
                action("dislike", "dislike")
                action("comment", "comment")
                action("share", "share")
                action("remix", "remix")
                action("subscribe", "subscribe")

                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        actionSignals = seenActionKinds.size
        if (!comments && directStrongPlayer && secondaryScrollablePanel) {
            comments = true
        }

        // Important: simply opening YouTube must never start watch time. A Home
        // Shorts shelf can contain the word "Shorts", but it does not expose the
        // full-screen player + action stack. Require a real player signature.
        val viewer = !comments && !nonShortsTabSelected && (
            directStrongPlayer ||
                (shortsSelected && actionSignals >= 3 && (largeScrollableCandidate || largePlayerCandidate)) ||
                (actionSignals >= 4 && largeScrollableCandidate && largePlayerCandidate)
            )
        return SurfaceState(viewer, comments, shortsSelected)
    }

    private fun isYouTubeShortsPlayerId(id: String): Boolean {
        if (id.isBlank()) return false
        return id.contains("reel_watch_fragment_root") ||
            id.contains("reel_recycler") ||
            id.contains("reel_player") ||
            id.contains("reel_progress_bar") ||
            id.contains("shorts_container") ||
            id.contains("shorts_player") ||
            id.contains("shorts_pager") ||
            id.contains("shorts_recycler")
    }

    private fun isYouTubeCommentNode(id: String, combinedText: String = ""): Boolean {
        return (id.contains("comment") &&
            (id.contains("sheet") || id.contains("panel") || id.contains("list") ||
                id.contains("composer") || id.contains("recycler"))) ||
            id.contains("comments_container") ||
            id.contains("bottom_sheet") ||
            combinedText.contains("add a comment") ||
            combinedText.contains("write a comment") ||
            combinedText.contains("view replies")
    }

    private fun isLargeScrollableVideoContainer(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val containerLike = node.isScrollable ||
            cls.contains("recyclerview") ||
            cls.contains("viewpager") ||
            cls.contains("viewpager2") ||
            id.contains("recycler") ||
            id.contains("pager")
        if (!containerLike) return false

        val nodeRect = Rect()
        val rootRect = Rect()
        node.getBoundsInScreen(nodeRect)
        root.getBoundsInScreen(rootRect)
        if (rootRect.width() <= 0 || rootRect.height() <= 0) return false
        val widthRatio = nodeRect.width().toFloat() / rootRect.width().toFloat()
        val heightRatio = nodeRect.height().toFloat() / rootRect.height().toFloat()
        return widthRatio >= 0.68f && heightRatio >= 0.58f
    }

    private fun youtubeNavigationDestination(source: AccessibilityNodeInfo?): String? {
        var node = source
        var depth = 0
        while (node != null && depth < 8) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            fun hit(name: String): Boolean = label == name || label.startsWith("$name,") ||
                label.contains("$name tab") || id.contains("${name}_tab")
            when {
                hit("shorts") -> return "shorts"
                hit("home") -> return "home"
                hit("subscriptions") -> return "subscriptions"
                hit("you") || hit("library") || hit("profile") -> return "you"
                hit("search") -> return "search"
            }
            node = node.parent
            depth++
        }
        return null
    }

    private fun isYouTubeCommentInteraction(source: AccessibilityNodeInfo?): Boolean {
        var node = source
        var depth = 0
        while (node != null && depth < 8) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            if (id.contains("comment") || label == "comments" ||
                label.startsWith("comments,") || label.startsWith("comment ") ||
                label.contains("view comments") || label.contains("add a comment") ||
                label.contains("view replies") || label.contains("reply")) {
                return true
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun isYouTubeLikelyCommentsScrollSource(source: AccessibilityNodeInfo?): Boolean {
        if (isYouTubeCommentSource(source)) return true

        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        var node = source
        var depth = 0
        while (node != null && depth < 10) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            if (isYouTubeCommentNode(id, label)) return true

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val widthRatio = rect.width().toFloat() / screenW
            val heightRatio = rect.height().toFloat() / screenH
            val panelScrollable = node.isScrollable || cls.contains("recyclerview") ||
                cls.contains("listview") || cls.contains("scrollview") || id.contains("recycler")

            // Shorts itself is essentially full-screen. The comments/replies
            // sheet is a secondary scrollable panel that starts noticeably lower
            // on the screen. This geometry guard catches current YouTube builds
            // even when the sheet's resource IDs are obfuscated.
            if (panelScrollable && widthRatio >= 0.72f &&
                rect.top >= (screenH * 0.16f).toInt() &&
                heightRatio in 0.30f..0.88f) {
                return true
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun isYouTubeFullScreenPagerContainer(node: AccessibilityNodeInfo): Boolean {
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val widthRatio = rect.width().toFloat() / screenW
        val heightRatio = rect.height().toFloat() / screenH
        val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val containerLike = node.isScrollable || cls.contains("recyclerview") ||
            cls.contains("viewpager") || cls.contains("viewpager2") ||
            id.contains("recycler") || id.contains("pager")
        return containerLike && widthRatio >= 0.72f && heightRatio >= 0.72f &&
            rect.top <= (screenH * 0.14f).toInt() &&
            rect.bottom >= (screenH * 0.82f).toInt()
    }

    private fun isYouTubeCommentSource(source: AccessibilityNodeInfo?): Boolean {
        var node = source
        var depth = 0
        while (node != null && depth < 12) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (isYouTubeCommentNode(id, "$text $desc")) return true
            node = node.parent
            depth++
        }
        return false
    }

    private fun isYouTubePagerSource(source: AccessibilityNodeInfo?, surfaceVerified: Boolean): Boolean {
        var node = source
        var depth = 0
        while (node != null && depth < 12) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (isYouTubeCommentNode(id, "$text $desc")) return false
            if (isYouTubeShortsPlayerId(id)) return true
            if (isYouTubeFullScreenPagerContainer(node) && surfaceVerified) return true
            node = node.parent
            depth++
        }
        return false
    }

    private fun isLikelyFullScreenPageEvent(event: AccessibilityEvent): Boolean {
        if (event.fromIndex >= 0 || event.toIndex >= 0 || event.currentItemIndex >= 0) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && event.scrollDeltaY != 0) return true
        val root = rootInActiveWindow ?: return false
        var node = event.source
        var depth = 0
        while (node != null && depth < 10) {
            if (isLargeScrollableVideoContainer(node, root)) return true
            node = node.parent
            depth++
        }
        return false
    }

    // ---------------------------------------------------------------------
    // TikTok detector — full-screen pager / ViewPager2 model
    // ---------------------------------------------------------------------

    private fun handleTikTokEvent(event: AccessibilityEvent, packageName: String) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshTargetSurfaceWithRetries(packageName)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                requestSurfaceRefresh(packageName, 140L)
                // TikTok produces a constant stream of content changes while one
                // video is playing. Content changes alone are never a count.
                // After a real scroll gesture, however, they are useful as a cue
                // to run the same stable-final-video confirmation used below.
                if ((currentMode == ScreenMode.TIKTOK_FEED || tiktokPager.active) &&
                    System.currentTimeMillis() <= tiktokGestureCandidateUntil) {
                    scheduleStableGestureFingerprint(
                        platform = "TT",
                        state = tiktokFingerprint,
                        mode = ScreenMode.TIKTOK_FEED,
                        directionHint = 0
                    )
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val destination = tiktokNavigationDestination(event.source)
                if (destination != null && destination !in setOf("for you", "following", "friends", "home")) {
                    tiktokNonVideoNavSuppressUntil = System.currentTimeMillis() + NON_VIDEO_NAV_SUPPRESS_MS
                    forceLeaveShortSurface("TT", "navigation=$destination")
                    refreshTargetSurfaceWithRetries(packageName)
                } else if (destination != null) {
                    tiktokNonVideoNavSuppressUntil = 0L
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleTikTokPagerScroll(event, packageName)
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                val destination = tiktokNavigationDestination(event.source)
                if (destination != null && destination !in setOf("for you", "following", "friends", "home")) {
                    tiktokNonVideoNavSuppressUntil = System.currentTimeMillis() + NON_VIDEO_NAV_SUPPRESS_MS
                    forceLeaveShortSurface("TT", "selected navigation=$destination")
                    refreshTargetSurfaceWithRetries(packageName)
                } else {
                    if (destination != null) tiktokNonVideoNavSuppressUntil = 0L
                    handleTikTokPagerScroll(event, packageName)
                }
            }
        }
    }

    private fun refreshTikTokSurface(packageName: String) {
        if (currentTargetPackage != packageName) return
        val now = System.currentTimeMillis()
        if (now < tiktokNonVideoNavSuppressUntil) {
            resetPagerState(tiktokPager)
            tiktokGestureCandidateUntil = 0L
            setMode(ScreenMode.OTHER, "TT non-video navigation suppression")
            hideBubble()
            return
        }

        val state = scanTikTokSurface()

        if (state.viewerOpen) {
            tiktokPager.lastSeenAt = now
            if (!tiktokPager.active) {
                tiktokPager.active = true
                tiktokPager.page = -1
                tiktokPager.armedAt = now
                tiktokPager.lastIndexEventAt = 0L
                cancelPagerFallback(tiktokPager)
                diag("TT PAGER/feed armed")
            }
        } else if (tiktokPager.active && now - tiktokPager.lastSeenAt > 260L) {
            resetPagerState(tiktokPager)
            tiktokGestureCandidateUntil = 0L
            diag("TT PAGER/feed disarmed after verified absence")
        }

        val newMode = when {
            state.commentsOpen -> ScreenMode.TIKTOK_COMMENTS
            state.viewerOpen -> ScreenMode.TIKTOK_FEED
            else -> ScreenMode.OTHER
        }
        setMode(newMode, "TT visibleViewer=${state.viewerOpen} comments=${state.commentsOpen}")

        if (currentMode != ScreenMode.TIKTOK_FEED) {
            tiktokPollCandidate = 0
            tiktokPollCandidateSince = 0L
        }

        if (currentMode == ScreenMode.TIKTOK_FEED && sessionVideoNumber == 0) {
            sessionVideoNumber = 1
            persistSession(force = true)
        }
        if (currentMode == ScreenMode.TIKTOK_FEED) primeFingerprint("TT", tiktokFingerprint)
        updateBubble()
    }

    private fun handleTikTokPagerScroll(event: AccessibilityEvent, packageName: String) {
        if (interventionShowing || currentTargetPackage != packageName) return

        val sourceIsComments = isTikTokCommentSource(event.source)
        val exactPagerSource = isTikTokPagerSource(event.source)
        val transitionSource = isTikTokTransitionSource(event.source)
        val scanned = scanTikTokSurface()

        if (scanned.commentsOpen || sourceIsComments) {
            setMode(ScreenMode.TIKTOK_COMMENTS, "TT comments open")
            hideBubble()
            return
        }

        // TikTok resource ids vary by region/build. If the tree misses the feed
        // but the actual scroll source is a near-full-screen vertical video
        // container, that is strong enough to arm the feed for this gesture.
        val viewerVerified = scanned.viewerOpen || exactPagerSource || transitionSource
        if (!viewerVerified) {
            diagScrollRejected(event, "TT rejected: no full-screen feed evidence")
            setMode(ScreenMode.OTHER, "TT non-feed scroll")
            hideBubble()
            requestSurfaceRefresh(packageName, 0L)
            return
        }

        val now = System.currentTimeMillis()
        if (!tiktokPager.active) {
            tiktokPager.active = true
            tiktokPager.page = -1
            tiktokPager.armedAt = now
            diag("TT PAGER/feed armed sourcePager=$exactPagerSource sourceFeed=$transitionSource tree=${scanned.viewerOpen}")
        }
        tiktokPager.lastSeenAt = now
        setMode(ScreenMode.TIKTOK_FEED, "TT verified feed gesture")
        if (sessionVideoNumber == 0) sessionVideoNumber = 1

        val from = event.fromIndex
        val to = event.toIndex
        val semanticIndex = extractCollectionIndex(event.source)
        val cur = when {
            to >= 0 -> to
            event.currentItemIndex >= 0 -> event.currentItemIndex
            semanticIndex >= 0 -> semanticIndex
            from >= 0 -> from
            else -> -1
        }
        val dy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        val dir = directionHint(from, to, dy)
        diagPagerScroll("TT", event, cur, dy)

        // A TikTok drag is only a candidate. The count happens after the final
        // visible creator/caption/sound identity is stable and actually changed.
        tiktokGestureCandidateUntil = now + TIKTOK_GESTURE_CONFIRM_WINDOW_MS
        scheduleStableGestureFingerprint(
            platform = "TT",
            state = tiktokFingerprint,
            mode = ScreenMode.TIKTOK_FEED,
            directionHint = dir
        )
        updateBubble()
    }

    private fun tiktokNavigationDestination(source: AccessibilityNodeInfo?): String? {
        var node = source
        var depth = 0
        while (node != null && depth < 8) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            fun hit(name: String): Boolean = label == name || label.startsWith("$name,") ||
                label.contains("$name tab") || id.contains(name.replace(" ", "_"))
            when {
                hit("for you") -> return "for you"
                hit("following") -> return "following"
                hit("friends") -> return "friends"
                hit("home") -> return "home"
                hit("profile") -> return "profile"
                hit("inbox") -> return "inbox"
                hit("search") -> return "search"
                hit("shop") -> return "shop"
            }
            node = node.parent
            depth++
        }
        return null
    }

    private fun isTikTokTransitionSource(source: AccessibilityNodeInfo?): Boolean {
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        var node = source
        var depth = 0
        while (node != null && depth < 8) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (id.contains("comment") || id.contains("reply") ||
                id.contains("bottom_sheet") || id.contains("bottomsheet")) return false
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val w = rect.width().toFloat() / screenW
            val h = rect.height().toFloat() / screenH
            val feedLike = node.isScrollable || cls.contains("viewpager") ||
                cls.contains("recyclerview") || id.contains("pager") ||
                id.contains("aweme") || id.contains("feed") || id.contains("video")
            if (feedLike && w >= 0.72f && h >= 0.68f &&
                rect.top <= (screenH * 0.18f).toInt()) return true
            node = node.parent
            depth++
        }
        return false
    }

    private fun recordTikTokContentBurst() {
        val now = System.currentTimeMillis()
        if (now > tiktokGestureCandidateUntil) return
        if (now - tiktokContentBurstLastAt > TIKTOK_CONTENT_BURST_GAP_MS) {
            tiktokContentBurstStartedAt = now
            tiktokContentBurstEvents = 1
        } else {
            tiktokContentBurstEvents++
        }
        tiktokContentBurstLastAt = now
        val token = ++tiktokContentBurstToken
        handler.postDelayed({
            if (token != tiktokContentBurstToken ||
                currentMode != ScreenMode.TIKTOK_FEED || interventionShowing ||
                System.currentTimeMillis() > tiktokGestureCandidateUntil) return@postDelayed

            val events = tiktokContentBurstEvents
            tiktokContentBurstEvents = 0
            if (events < TIKTOK_CONTENT_MIN_EVENTS) {
                diag("TT content burst ignored events=$events")
                return@postDelayed
            }

            // If the fingerprint is available and unchanged, the burst was
            // probably only player chrome/progress updating. If metadata changed
            // (or TikTok exposes no usable identity at all), one burst represents
            // one snapped feed transition.
            val fp = buildPlatformVideoFingerprint("TT")
            if (fp != 0 && tiktokFingerprint.current != 0 && fp == tiktokFingerprint.current) {
                diag("TT content burst same fingerprint; no-count")
                return@postDelayed
            }
            if (fp != 0) {
                tiktokFingerprint.current = fp
                rememberFingerprint(tiktokFingerprint, fp)
            }
            if (System.currentTimeMillis() - lastTikTokNavigationSignalAt < 700L) {
                diag("TT content burst deduped against navigation")
                return@postDelayed
            }
            acceptForwardTransition("TT", ScreenMode.TIKTOK_FEED, "content-burst")
            lastTikTokNavigationSignalAt = System.currentTimeMillis()
            tiktokGestureCandidateUntil = 0L
            updateBubble()
        }, TIKTOK_CONTENT_SETTLE_MS)
    }

    private fun scanTikTokSurface(): SurfaceState {
        val packageName = currentTargetPackage?.takeIf { isTikTokPackage(it) } ?: TIKTOK
        val roots = targetRootsFor(packageName)
        if (roots.isEmpty()) return SurfaceState(false, false, false)

        val directFeedNodes = findVisibleNodesBySimpleIds(
            packageName,
            listOf("feed_view_pager", "main_feed_view_pager", "aweme_feed_view_pager")
        )
        val strongFeedPager = directFeedNodes.any { node ->
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val (w, h) = nodeScreenCoverage(node)
            id.contains("feed_view_pager") && w >= 0.65f && h >= 0.62f
        }

        var comments = false
        var feedTabSignals = 0
        var largeVerticalContainer = strongFeedPager
        var largeMediaContainer = false
        var fullscreenMediaCandidate = false
        val actions = HashSet<String>()
        var bottomHomeSeen = false
        var searchOrProfileScreen = false
        var explicitNonFeedNav = false

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 2200) {
                val node = queue.removeFirst()
                visited++
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
                val combined = nodeStateText(node)
                val (w, h) = nodeScreenCoverage(node)

                val commentContainerId = id.contains("comment") && (
                    id.contains("sheet") || id.contains("panel") || id.contains("list") ||
                        id.contains("recycler") || id.contains("composer") || id.contains("thread")
                    )
                val replyContainerId = id.contains("reply") && (
                    id.contains("sheet") || id.contains("panel") || id.contains("list") ||
                        id.contains("recycler") || id.contains("thread")
                    )
                if (commentContainerId || replyContainerId ||
                    id.contains("bottom_sheet") || id.contains("bottomsheet") ||
                    combined.contains("add comment") || combined.contains("add a comment") ||
                    combined.contains("view replies") || combined.contains("reply to")) comments = true

                if (combined == "for you" || combined.startsWith("for you,") ||
                    combined == "following" || combined.startsWith("following,") ||
                    combined == "friends" || combined.startsWith("friends,")) {
                    feedTabSignals++
                }
                if (combined.contains("for you") && (node.isSelected || combined.contains("selected"))) feedTabSignals++
                if (combined == "home" || combined.startsWith("home,")) bottomHomeSeen = true

                // Search/Profile/Inbox screens can have lots of media thumbnails;
                // don't call them the swipe feed unless feed semantics are present.
                if ((combined == "search" || combined == "profile" || combined == "inbox" ||
                        combined == "shop") &&
                    (node.isSelected || node.isChecked || combined.contains("selected") || combined.contains("current"))) {
                    searchOrProfileScreen = true
                    explicitNonFeedNav = true
                }

                if (combined.contains("edit profile") ||
                    combined.contains("manage account") ||
                    combined.contains("profile views") ||
                    combined.contains("settings and privacy")) {
                    searchOrProfileScreen = true
                }

                fun action(kind: String, vararg needles: String) {
                    if (needles.any { combined.contains(it) || id.contains(it) }) actions.add(kind)
                }
                action("like", "like")
                action("comment", "comment")
                action("share", "share")
                action("sound", "sound", "music")
                action("follow", "follow")

                val pagerNamed = id.contains("viewpager") || id.contains("view_pager") || id.contains("pager") ||
                    id.contains("aweme") || id.contains("feed_view") || id.contains("video_view") || id.contains("player_view")
                val pagerClass = cls.contains("viewpager2") || cls.contains("viewpager")
                if (!comments && w >= 0.65f && h >= 0.58f &&
                    (node.isScrollable || pagerNamed || pagerClass || cls.contains("recyclerview"))) {
                    largeVerticalContainer = true
                }
                if (!comments && w >= 0.60f && h >= 0.52f &&
                    (id.contains("video") || id.contains("player") || id.contains("aweme") || id.contains("feed") ||
                        cls.contains("surfaceview") || cls.contains("textureview"))) {
                    largeMediaContainer = true
                }
                if (!comments && node.isVisibleToUser && w >= 0.78f && h >= 0.72f &&
                    (cls.contains("surfaceview") || cls.contains("textureview") ||
                        id.contains("video") || id.contains("player") || id.contains("aweme"))) {
                    fullscreenMediaCandidate = true
                }

                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }

        val semanticFeed = strongFeedPager || feedTabSignals > 0 ||
            (bottomHomeSeen && (actions.size >= 2 || largeVerticalContainer || largeMediaContainer))

        // TikTok's IDs are frequently obfuscated. A full-screen media layer plus
        // a full-height vertical container is a strong feed signature even when
        // "For You" or a named pager id is absent from Accessibility.
        val structuralFeed = fullscreenMediaCandidate &&
            (largeVerticalContainer || actions.size >= 2 || bottomHomeSeen)

        val viewer = !comments && !searchOrProfileScreen && !explicitNonFeedNav && (
            strongFeedPager ||
                structuralFeed ||
                (actions.size >= 3 && (largeVerticalContainer || largeMediaContainer || fullscreenMediaCandidate)) ||
                (semanticFeed && (actions.size >= 2 || largeVerticalContainer || largeMediaContainer))
            )
        return SurfaceState(viewer, comments, semanticFeed || structuralFeed)
    }

    private fun isTikTokCommentSource(source: AccessibilityNodeInfo?): Boolean {
        var node = source
        var depth = 0
        while (node != null && depth < 10) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            val commentContainer = id.contains("comment") && (
                id.contains("sheet") || id.contains("panel") || id.contains("list") ||
                    id.contains("recycler") || id.contains("thread") || id.contains("composer")
                )
            if (commentContainer || id.contains("bottom_sheet") || id.contains("bottomsheet") ||
                label.contains("add a comment") || label.contains("view replies")) return true
            node = node.parent
            depth++
        }
        return false
    }

    private fun isTikTokPagerSource(source: AccessibilityNodeInfo?): Boolean {
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        var node = source
        var depth = 0
        while (node != null && depth < 10) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (id.contains("comment") || id.contains("reply") || id.contains("bottom_sheet") || id.contains("bottomsheet")) {
                return false
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val widthRatio = rect.width().toFloat() / screenW
            val heightRatio = rect.height().toFloat() / screenH
            val pagerNamed = id.contains("viewpager") || id.contains("view_pager") ||
                id.contains("pager") || id.contains("aweme") || id.contains("feed_view") ||
                id.contains("video_view") || id.contains("player_view")
            val pagerClass = cls.contains("viewpager2") || cls.contains("viewpager")
            if (widthRatio >= 0.70f && heightRatio >= 0.68f && (pagerNamed || pagerClass)) return true
            node = node.parent
            depth++
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Facebook Reels detector — conservative full-screen Reel surface model
    // ---------------------------------------------------------------------

    private fun handleFacebookEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> requestSurfaceRefresh(FACEBOOK, 120L)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val destination = facebookNavigationDestination(event.source)
                if (destination != null && destination != "reels") {
                    facebookNonVideoNavSuppressUntil = System.currentTimeMillis() + NON_VIDEO_NAV_SUPPRESS_MS
                    forceLeaveShortSurface("FB", "navigation=$destination")
                    refreshTargetSurfaceWithRetries(FACEBOOK)
                } else if (destination == "reels") {
                    facebookNonVideoNavSuppressUntil = 0L
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleFacebookPagerScroll(event)
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                val destination = facebookNavigationDestination(event.source)
                if (destination != null && destination != "reels") {
                    facebookNonVideoNavSuppressUntil = System.currentTimeMillis() + NON_VIDEO_NAV_SUPPRESS_MS
                    forceLeaveShortSurface("FB", "selected navigation=$destination")
                    refreshTargetSurfaceWithRetries(FACEBOOK)
                } else {
                    if (destination == "reels") facebookNonVideoNavSuppressUntil = 0L
                    handleFacebookPagerScroll(event)
                }
            }
        }
    }

    private fun refreshFacebookSurface() {
        if (currentTargetPackage != FACEBOOK) return
        val now = System.currentTimeMillis()
        if (now < facebookNonVideoNavSuppressUntil) {
            resetPagerState(facebookPager)
            setMode(ScreenMode.OTHER, "FB non-video navigation suppression")
            hideBubble()
            return
        }

        val state = scanFacebookSurface()
        if (state.viewerOpen) {
            facebookPager.lastSeenAt = now
            if (!facebookPager.active) {
                facebookPager.active = true
                facebookPager.page = -1
                facebookPager.armedAt = now
                facebookPager.lastIndexEventAt = 0L
                cancelPagerFallback(facebookPager)
                diag("FB PAGER armed")
            }
        } else if (facebookPager.active && now - facebookPager.lastSeenAt > 220L) {
            resetPagerState(facebookPager)
            diag("FB PAGER hidden/disarmed after verified viewer absence")
        }

        val mode = when {
            state.commentsOpen -> ScreenMode.FACEBOOK_COMMENTS
            state.viewerOpen -> ScreenMode.FACEBOOK_REELS
            else -> ScreenMode.OTHER
        }
        setMode(mode, "FB visibleViewer=${state.viewerOpen} comments=${state.commentsOpen}")
        if (currentMode == ScreenMode.FACEBOOK_REELS && sessionVideoNumber == 0) {
            sessionVideoNumber = 1
            persistSession(force = true)
        }
        if (currentMode == ScreenMode.FACEBOOK_REELS) primeFingerprint("FB", facebookFingerprint)
        updateBubble()
    }

    private fun handleFacebookPagerScroll(event: AccessibilityEvent) {
        if (interventionShowing || currentTargetPackage != FACEBOOK) return

        val sourceIsComments = isFacebookCommentSource(event.source)
        val surface = scanFacebookSurface()
        val exactPagerSource = isFacebookPagerSource(event.source)

        if (surface.commentsOpen || sourceIsComments) {
            setMode(ScreenMode.FACEBOOK_COMMENTS, "FB comments open")
            hideBubble()
            return
        }

        // Never let a stale "Reels was active" flag turn Profile/Home scrolling
        // into Reel navigation. The current screen must still prove the Reels
        // viewer, or the event source itself must belong to that viewer.
        if (!surface.viewerOpen && !exactPagerSource) {
            diagScrollRejected(event, "FB rejected: current screen is not Reels")
            setMode(ScreenMode.OTHER, "FB non-Reels scroll")
            hideBubble()
            requestSurfaceRefresh(FACEBOOK, 0L)
            return
        }

        val now = System.currentTimeMillis()
        if (!facebookPager.active) {
            facebookPager.active = true
            facebookPager.page = -1
            facebookPager.armedAt = now
            diag("FB PAGER armed source=$exactPagerSource tree=${surface.viewerOpen}")
        }
        facebookPager.lastSeenAt = now
        setMode(ScreenMode.FACEBOOK_REELS, "FB verified short-video gesture")
        if (sessionVideoNumber == 0) sessionVideoNumber = 1

        val from = event.fromIndex
        val to = event.toIndex
        val semanticIndex = extractCollectionIndex(event.source)
        val cur = when {
            to >= 0 -> to
            event.currentItemIndex >= 0 -> event.currentItemIndex
            semanticIndex >= 0 -> semanticIndex
            from >= 0 -> from
            else -> -1
        }
        val dy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        val dir = directionHint(from, to, dy)
        diagPagerScroll("FB", event, cur, dy)

        // Facebook reports drag movement before the Reel has snapped to a final
        // page. Like YouTube, V16 counts only a stable final video identity.
        scheduleStableGestureFingerprint(
            platform = "FB",
            state = facebookFingerprint,
            mode = ScreenMode.FACEBOOK_REELS,
            directionHint = dir
        )
        updateBubble()
    }

    private fun facebookNavigationDestination(source: AccessibilityNodeInfo?): String? {
        var node = source
        var depth = 0
        while (node != null && depth < 8) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            fun hit(name: String): Boolean = label == name || label.startsWith("$name,") ||
                label.contains("$name tab") || id.contains("${name}_tab") || id.endsWith("/$name")
            when {
                hit("reels") || hit("reel") -> return "reels"
                hit("home") -> return "home"
                hit("profile") -> return "profile"
                hit("friends") -> return "friends"
                hit("notifications") -> return "notifications"
                hit("menu") -> return "menu"
                hit("marketplace") -> return "marketplace"
                hit("watch") || hit("video") -> return "watch"
            }
            node = node.parent
            depth++
        }
        return null
    }

    private fun scanFacebookSurface(): SurfaceState {
        val roots = targetRootsFor(FACEBOOK)
        if (roots.isEmpty()) return SurfaceState(false, false, false)
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        var viewer = false
        var comments = false
        var reelsLabelSeen = false
        var largePager = false
        var largeMediaContainer = false
        var shortControlSignals = 0
        var profileScreenEvidence = 0
        var nonReelsNavSelected = false

        for (root in roots) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 1000) {
                val node = stack.removeLast()
                visited++
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
                val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val combined = "$text $desc"
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val widthRatio = rect.width().toFloat() / screenW
                val heightRatio = rect.height().toFloat() / screenH

                if ((id.contains("comment") || id.contains("reply") || id.contains("bottom_sheet") || id.contains("bottomsheet")) ||
                    combined.contains("write a comment") || combined.contains("add a comment") || combined.contains("view replies")
                ) comments = true

                val normalized = combined.trim()
                if ((normalized == "home" || normalized.startsWith("home,") ||
                        normalized == "profile" || normalized.startsWith("profile,") ||
                        normalized == "friends" || normalized.startsWith("friends,") ||
                        normalized == "notifications" || normalized.startsWith("notifications,") ||
                        normalized == "menu" || normalized.startsWith("menu,")) &&
                    (node.isSelected || node.isChecked || normalized.contains("selected") || normalized.contains("current"))) {
                    nonReelsNavSelected = true
                }

                if (combined.contains("edit profile") ||
                    combined.contains("view profile") ||
                    combined.contains("profile settings") ||
                    combined.contains("manage posts") ||
                    id.contains("profile_header") ||
                    id.contains("profile_tab_content")) {
                    profileScreenEvidence++
                }

                if (combined.trim() == "reels" || combined.trim().startsWith("reels,")) reelsLabelSeen = true
                val strongReelId = id.contains("reel") || id.contains("reels") || id.contains("short_form_video")
                val pagerClass = cls.contains("viewpager") || cls.contains("recyclerview")
                val pagerId = id.contains("pager") || id.contains("recycler") || id.contains("video_feed") || id.contains("video_player")
                if (!comments && widthRatio >= 0.68f && heightRatio >= 0.62f && (pagerClass || pagerId || node.isScrollable)) largePager = true
                if (!comments && widthRatio >= 0.60f && heightRatio >= 0.55f && strongReelId) viewer = true
                if (!comments && widthRatio >= 0.60f && heightRatio >= 0.52f &&
                    (id.contains("video") || id.contains("player") || id.contains("media") || strongReelId)) largeMediaContainer = true
                if (isShortVideoControlLabel(combined, id, platform = "FB")) shortControlSignals++

                for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
            }
        }
        if (!viewer && !comments && reelsLabelSeen && largePager) viewer = true
        if (!viewer && !comments && (largePager || largeMediaContainer) && shortControlSignals >= 3) viewer = true

        // Facebook can leave a Reel/player subtree mounted while the user's own
        // Profile is visible. Visible profile controls/nav selection always win.
        if (nonReelsNavSelected || profileScreenEvidence > 0) viewer = false

        return SurfaceState(viewer, comments, reelsLabelSeen)
    }

    private fun isFacebookCommentSource(source: AccessibilityNodeInfo?): Boolean {
        var node = source
        var depth = 0
        while (node != null && depth < 10) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            val commentContainer = id.contains("comment") && (
                id.contains("sheet") || id.contains("panel") || id.contains("list") ||
                    id.contains("recycler") || id.contains("thread") || id.contains("composer")
                )
            if (commentContainer || id.contains("bottom_sheet") || id.contains("bottomsheet") ||
                label.contains("write a comment") || label.contains("add a comment") ||
                label.contains("view replies")) return true
            node = node.parent
            depth++
        }
        return false
    }

    private fun isFacebookPagerSource(source: AccessibilityNodeInfo?): Boolean {
        var node = source
        var depth = 0
        while (node != null && depth < 12) {
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val label = nodeStateText(node)
            if (id.contains("comment") || id.contains("reply") ||
                id.contains("bottom_sheet") || id.contains("bottomsheet")) return false

            // Do not accept a generic full-screen RecyclerView here. Facebook
            // Profile/Home are also large scrolling lists, which was exactly why
            // profile scrolling could resurrect the Reel counter.
            if (id.contains("reel") || id.contains("reels") ||
                id.contains("short_form_video") || id.contains("reels_feed") ||
                label.contains("reel viewer")) {
                return true
            }
            node = node.parent
            depth++
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Hybrid metadata fingerprint engine
    // ---------------------------------------------------------------------

    private fun resetFingerprintState(state: FingerprintState) {
        state.current = 0
        state.recent.clear()
        state.settleToken++
        state.settlePending = false
        state.pendingDirectionHint = 0
    }

    private fun primeFingerprint(platform: String, state: FingerprintState) {
        if (state.current != 0) return
        val fp = buildPlatformVideoFingerprint(platform)
        if (fp == 0) return
        state.current = fp
        rememberFingerprint(state, fp)
        diag("$platform FP baseline=$fp")
    }

    private fun scheduleFingerprintTransition(
        platform: String,
        state: FingerprintState,
        mode: ScreenMode,
        directionHint: Int
    ) {
        // Do not keep postponing the check on every TYPE_WINDOW_CONTENT_CHANGED.
        // TikTok in particular can emit a continuous stream of content-change
        // events while a video is playing. V12 kept resetting the delay, so the
        // fingerprint comparison could be starved forever and never count.
        if (directionHint != 0) state.pendingDirectionHint = directionHint
        if (state.settlePending) return

        state.settlePending = true
        val generation = state.settleToken
        handler.postDelayed({
            if (generation != state.settleToken) return@postDelayed
            state.settlePending = false
            val effectiveDirection = state.pendingDirectionHint
            state.pendingDirectionHint = 0

            if (interventionShowing || currentMode != mode) return@postDelayed
            val fp = buildPlatformVideoFingerprint(platform)
            if (fp == 0) {
                diag("$platform FP unavailable")
                return@postDelayed
            }
            if (state.current == 0) {
                state.current = fp
                rememberFingerprint(state, fp)
                diag("$platform FP late-baseline=$fp")
                return@postDelayed
            }
            if (fp == state.current) return@postDelayed

            val seenBefore = state.recent.contains(fp)
            val previous = state.current
            state.current = fp
            rememberFingerprint(state, fp)

            val recentNavigationAt = when (platform) {
                "IG" -> lastInstagramNavigationSignalAt
                "YT" -> lastYouTubeNavigationSignalAt
                "TT" -> lastTikTokNavigationSignalAt
                else -> 0L
            }
            val recentlyHandledByNavigation = recentNavigationAt > 0L &&
                System.currentTimeMillis() - recentNavigationAt < 700L

            when {
                effectiveDirection < 0 -> diag("$platform FP backward $previous->$fp no-count")
                seenBefore -> diag("$platform FP returned-to-recent $previous->$fp no-count")
                recentlyHandledByNavigation -> diag("$platform FP deduped against recent navigation signal")
                else -> {
                    acceptForwardTransition(platform, mode, "metadata-fingerprint")
                    if (platform == "TT") {
                        lastTikTokNavigationSignalAt = System.currentTimeMillis()
                        tiktokGestureCandidateUntil = 0L
                    }
                }
            }
            updateBubble()
        }, FINGERPRINT_SETTLE_MS)
    }

    /**
     * Confirms a swipe only after the UI has fully settled on a different video.
     *
     * YouTube Shorts and Facebook Reels emit TYPE_VIEW_SCROLLED while the finger
     * is still dragging. Counting that event directly makes a half-swipe that
     * snaps back look like a watched video. V16 samples the visible-video
     * fingerprint twice after the gesture. A transition is committed only when
     * both samples agree and differ from the previous resting video.
     */
    private fun scheduleStableGestureFingerprint(
        platform: String,
        state: FingerprintState,
        mode: ScreenMode,
        directionHint: Int
    ) {
        if (directionHint != 0) state.pendingDirectionHint = directionHint
        // Content-change storms must not keep postponing the settle check.
        if (state.settlePending) return

        val token = ++state.settleToken
        state.settlePending = true

        handler.postDelayed({
            if (token != state.settleToken || interventionShowing || currentMode != mode) return@postDelayed

            val first = buildPlatformVideoFingerprint(platform)
            if (first == 0) {
                state.settlePending = false
                diag("$platform STABLE-FP unavailable first sample")
                return@postDelayed
            }

            handler.postDelayed({
                if (token != state.settleToken || interventionShowing || currentMode != mode) return@postDelayed

                val second = buildPlatformVideoFingerprint(platform)
                state.settlePending = false
                val effectiveDirection = state.pendingDirectionHint
                state.pendingDirectionHint = 0

                val surfaceStillValid = when (platform) {
                    "YT" -> {
                        val s = scanYouTubeSurface()
                        s.viewerOpen && !s.commentsOpen && System.currentTimeMillis() >= youtubeCommentGuardUntil
                    }
                    "TT" -> {
                        val s = scanTikTokSurface()
                        s.viewerOpen && !s.commentsOpen
                    }
                    "FB" -> {
                        val s = scanFacebookSurface()
                        s.viewerOpen && !s.commentsOpen
                    }
                    else -> currentMode == mode
                }
                if (!surfaceStillValid || currentMode != mode) {
                    diag("$platform STABLE-FP cancelled: short-video surface no longer visible")
                    return@postDelayed
                }

                if (second == 0 || second != first) {
                    diag("$platform STABLE-FP still moving first=$first second=$second")
                    return@postDelayed
                }

                if (state.current == 0) {
                    state.current = second
                    rememberFingerprint(state, second)
                    diag("$platform STABLE-FP baseline=$second")
                    return@postDelayed
                }

                if (second == state.current) {
                    diag("$platform STABLE-FP same video; gesture snapped back")
                    return@postDelayed
                }

                val previous = state.current
                val seenBefore = state.recent.contains(second)
                state.current = second
                rememberFingerprint(state, second)

                when {
                    effectiveDirection < 0 ->
                        diag("$platform STABLE-FP backward $previous->$second no-count")
                    seenBefore && effectiveDirection == 0 ->
                        diag("$platform STABLE-FP returned-to-recent $previous->$second no-count")
                    else -> {
                        // This is the first point at which we know a different
                        // video is actually resting on screen.
                        commitForwardTransition(platform, mode, "stable-video-change")
                        when (platform) {
                            "YT" -> lastYouTubeNavigationSignalAt = System.currentTimeMillis()
                            "TT" -> {
                                lastTikTokNavigationSignalAt = System.currentTimeMillis()
                                tiktokGestureCandidateUntil = 0L
                            }
                        }
                    }
                }
                updateBubble()
            }, 180L)
        }, 460L)
    }

    private fun rememberFingerprint(state: FingerprintState, fp: Int) {
        state.recent.remove(fp)
        state.recent.addLast(fp)
        while (state.recent.size > 8) state.recent.removeFirst()
    }

    private fun directionHint(from: Int, to: Int, dy: Int): Int {
        return when {
            from >= 0 && to >= 0 && to > from -> 1
            from >= 0 && to >= 0 && to < from -> -1
            dy > 0 -> 1
            dy < 0 -> -1
            else -> 0
        }
    }

    /**
     * Some RecyclerView/ViewPager2 implementations hide their adapter position
     * from AccessibilityEvent but expose it through CollectionItemInfo on the
     * event source or one of its parents.
     */
    private fun extractCollectionIndex(source: AccessibilityNodeInfo?): Int {
        var node = source
        var depth = 0
        while (node != null && depth < 12) {
            val item = node.collectionItemInfo
            if (item != null) {
                val row = item.rowIndex
                val col = item.columnIndex
                if (row >= 0) return row
                if (col >= 0) return col
            }
            node = node.parent
            depth++
        }
        return -1
    }

    /**
     * Build a short-lived hash from visible UI metadata. No captions, usernames,
     * labels or other source strings are persisted or written to diagnostics.
     * The hash exists only in memory and lets us confirm that the visible video
     * actually changed when an app gives us a scroll event without an index.
     */
    private fun buildPlatformVideoFingerprint(platform: String): Int {
        return if (platform == "TT") buildTikTokVideoFingerprint() else buildVisibleVideoFingerprint(platform)
    }

    /**
     * TikTok frequently obfuscates resource ids and may not expose ViewPager2
     * positions. Use a privacy-preserving in-memory identity made from the visible
     * creator/caption/sound metadata. Generic controls and changing counters are
     * filtered out so the identity stays stable while one video is playing.
     */
    private fun buildTikTokVideoFingerprint(): Int {
        val packageName = currentTargetPackage?.takeIf { isTikTokPackage(it) } ?: return 0
        val roots = targetRootsFor(packageName)
        if (roots.isEmpty()) return 0

        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val tokens = LinkedHashSet<String>()
        for (root in roots) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 900 && tokens.size < 18) {
                val node = stack.removeLast()
                visited++
                if (!node.isVisibleToUser) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
                    continue
                }
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
                val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val value = listOf(text, desc).filter { it.isNotBlank() }.joinToString(" ")
                    .replace(Regex("\\s+"), " ").trim()

                val rect = Rect()
                node.getBoundsInScreen(rect)
                val centerY = rect.centerY()
                val inVideoArea = rect.width() > 0 && rect.height() > 0 &&
                    centerY in (screenH * 0.08f).toInt()..(screenH * 0.90f).toInt()

                if (inVideoArea && value.isNotBlank()) {
                    val generic = isPureGenericControl(value) || looksLikeDynamicControlText(value) ||
                        value in setOf("for you", "following", "friends", "home", "shop", "inbox", "profile")
                    val identityId = id.contains("author") || id.contains("user") || id.contains("name") ||
                        id.contains("desc") || id.contains("caption") || id.contains("music") ||
                        id.contains("sound") || id.contains("aweme") || id.contains("title")
                    val textLike = cls.contains("textview") || cls.contains("button")
                    val likelyIdentity = value.startsWith("@") || identityId ||
                        (textLike && value.length in 2..160 && !generic && !value.matches(Regex("^[0-9.,km+b\\s:]+$")))
                    if (likelyIdentity && !generic) {
                        tokens.add("${rect.top / 36}:${value.take(120)}")
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
            }
        }
        if (tokens.isEmpty()) return 0
        return ("TT|" + tokens.sorted().take(14).joinToString("|")).hashCode()
    }

    private fun pollTikTokIdentity() {
        if (currentMode != ScreenMode.TIKTOK_FEED || interventionShowing) return
        val now = System.currentTimeMillis()
        val fp = buildTikTokVideoFingerprint()
        if (fp == 0) return

        if (tiktokFingerprint.current == 0) {
            tiktokFingerprint.current = fp
            rememberFingerprint(tiktokFingerprint, fp)
            tiktokPollCandidate = 0
            tiktokPollCandidateSince = 0L
            diag("TT POLL baseline=$fp")
            return
        }
        if (fp == tiktokFingerprint.current) {
            tiktokPollCandidate = 0
            tiktokPollCandidateSince = 0L
            return
        }

        if (tiktokPollCandidate != fp) {
            tiktokPollCandidate = fp
            tiktokPollCandidateSince = now
            return
        }
        if (now - tiktokPollCandidateSince < TIKTOK_IDENTITY_STABLE_MS) return

        val previous = tiktokFingerprint.current
        val seenBefore = tiktokFingerprint.recent.contains(fp)
        tiktokFingerprint.current = fp
        rememberFingerprint(tiktokFingerprint, fp)
        tiktokPollCandidate = 0
        tiktokPollCandidateSince = 0L

        if (seenBefore) {
            diag("TT POLL returned-to-recent $previous->$fp no-count")
            return
        }
        if (now - lastTikTokNavigationSignalAt < 750L) {
            diag("TT POLL deduped against navigation")
            return
        }
        acceptForwardTransition("TT", ScreenMode.TIKTOK_FEED, "stable-identity-poll")
        lastTikTokNavigationSignalAt = now
        updateBubble()
    }

    private fun buildVisibleVideoFingerprint(platform: String): Int {
        val packageName = currentTargetPackage ?: return 0
        val roots = targetRootsFor(packageName)
        if (roots.isEmpty()) return 0

        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val tokens = LinkedHashSet<String>()

        for (root in roots) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var visited = 0

            while (stack.isNotEmpty() && visited < 1400 && tokens.size < 28) {
                val node = stack.removeLast()
                visited++
                if (!node.isVisibleToUser) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
                    continue
                }

                val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val centerY = rect.centerY()
                val onVideoArea = rect.width() > 0 && rect.height() > 0 &&
                    centerY in (screenH * 0.08f).toInt()..(screenH * 0.94f).toInt()

                if (onVideoArea) {
                    val combined = listOf(text, desc)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    // Prefer fields that describe the currently visible video,
                    // not counters/buttons that can change while the same video
                    // remains on screen.  This makes content-change fallback much
                    // safer on YouTube Shorts and TikTok.
                    val identityId = id.contains("title") || id.contains("caption") ||
                        id.contains("author") || id.contains("channel") || id.contains("username") ||
                        id.contains("creator") || id.contains("music") || id.contains("sound") ||
                        id.contains("audio") || id.contains("description") || id.contains("desc") ||
                        id.contains("aweme") || id.contains("metadata")
                    val dynamicControlId = id.contains("like") || id.contains("comment") ||
                        id.contains("share") || id.contains("send") || id.contains("save") ||
                        id.contains("progress") || id.contains("duration") || id.contains("time") ||
                        id.contains("count") || id.contains("reaction") || id.contains("subscribe") ||
                        id.contains("remix") || id.contains("dislike")
                    val usefulFreeText = cls.contains("textview") && combined.length >= 8 &&
                        !isPureGenericControl(combined) && !looksLikeDynamicControlText(combined)

                    if (combined.isNotBlank() && !dynamicControlId &&
                        !looksLikeDynamicControlText(combined) &&
                        (identityId || usefulFreeText || desc.length >= 8)) {
                        val clean = combined.take(140)
                        tokens.add("${id.substringAfterLast('/').take(40)}:${rect.top / 28}:$clean")
                    }
                }

                for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
            }
        }

        if (tokens.isEmpty()) return 0
        val sorted = tokens.sorted().take(20)
        return (platform + "|" + sorted.joinToString("|")).hashCode()
    }

    private fun looksLikeDynamicControlText(value: String): Boolean {
        val v = value.trim().lowercase(Locale.ROOT)
        if (v.isBlank()) return true
        if (v.matches(Regex("^[0-9.,km+b\\s:]+$"))) return true
        val prefixes = listOf(
            "like", "liked", "comment", "share", "send", "save", "dislike", "remix",
            "subscribe", "subscribed", "more", "play", "pause", "next", "previous"
        )
        return prefixes.any { v == it || v.startsWith("$it ") || v.startsWith("$it,") }
    }

    private fun isPureGenericControl(value: String): Boolean {
        val v = value.trim().lowercase(Locale.ROOT)
        return v in setOf(
            "like", "likes", "comment", "comments", "share", "send", "save", "more",
            "follow", "following", "for you", "home", "search", "profile", "reels",
            "shorts", "remix", "dislike", "subscribe", "subscriptions", "back", "close"
        )
    }

    private fun isShortVideoControlLabel(combinedText: String, id: String, platform: String): Boolean {
        val value = "$combinedText $id".lowercase(Locale.ROOT)
        val common = listOf("like", "comment", "share", "send", "save")
        if (common.any(value::contains)) return true
        return when (platform) {
            "YT" -> listOf("dislike", "remix", "subscribe", "shorts").any(value::contains)
            "TT" -> listOf("follow", "following", "for you", "sound", "music", "aweme").any(value::contains)
            "FB" -> listOf("reel", "reels", "reaction").any(value::contains)
            "IG" -> listOf("reel", "clips", "audio", "original audio").any(value::contains)
            else -> false
        }
    }

    // ---------------------------------------------------------------------
    // Shared pager counting engine
    // ---------------------------------------------------------------------

    private fun handlePagerIndex(
        platform: String,
        pager: PagerState,
        from: Int,
        to: Int,
        cur: Int,
        mode: ScreenMode
    ) {
        val now = System.currentTimeMillis()
        val sinceArm = now - pager.armedAt
        val explicitMove = from >= 0 && to >= 0 && from != to
        val explicitDirection = when {
            explicitMove && to > from -> 1
            explicitMove && to < from -> -1
            else -> 0
        }

        if (pager.page < 0) {
            // Instagram/YouTube often emit one or more programmatic reposition
            // events while opening the viewer. Absorb them as the baseline.
            if (sinceArm <= OPEN_SETTLE_MS) {
                pager.page = cur
                diag("$platform BASELINE opening page=$cur")
                return
            }

            // If no opening settle was exposed and the first event we ever see
            // is already a page transition, from->to tells us it was a real
            // navigation. This prevents a normal quick first flick being lost.
            pager.page = cur
            if (explicitDirection > 0) {
                acceptForwardTransition(platform, mode, "first-index $from->$to")
            } else {
                diag("$platform BASELINE late page=$cur from=$from to=$to")
            }
            return
        }

        // A viewer can reposition itself a few milliseconds after arming. Merge
        // this into the baseline instead of creating a false count.
        if (sinceArm <= OPEN_REPOSITION_MS && cur != pager.page) {
            diag("$platform opening reposition ${pager.page}->$cur merged")
            pager.page = cur
            return
        }

        if (cur == pager.page) return

        val old = pager.page
        pager.page = cur

        val forward = when {
            explicitDirection > 0 -> true
            explicitDirection < 0 -> false
            else -> cur > old
        }

        if (forward) {
            acceptForwardTransition(platform, mode, "page $old->$cur")
        } else {
            diag("$platform BACKWARD $old->$cur no-count")
        }
    }

    private fun scheduleSettledPagerIndex(
        platform: String,
        pager: PagerState,
        cur: Int,
        directionHint: Int,
        mode: ScreenMode
    ) {
        if (cur < 0) return
        pager.settledIndexCandidate = cur
        if (directionHint != 0) pager.settledIndexDirectionHint = directionHint
        val token = ++pager.settledIndexToken

        handler.postDelayed({
            if (token != pager.settledIndexToken || interventionShowing ||
                !pager.active || currentMode != mode) return@postDelayed

            val finalIndex = pager.settledIndexCandidate
            val finalDirection = pager.settledIndexDirectionHint
            pager.settledIndexDirectionHint = 0
            if (finalIndex < 0) return@postDelayed

            if (pager.page < 0) {
                pager.page = finalIndex
                diag("$platform SETTLED baseline page=$finalIndex")
                return@postDelayed
            }
            if (finalIndex == pager.page) {
                diag("$platform SETTLED same page=$finalIndex; gesture snapped back")
                return@postDelayed
            }

            val old = pager.page
            pager.page = finalIndex
            val forward = when {
                finalDirection > 0 -> true
                finalDirection < 0 -> false
                else -> finalIndex > old
            }
            if (forward) {
                acceptForwardTransition(platform, mode, "settled-page $old->$finalIndex")
            } else {
                diag("$platform SETTLED backward $old->$finalIndex no-count")
            }
            updateBubble()
        }, 340L)
    }

    private fun collectPagerFallback(
        platform: String,
        pager: PagerState,
        dy: Int,
        mode: ScreenMode
    ) {
        // A verified short-video feed is already a strong screen-level signal.
        // Several current YouTube/TikTok builds emit TYPE_VIEW_SCROLLED but leave
        // scrollDeltaY/fromIndex/toIndex empty.  Treat those callbacks as one
        // debounced gesture burst instead of dropping them completely.
        if (dy > 0) {
            pager.fallbackNetDy += dy
            pager.fallbackPositiveDy += dy
        } else if (dy < 0) {
            pager.fallbackNetDy += dy
            pager.fallbackNegativeDy += -dy
        } else {
            pager.fallbackUnknownEvents++
        }

        val token = ++pager.fallbackToken
        handler.postDelayed({
            if (token != pager.fallbackToken || !pager.active || interventionShowing || currentMode != mode) {
                return@postDelayed
            }
            if (System.currentTimeMillis() - pager.lastIndexEventAt < FALLBACK_SCROLL_SETTLE_MS + 80L) {
                resetPagerFallback(pager)
                return@postDelayed
            }

            val pos = pager.fallbackPositiveDy
            val neg = pager.fallbackNegativeDy
            val net = pager.fallbackNetDy
            val unknown = pager.fallbackUnknownEvents
            resetPagerFallback(pager)

            val now = System.currentTimeMillis()
            if (now - pager.armedAt <= OPEN_SETTLE_MS) {
                diag("$platform fallback absorbed during open")
                return@postDelayed
            }
            if (now - pager.fallbackLastAcceptedAt < GENERIC_GESTURE_COOLDOWN_MS) {
                diag("$platform fallback gesture cooldown")
                return@postDelayed
            }

            when {
                pos > neg && net > 0 -> {
                    pager.fallbackLastAcceptedAt = now
                    acceptForwardTransition(platform, mode, "pager-delta-fallback")
                }
                neg > pos && net < 0 -> diag("$platform BACKWARD delta-fallback no-count")
                unknown > 0 && pos == 0 && neg == 0 -> {
                    // No direction metadata was exposed, but Android did report a
                    // scroll on a verified full-screen short-video surface. One
                    // quiet burst corresponds to one snap/page change on current
                    // Shorts/TikTok builds.
                    pager.fallbackLastAcceptedAt = now
                    acceptForwardTransition(platform, mode, "verified-scroll-burst")
                }
                else -> diag("$platform fallback direction ambiguous; no-count")
            }
            updateBubble()
        }, FALLBACK_SCROLL_SETTLE_MS)
    }

    private fun acceptForwardTransition(platform: String, mode: ScreenMode, reason: String) {
        if (platform == "YT") {
            scheduleYouTubeConfirmedCount(mode, reason)
            return
        }
        commitForwardTransition(platform, mode, reason)
    }

    private fun scheduleYouTubeConfirmedCount(mode: ScreenMode, reason: String) {
        if (youtubePendingCount) {
            diag("YT pending count already armed; dedupe reason=$reason")
            return
        }
        youtubePendingCount = true
        youtubePendingReason = reason
        val token = ++youtubePendingCountToken
        handler.postDelayed({
            if (!youtubePendingCount || token != youtubePendingCountToken) return@postDelayed
            youtubePendingCount = false
            val now = System.currentTimeMillis()
            val surface = scanYouTubeSurface()
            if (currentMode != ScreenMode.YOUTUBE_SHORTS || surface.commentsOpen || now < youtubeCommentGuardUntil) {
                diag("YT pending count cancelled after surface confirmation reason=$reason")
                return@postDelayed
            }
            commitForwardTransition("YT", mode, "confirmed:$reason")
        }, YOUTUBE_COUNT_CONFIRM_MS)
    }

    private fun cancelYouTubePendingCount(reason: String) {
        if (youtubePendingCount) diag("YT pending count cancelled: $reason pending=$youtubePendingReason")
        youtubePendingCount = false
        youtubePendingReason = ""
        youtubePendingCountToken++
    }

    private fun commitForwardTransition(platform: String, mode: ScreenMode, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastCountedAt < MIN_COUNT_INTERVAL_MS) {
            diag("$platform COUNT cooldown reject reason=$reason")
            return
        }

        lastCountedAt = now
        sessionVideoNumber += 1
        val today = store.incrementTodayCount(currentTargetPackage)
        performScrollHaptic()
        persistSession(force = true)
        diag("$platform COUNT +1 session=$sessionVideoNumber today=$today mode=$mode via=$reason")
    }

    private fun resetPagerState(pager: PagerState) {
        pager.active = false
        pager.page = -1
        pager.armedAt = 0L
        pager.lastSeenAt = 0L
        pager.lastIndexEventAt = 0L
        pager.fallbackNetDy = 0
        pager.fallbackPositiveDy = 0
        pager.fallbackNegativeDy = 0
        pager.fallbackToken++
        pager.fallbackLastAcceptedAt = 0L
        pager.fallbackUnknownEvents = 0
        pager.settledIndexCandidate = -1
        pager.settledIndexDirectionHint = 0
        pager.settledIndexToken++
    }

    private fun cancelPagerFallback(pager: PagerState) {
        pager.fallbackToken++
        resetPagerFallback(pager)
    }

    private fun resetPagerFallback(pager: PagerState) {
        pager.fallbackNetDy = 0
        pager.fallbackPositiveDy = 0
        pager.fallbackNegativeDy = 0
        pager.fallbackUnknownEvents = 0
    }

    // ---------------------------------------------------------------------
    // Surface refresh/session persistence
    // ---------------------------------------------------------------------

    private fun refreshTargetSurface(packageName: String, immediate: Boolean = false) {
        bpScheduleScan(packageName, if (immediate) 0L else BP_EVENT_COALESCE_MS, "explicit refresh")
    }

    private fun persistSessionIfNeeded() {
        if (System.currentTimeMillis() - lastSessionPersistAt >= SESSION_PERSIST_INTERVAL_MS) {
            persistSession(force = true)
        }
    }

    private fun persistSession(force: Boolean = false) {
        val target = currentTargetPackage ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSessionPersistAt < SESSION_PERSIST_INTERVAL_MS) return
        store.saveSession(
            packageName = target,
            videoNumber = sessionVideoNumber,
            startedAt = sessionStartedAt,
            nextInterventionAt = nextInterventionAt,
            lastSeenAt = now,
            watchedMs = currentSessionWatchMs(),
            reminderPhase = currentReminderPhase
        )
        lastSessionPersistAt = now
    }

    private fun isTikTokPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.ROOT)
        return packageName == TIKTOK || packageName == TIKTOK_ALT ||
            packageName == TIKTOK_LITE || packageName == TIKTOK_LITE_ALT ||
            p.contains("musically") || p.contains("ugc.trill") || p.contains("tiktok")
    }

    private fun isEnabledTarget(packageName: String): Boolean {
        if (!store.protectionEnabled) return false
        return (packageName == INSTAGRAM && store.instagramEnabled) ||
            (packageName == YOUTUBE && store.youtubeEnabled) ||
            (isTikTokPackage(packageName) && store.tiktokEnabled) ||
            (packageName == FACEBOOK && store.facebookEnabled)
    }

    private fun isShortVideoMode(mode: ScreenMode): Boolean {
        return mode == ScreenMode.INSTAGRAM_REELS ||
            mode == ScreenMode.YOUTUBE_SHORTS ||
            mode == ScreenMode.TIKTOK_FEED ||
            mode == ScreenMode.FACEBOOK_REELS
    }

    private fun isBubbleSurfaceMode(mode: ScreenMode): Boolean {
        // The user requested the floating counter only while an actual short-video
        // feed is visible. Comments, Home, Profile, Search grids and other screens
        // deliberately hide it instead of showing a paused bubble.
        return isShortVideoMode(mode)
    }

    private fun shouldBubbleBeVisible(): Boolean {
        if (currentTargetPackage == null || !store.protectionEnabled) return false
        return isShortVideoMode(currentMode)
    }

    // ---------------------------------------------------------------------
    // Bubble UI
    // ---------------------------------------------------------------------

    private fun showBubble() {
        if (!shouldBubbleBeVisible() || (!store.showCounter && !store.showTimer)) {
            hideBubble()
            return
        }
        if (bubbleView != null) {
            updateBubble()
            return
        }

        val size = dp(BUBBLE_SIZE_DP)
        val circle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(3), dp(2), dp(2))
            elevation = dp(8).toFloat()
            alpha = 0.94f
            background = circleDrawable(
                fillColor = 0xE315151B.toInt(),
                strokeColor = 0x665E50FF,
                strokeWidthDp = 1
            )
        }

        val countText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            text = "–"
        }

        val timerText = TextView(this).apply {
            setTextColor(0xFFC6C0D8.toInt())
            textSize = 7.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            text = "00:00"
        }

        circle.addView(
            countText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        circle.addView(
            timerText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(120)
        }

        makeDraggableAndLongPress(circle, params)
        bubbleView = circle
        bubbleCountText = countText
        bubbleTimerText = timerText
        bubbleParams = params
        windowManager.addView(circle, params)
        updateBubble()
    }

    private fun updateBubble() {
        if (!shouldBubbleBeVisible()) {
            hideBubble()
            return
        }

        if (bubbleView == null) {
            showBubble()
            return
        }

        if (!store.showCounter && !store.showTimer) {
            hideBubble()
            return
        }

        bubbleView?.background = circleDrawable(
            fillColor = if (store.remindersEnabled) 0xE315151B.toInt() else 0xE3222229.toInt(),
            strokeColor = if (store.remindersEnabled) 0x665E50FF else 0x556C6A74,
            strokeWidthDp = 1
        )

        bubbleCountText?.apply {
            visibility = if (store.showCounter) View.VISIBLE else View.GONE
            text = store.getTodayCount().coerceAtLeast(0).toString()
        }

        bubbleTimerText?.apply {
            visibility = if (store.showTimer) View.VISIBLE else View.GONE
            // Reminder status is visible at a glance: green = interventions armed,
            // red = counting continues but reminders are temporarily disabled.
            setTextColor(if (store.remindersEnabled) 0xFF70E1C1.toInt() else 0xFFFF5F68.toInt())
            val elapsed = (currentSessionWatchMs() / 1000L).coerceAtLeast(0L)
            val min = elapsed / 60
            val sec = elapsed % 60
            text = "%02d:%02d".format(min, sec)
        }
    }

    private fun hideBubble() {
        hideBubbleQuickMenu()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleCountText = null
        bubbleTimerText = null
        bubbleParams = null
    }

    // ---------------------------------------------------------------------
    // Intervention overlay
    // ---------------------------------------------------------------------

    private fun showIntervention(testMode: Boolean) {
        if (interventionShowing) return
        if (!testMode && !store.remindersEnabled) return
        interventionShowing = true
        performConfirmHaptic()
        muteMediaForIntervention()
        exitCheckToken++
        if (!testMode) pauseWatchClock("intervention shown")
        hideBubble()

        val root = FrameLayout(this).apply { setBackgroundColor(0xCC0A0A0E.toInt()) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            background = roundedDrawable(0xFF15151C.toInt(), 26f, 0x334C3FFF, 1)
            elevation = dp(14).toFloat()
        }

        fun addText(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(5), 0, dp(5))
                card.addView(
                    this,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        addText("MINDSCROLL • PAUSE", 12f, 0xFFA99BFF.toInt(), true)
        addText("Your attention is worth more than another scroll.", 25f, Color.WHITE, true)
        addText(
            if (testMode) "This is your test intervention." else "You've been scrolling for a while.",
            14f,
            0xFFB8B4C5.toInt()
        )

        val selectedMotivation = if (store.videoEnabled) motivationLibrary.chooseNextMedia() else null
        val selectedUri = selectedMotivation?.let { motivationLibrary.uriFor(it) }
        if (selectedUri != null && selectedMotivation != null) {
            val mediaFallback = TextView(this).apply {
                text = "This motivation item could not be displayed.\n\nPause for a moment — do you still want another ${store.repeatPauseMinutes} minutes?"
                textSize = 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(24), dp(14), dp(24))
                background = roundedDrawable(0xFF0F0F14.toInt(), 20f)
                visibility = View.GONE
            }

            if (selectedMotivation.isImage) {
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    adjustViewBounds = true
                    background = roundedDrawable(0xFF0F0F14.toInt(), 20f)
                    val bitmap = selectedMotivation.file?.absolutePath?.let { path ->
                        runCatching { decodeSampledBitmap(path, dp(900), dp(900)) }.getOrNull()
                    }
                    if (bitmap != null) {
                        setImageBitmap(bitmap)
                    } else {
                        visibility = View.GONE
                        mediaFallback.visibility = View.VISIBLE
                    }
                }
                card.addView(
                    image,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(280)
                    ).apply {
                        topMargin = dp(14)
                        bottomMargin = dp(8)
                    }
                )
            } else {
                val video = VideoView(this).apply {
                    if (!selectedMotivation.builtIn) {
                        selectedMotivation.file?.absolutePath?.let { setVideoPath(it) }
                    } else {
                        setVideoURI(selectedUri)
                    }
                    setOnPreparedListener { player ->
                        player.isLooping = true
                        // V19 muted the entire STREAM_MUSIC output, which also muted
                        // MindScroll's own imported video. V20 uses transient audio focus
                        // instead, so the underlying Reel/Short yields audio while the
                        // motivation video's own audio remains audible.
                        player.setVolume(1f, 1f)
                        start()
                    }
                    setOnErrorListener { _, _, _ ->
                        visibility = View.GONE
                        mediaFallback.visibility = View.VISIBLE
                        true
                    }
                }
                card.addView(
                    video,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(250)
                    ).apply {
                        topMargin = dp(14)
                        bottomMargin = dp(8)
                    }
                )
            }

            card.addView(
                mediaFallback,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(8)
                }
            )
            addText(selectedMotivation.title, 11f, 0xFFB8B4C5.toInt())
        } else {
            val motivation = TextView(this).apply {
                text = "STOP.\n\nYou opened MindScroll for a reason.\nIs another ${store.repeatPauseMinutes} minutes really what you want?"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(24), dp(14), dp(24))
                background = roundedDrawable(0xFF0F0F14.toInt(), 20f)
            }
            card.addView(
                motivation,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(14)
                }
            )
        }

        val countdown = addText(
            "Think for ${store.reflectionSeconds} seconds...",
            14f,
            0xFF70E1C1.toInt(),
            true
        )
        val question = addText("Do you still want to continue?", 18f, Color.WHITE, true).apply {
            visibility = View.GONE
        }

        val noButton = actionButton("No — I'm done", 0xFF70E1C1.toInt(), 0xFF10251F.toInt()).apply {
            visibility = View.GONE
            setOnClickListener {
                dismissIntervention()
                hardEndSession("user chose No")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }

        val yesButton = actionButton("Yes — continue", 0xFF252531.toInt(), Color.WHITE).apply {
            visibility = View.GONE
            setOnClickListener {
                dismissIntervention()
                if (testMode) return@setOnClickListener
                val now = System.currentTimeMillis()
                // Keep the current session number/time. From this point onward the
                // repeat-reminder setting owns the active countdown.
                currentReminderPhase = SettingsStore.REMINDER_PHASE_REPEAT
                sessionStartedAt = now
                interventionPausedAt = 0L
                nextInterventionAt = if (store.remindersEnabled) now + store.repeatPauseMinutes * 60_000L else 0L
                resumeWatchClock("user chose continue")
                persistSession(force = true)
                refreshTargetSurface(currentTargetPackage ?: return@setOnClickListener, immediate = true)
            }
        }

        val dismissNow = TextView(this).apply {
            text = "Dismiss for now"
            textSize = 13f
            setTextColor(0xFFC6C0D8.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                performConfirmHaptic()
                dismissIntervention()
                if (!testMode) {
                    val now = System.currentTimeMillis()
                    currentReminderPhase = SettingsStore.REMINDER_PHASE_REPEAT
                    sessionStartedAt = now
                    nextInterventionAt = if (store.remindersEnabled) now + store.repeatPauseMinutes * 60_000L else 0L
                    resumeWatchClock("user dismissed reminder")
                    persistSession(force = true)
                    currentTargetPackage?.let { refreshTargetSurface(it, immediate = true) }
                }
            }
        }

        card.addView(dismissNow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(noButton, buttonLayoutParams())
        card.addView(yesButton, buttonLayoutParams())
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        interventionView = root
        windowManager.addView(root, params)

        var remaining = store.reflectionSeconds
        val countdownRunnable = object : Runnable {
            override fun run() {
                if (!interventionShowing) return
                remaining -= 1
                if (remaining <= 0) {
                    countdown.visibility = View.GONE
                    question.visibility = View.VISIBLE
                    noButton.visibility = View.VISIBLE
                    yesButton.visibility = View.VISIBLE
                } else {
                    countdown.text = "Think for $remaining seconds..."
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.postDelayed(countdownRunnable, 1000L)
    }

    private fun dismissIntervention() {
        interventionView?.let { view ->
            (view as? FrameLayout)?.let { root -> findVideoView(root)?.stopPlayback() }
            runCatching { windowManager.removeView(view) }
        }
        interventionView = null
        interventionShowing = false
        restoreMediaAfterIntervention()
    }

    private fun findVideoView(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findVideoView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun actionButton(text: String, backgroundColor: Int, textColor: Int): Button {
        return Button(this).apply {
            this.text = text
            this.textSize = 15f
            isAllCaps = false
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            backgroundTintList = android.content.res.ColorStateList.valueOf(backgroundColor)
        }
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply { topMargin = dp(10) }
    }

    private fun makeDraggableAndLongPress(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var longPressed = false
        val moveThreshold = dp(8).toFloat()

        val longPressRunnable = Runnable {
            if (!moved) {
                longPressed = true
                performLongPressHaptic()
                showBubbleQuickMenu()
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPressRunnable, BUBBLE_LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > moveThreshold || abs(dy) > moveThreshold) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (!longPressed) {
                        params.x = startX - dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                        bubbleMenuView?.let { menu -> positionBubbleQuickMenu(menu) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun showBubbleQuickMenu() {
        if (bubbleMenuView != null || bubbleView == null || bubbleParams == null) return
        val label = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(9), dp(12), dp(9))
            gravity = Gravity.CENTER
            elevation = dp(10).toFloat()
            background = roundedDrawable(0xF21A1A22.toInt(), 18f, 0x665E50FF, 1)
            setOnClickListener {
                store.remindersEnabled = !store.remindersEnabled
                if (store.remindersEnabled) {
                    val now = System.currentTimeMillis()
                    val minutes = if (currentReminderPhase == SettingsStore.REMINDER_PHASE_REPEAT) store.repeatPauseMinutes else store.firstPauseMinutes
                    sessionStartedAt = now
                    nextInterventionAt = now + minutes * 60_000L
                } else {
                    nextInterventionAt = 0L
                }
                performConfirmHaptic()
                updateBubbleQuickMenuText(this)
                updateBubble()
                persistSession(force = true)
                scheduleBubbleMenuAutoHide()
                diag("REMINDERS quick-toggle enabled=${store.remindersEnabled}")
            }
        }
        updateBubbleQuickMenuText(label)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END }
        label.tag = params
        bubbleMenuView = label
        windowManager.addView(label, params)
        positionBubbleQuickMenu(label)
        scheduleBubbleMenuAutoHide()
    }

    private fun updateBubbleQuickMenuText(view: TextView) {
        val on = store.remindersEnabled
        view.text = if (on) "Reminders  ON" else "Reminders  OFF"
        view.setTextColor(if (on) 0xFF70E1C1.toInt() else 0xFFC6C0D8.toInt())
    }

    private fun positionBubbleQuickMenu(menu: View) {
        val bubble = bubbleParams ?: return
        val menuParams = menu.tag as? WindowManager.LayoutParams ?: return
        val screenW = resources.displayMetrics.widthPixels
        menuParams.x = if (bubble.x < screenW / 2) {
            // Bubble is nearer the right edge: put the menu to its left.
            bubble.x + dp(BUBBLE_SIZE_DP + 8)
        } else {
            // Bubble has been dragged toward the left edge: put the menu to its right.
            (bubble.x - dp(118)).coerceAtLeast(dp(4))
        }
        menuParams.y = bubble.y + dp(2)
        runCatching { windowManager.updateViewLayout(menu, menuParams) }
    }

    private fun scheduleBubbleMenuAutoHide() {
        val view = bubbleMenuView ?: return
        handler.removeCallbacksAndMessages(view)
        handler.postAtTime({ if (bubbleMenuView === view) hideBubbleQuickMenu() }, view, android.os.SystemClock.uptimeMillis() + BUBBLE_MENU_HIDE_MS)
    }

    private fun hideBubbleQuickMenu() {
        val view = bubbleMenuView ?: return
        handler.removeCallbacksAndMessages(view)
        runCatching { windowManager.removeView(view) }
        bubbleMenuView = null
    }

    private fun performScrollHaptic() {
        if (!store.hapticsEnabled) return
        val view = bubbleView ?: return
        val effect = if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(effect)
    }

    private fun performConfirmHaptic() {
        (bubbleView ?: interventionView)?.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    private fun performLongPressHaptic() {
        bubbleView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * Request transient media focus instead of setting STREAM_MUSIC to zero.
     * Zeroing the stream muted the user's imported motivation video as well.
     * Audio focus asks the underlying social app to pause/yield while keeping
     * MindScroll's own video audio audible.
     */
    private fun muteMediaForIntervention() {
        if (!store.muteDuringIntervention || hasTransientAudioFocus) return
        runCatching {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val result = audio.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            hasTransientAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.onFailure { hasTransientAudioFocus = false }
    }

    private fun restoreMediaAfterIntervention() {
        if (!hasTransientAudioFocus) return
        hasTransientAudioFocus = false
        runCatching {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun decodeSampledBitmap(path: String, targetWidth: Int, targetHeight: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > targetWidth * 2 || bounds.outHeight / sample > targetHeight * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun circleDrawable(fillColor: Int, strokeColor: Int? = null, strokeWidthDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            if (strokeColor != null && strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun roundedDrawable(
        fillColor: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    // ---------------------------------------------------------------------
    // Diagnostics — metadata only, never captions/comments/usernames.
    // ---------------------------------------------------------------------

    private fun isDebugBuild(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun diagPagerScroll(platform: String, event: AccessibilityEvent, cur: Int, dy: Int) {
        if (!isDebugBuild() || !store.diagnosticsEnabled) return
        val source = event.source
        val id = source?.viewIdResourceName?.substringAfterLast('/') ?: "-"
        val clazz = source?.className?.toString()?.substringAfterLast('.') ?: "-"
        val page = when (platform) {
            "IG" -> instagramPager.page
            "YT" -> youtubePager.page
            "TT" -> tiktokPager.page
            "FB" -> facebookPager.page
            else -> -1
        }
        diag(
            "$platform PAGER_SCROLL page=$page " +
                "from=${event.fromIndex} to=${event.toIndex} cur=$cur dy=$dy cls=$clazz id=$id"
        )
    }

    private fun diagScrollRejected(event: AccessibilityEvent, reason: String) {
        if (!isDebugBuild() || !store.diagnosticsEnabled) return
        val source = event.source
        val id = source?.viewIdResourceName?.substringAfterLast('/') ?: "-"
        val clazz = source?.className?.toString()?.substringAfterLast('.') ?: "-"
        diag("SCROLL reject reason=$reason cls=$clazz id=$id")
    }

    private fun diag(message: String) {
        if (!isDebugBuild() || !::store.isInitialized || !store.diagnosticsEnabled) return
        runCatching {
            val file = File(filesDir, DIAG_FILE)
            if (file.exists() && file.length() > MAX_DIAG_BYTES) {
                file.writeText("-- diagnostics trimmed --\n")
            }
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$time $message\n")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::store.isInitialized) {
            pauseWatchClock("service destroy")
            persistSession(force = true)
        }
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        dismissIntervention()
        runCatching { unregisterReceiver(controlReceiver) }
        super.onDestroy()
    }
}
