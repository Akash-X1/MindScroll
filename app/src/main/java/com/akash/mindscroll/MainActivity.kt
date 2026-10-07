package com.akash.mindscroll

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var store: SettingsStore
    private lateinit var motivationLibrary: MotivationLibrary
    private val serviceEnabledState = mutableStateOf(false)
    private val todayCountState = mutableIntStateOf(0)
    private val todayWatchState = mutableLongStateOf(0L)
    private val dashboardRefreshState = mutableIntStateOf(0)
    private val libraryRefreshState = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = SettingsStore(this)
        store.diagnosticsEnabled = false
        motivationLibrary = MotivationLibrary(this)
        refreshDashboard()

        setContent {
            MindScrollTheme {
                MindScrollApp(
                    store = store,
                    motivationLibrary = motivationLibrary,
                    libraryRefreshKey = libraryRefreshState.intValue,
                    serviceEnabled = serviceEnabledState.value,
                    todayCount = todayCountState.intValue,
                    todayWatchMs = todayWatchState.longValue,
                    refreshKey = dashboardRefreshState.intValue,
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onProtectionChanged = { enabled ->
                        store.protectionEnabled = enabled
                        sendServiceAction(
                            MindScrollAccessibilityService.ACTION_PROTECTION_CHANGED,
                            openSettingsIfDisabled = false
                        )
                        refreshDashboard()
                    },
                    onTestIntervention = {
                        sendServiceAction(MindScrollAccessibilityService.ACTION_TEST_INTERVENTION)
                    },
                    onReminderTimingChanged = {
                        sendServiceAction(
                            MindScrollAccessibilityService.ACTION_REMINDER_TIMING_CHANGED,
                            openSettingsIfDisabled = false
                        )
                        refreshDashboard()
                    },
                    onLibraryChanged = { libraryRefreshState.intValue++ },
                    onResetAllStatistics = {
                        store.clearAllHistory()
                        sendServiceAction(MindScrollAccessibilityService.ACTION_RESET_COUNTER, openSettingsIfDisabled = false)
                        refreshDashboard()
                    },
                    onDeleteAllData = { deleteAllData() }
                )
            }
        }
        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun refreshDashboard() {
        serviceEnabledState.value = isAccessibilityServiceEnabled(this)
        if (::store.isInitialized) {
            todayCountState.intValue = store.getTodayCount()
            todayWatchState.longValue = store.getTodayWatchMs()
            dashboardRefreshState.intValue++
        }
    }

    private fun sendServiceAction(action: String, openSettingsIfDisabled: Boolean = true) {
        if (!isAccessibilityServiceEnabled(this)) {
            if (openSettingsIfDisabled) {
                Toast.makeText(this, "Enable MindScroll Accessibility first.", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        if (type.startsWith("video/") || type.startsWith("image/")) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                val result = motivationLibrary.importMedia(uri)
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                if (result.success) libraryRefreshState.intValue++
            }
        } else if (type == "text/plain") {
            Toast.makeText(
                this,
                "MindScroll can import a shared image or video file. Social-media links are not downloaded automatically.",
                Toast.LENGTH_LONG
            ).show()
        }
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    private fun deleteAllData() {
        motivationLibrary.deleteAllUserVideos()
        File(filesDir, "mindscroll_diagnostics.txt").delete()
        store.clearAllData()
        sendBroadcast(Intent(MindScrollAccessibilityService.ACTION_DELETE_ALL_DATA).setPackage(packageName))
        Toast.makeText(this, "MindScroll data deleted from this device.", Toast.LENGTH_LONG).show()
        recreate()
    }

    private fun copyDiagnostics() {
        val file = File(filesDir, "mindscroll_diagnostics.txt")
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "No diagnostics recorded yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val text = file.readText().takeLast(120_000)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MindScroll diagnostics", text))
        Toast.makeText(this, "Diagnostics copied to clipboard.", Toast.LENGTH_SHORT).show()
    }

    private fun clearDiagnostics() {
        File(filesDir, "mindscroll_diagnostics.txt").delete()
        Toast.makeText(this, "Diagnostics cleared.", Toast.LENGTH_SHORT).show()
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, MindScrollAccessibilityService::class.java).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private val MindColors = darkColorScheme(
    primary = Color(0xFFA99BFF),
    onPrimary = Color(0xFF191527),
    secondary = Color(0xFF70E1C1),
    background = Color(0xFF09090D),
    surface = Color(0xFF121217),
    surfaceVariant = Color(0xFF1B1B22),
    onBackground = Color(0xFFF5F3FB),
    onSurface = Color(0xFFF5F3FB),
    onSurfaceVariant = Color(0xFFB8B4C5)
)

@Composable
private fun MindScrollTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MindColors, content = content)
}

private enum class ReminderWindowKind(val phase: String, val displayName: String) {
    FIRST(SettingsStore.REMINDER_PHASE_FIRST, "first reminder"),
    REPEAT(SettingsStore.REMINDER_PHASE_REPEAT, "repeat reminder")
}

private data class PendingReminderLimitChange(
    val kind: ReminderWindowKind,
    val newMinutes: Int,
    val elapsedUsedMs: Long
)

@Composable
private fun MindScrollApp(
    store: SettingsStore,
    motivationLibrary: MotivationLibrary,
    libraryRefreshKey: Int,
    serviceEnabled: Boolean,
    todayCount: Int,
    todayWatchMs: Long,
    refreshKey: Int,
    onOpenAccessibility: () -> Unit,
    onProtectionChanged: (Boolean) -> Unit,
    onTestIntervention: () -> Unit,
    onReminderTimingChanged: () -> Unit,
    onLibraryChanged: () -> Unit,
    onResetAllStatistics: () -> Unit,
    onDeleteAllData: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showWelcome by remember { mutableStateOf(!store.hasSeenWelcome) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var protectionEnabled by remember { mutableStateOf(store.protectionEnabled) }
    var firstPause by remember { mutableIntStateOf(store.firstPauseMinutes) }
    var repeatPause by remember { mutableIntStateOf(store.repeatPauseMinutes) }
    var reflection by remember { mutableIntStateOf(store.reflectionSeconds) }
    var instagramEnabled by remember { mutableStateOf(store.instagramEnabled) }
    var youtubeEnabled by remember { mutableStateOf(store.youtubeEnabled) }
    var tiktokEnabled by remember { mutableStateOf(store.tiktokEnabled) }
    var facebookEnabled by remember { mutableStateOf(store.facebookEnabled) }
    var remindersEnabled by remember { mutableStateOf(store.remindersEnabled) }
    var hapticsEnabled by remember { mutableStateOf(store.hapticsEnabled) }
    var muteDuringIntervention by remember { mutableStateOf(store.muteDuringIntervention) }
    var skipSponsoredVideos by remember { mutableStateOf(store.skipSponsoredVideos) }
    var showCounter by remember { mutableStateOf(store.showCounter) }
    var showTimer by remember { mutableStateOf(store.showTimer) }
    var videoEnabled by remember { mutableStateOf(store.videoEnabled) }
    var showResetStatsConfirm by remember { mutableStateOf(false) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var pendingReminderLimitChange by remember { mutableStateOf<PendingReminderLimitChange?>(null) }

    fun configuredMinutes(kind: ReminderWindowKind): Int = when (kind) {
        ReminderWindowKind.FIRST -> firstPause
        ReminderWindowKind.REPEAT -> repeatPause
    }

    fun commitReminderChange(
        kind: ReminderWindowKind,
        newMinutes: Int,
        resetCurrentWindow: Boolean,
        elapsedUsedMs: Long
    ) {
        when (kind) {
            ReminderWindowKind.FIRST -> {
                firstPause = newMinutes
                store.firstPauseMinutes = newMinutes
            }
            ReminderWindowKind.REPEAT -> {
                repeatPause = newMinutes
                store.repeatPauseMinutes = newMinutes
            }
        }

        val session = store.loadSession()
        val now = System.currentTimeMillis()
        val resumable = session != null &&
            protectionEnabled && serviceEnabled &&
            session.reminderPhase == kind.phase &&
            session.nextInterventionAt > 0L &&
            session.lastSeenAt > 0L &&
            now - session.lastSeenAt in 0L..(10 * 60_000L)

        if (resumable) {
            store.updateActiveReminderWindow(
                phase = kind.phase,
                minutes = newMinutes,
                resetWindow = resetCurrentWindow,
                elapsedUsedMs = if (resetCurrentWindow) 0L else elapsedUsedMs
            )
        }
        onReminderTimingChanged()
    }

    fun requestReminderChange(kind: ReminderWindowKind, newMinutes: Int) {
        val oldMinutes = configuredMinutes(kind)
        if (newMinutes == oldMinutes) return

        val session = store.loadSession()
        val now = System.currentTimeMillis()
        val affectsCurrentWindow = session != null &&
            protectionEnabled && serviceEnabled &&
            session.reminderPhase == kind.phase &&
            session.nextInterventionAt > 0L &&
            session.lastSeenAt > 0L &&
            now - session.lastSeenAt in 0L..(10 * 60_000L)

        if (affectsCurrentWindow && session != null) {
            val oldDurationMs = oldMinutes.coerceAtLeast(1) * 60_000L
            val remainingMs = (session.nextInterventionAt - session.lastSeenAt)
                .coerceIn(0L, oldDurationMs)
            val elapsedUsedMs = (oldDurationMs - remainingMs).coerceAtLeast(0L)
            val newDurationMs = newMinutes.coerceAtLeast(1) * 60_000L

            if (newDurationMs <= elapsedUsedMs) {
                pendingReminderLimitChange = PendingReminderLimitChange(
                    kind = kind,
                    newMinutes = newMinutes,
                    elapsedUsedMs = elapsedUsedMs
                )
                return
            }

            // The new limit is still ahead of the user. Preserve time already
            // watched and simply move the deadline. Example: 5m -> 3m after
            // using 2m leaves exactly 1m.
            commitReminderChange(kind, newMinutes, false, elapsedUsedMs)
        } else {
            commitReminderChange(kind, newMinutes, false, 0L)
        }
    }

    val context = LocalContext.current
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val result = motivationLibrary.importMedia(uri)
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            if (result.success) onLibraryChanged()
        }
    }

    pendingReminderLimitChange?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingReminderLimitChange = null },
            title = { Text("New limit already reached", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "You've already used ${formatCountdown(pending.elapsedUsedMs)} of the current ${pending.kind.displayName} window. " +
                        "Changing it to ${formatMinutes(pending.newMinutes)} means the new limit has already been reached.\n\n" +
                        "OK keeps your progress, so the reminder will be due as soon as you return to short videos. " +
                        "Reset starts a fresh ${formatMinutes(pending.newMinutes)} countdown."
                )
            },
            confirmButton = {
                Button(onClick = {
                    commitReminderChange(
                        kind = pending.kind,
                        newMinutes = pending.newMinutes,
                        resetCurrentWindow = false,
                        elapsedUsedMs = pending.elapsedUsedMs
                    )
                    pendingReminderLimitChange = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    commitReminderChange(
                        kind = pending.kind,
                        newMinutes = pending.newMinutes,
                        resetCurrentWindow = true,
                        elapsedUsedMs = 0L
                    )
                    pendingReminderLimitChange = null
                }) { Text("Reset") }
            }
        )
    }

    if (showResetStatsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetStatsConfirm = false },
            title = { Text("Reset all statistics?", fontWeight = FontWeight.Bold) },
            text = { Text("This permanently deletes your video-count and watch-time history from this device. Your settings and motivation media are kept.") },
            confirmButton = {
                Button(onClick = {
                    showResetStatsConfirm = false
                    onResetAllStatistics()
                }) { Text("Reset statistics") }
            },
            dismissButton = { TextButton(onClick = { showResetStatsConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("Delete all MindScroll data?", fontWeight = FontWeight.Bold) },
            text = { Text("This removes your statistics, settings, imported motivation media and local preferences. Built-in media remains packaged with the app and will be restored. This cannot be undone.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                    onClick = {
                        showDeleteAllConfirm = false
                        onDeleteAllData()
                    }
                ) { Text("Delete all my data") }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Privacy & Accessibility", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MindScroll processes supported short-video screen structure on-device to identify when Reels/Shorts are visible, recognize stable video changes, measure watch time, and show reminders you configure.")
                    Text("It does not upload accessibility screen data, captions, comments, messages, passwords, typed text, viewing identifiers, or your imported motivation media.")
                    Text("Statistics and settings are stored locally. Imported images/videos are copied into MindScroll's private app storage.")
                    Text("MindScroll does not automatically download videos from social-media links.")
                    Text("You can disable Protection in MindScroll or disable the Accessibility service at any time.")
                }
            },
            confirmButton = { Button(onClick = { showPrivacyDialog = false }) { Text("Done") } }
        )
    }

    if (showWelcome) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Your attention stays yours", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MindScroll works locally on your phone.")
                    Text("• No account required\n• No cloud upload\n• No message or password storage\n• No photo or video recording\n• Imported motivation media stay in private app storage")
                    Text(
                        "MindScroll does not request Internet permission.",
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    store.hasSeenWelcome = true
                    showWelcome = false
                }) { Text("I understand") }
            }
        )
    }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text("Accessibility access", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MindScroll uses Android Accessibility to read on-screen UI structure from supported apps only to recognize short-video viewers and stable video changes. This powers the counter, watch-time timer, and reminders you configure.")
                    Text(
                        "Processing stays on your device. MindScroll does not store or transmit captions, comments, messages, passwords, typed text, or accessibility-tree contents.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    store.accessibilityDisclosureAccepted = true
                    showAccessibilityDisclosure = false
                    onOpenAccessibility()
                }) { Text("Agree & open settings") }
            },
            dismissButton = { TextButton(onClick = { showAccessibilityDisclosure = false }) { Text("Not now") } }
        )
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Accessibility permission required", fontWeight = FontWeight.Bold) },
            text = { Text("Protection cannot run until MindScroll is enabled in Android Accessibility settings. Your preference is saved and will become active after permission is granted.") },
            confirmButton = {
                Button(onClick = {
                    showPermissionDialog = false
                    if (store.accessibilityDisclosureAccepted) onOpenAccessibility() else showAccessibilityDisclosure = true
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showPermissionDialog = false }) { Text("Not now") } }
        )
    }

    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val contentBottom = navigationBottom + 104.dp

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (selectedTab) {
            0 -> HomeScreen(
                modifier = Modifier.fillMaxSize(),
                statusTop = statusTop,
                bottomPadding = contentBottom,
                store = store,
                serviceEnabled = serviceEnabled,
                protectionEnabled = protectionEnabled,
                todayCount = todayCount,
                todayWatchMs = todayWatchMs,
                firstPause = firstPause,
                repeatPause = repeatPause,
                reflection = reflection,
                remindersEnabled = remindersEnabled,
                instagramEnabled = instagramEnabled,
                youtubeEnabled = youtubeEnabled,
                tiktokEnabled = tiktokEnabled,
                facebookEnabled = facebookEnabled,
                motivationLibrary = motivationLibrary,
                libraryRefreshKey = libraryRefreshKey,
                onProtectionChanged = { requested ->
                    if (requested && !serviceEnabled) {
                        protectionEnabled = true
                        onProtectionChanged(true)
                        showPermissionDialog = true
                    } else {
                        protectionEnabled = requested
                        onProtectionChanged(requested)
                    }
                },
                onOpenAccessibility = {
                    if (store.accessibilityDisclosureAccepted) onOpenAccessibility() else showAccessibilityDisclosure = true
                },
                onTestIntervention = onTestIntervention,
                onFirstPause = { requestReminderChange(ReminderWindowKind.FIRST, it) },
                onRepeatPause = { requestReminderChange(ReminderWindowKind.REPEAT, it) },
                onReflection = {
                    reflection = it
                    store.reflectionSeconds = it
                },
                onRemindersChanged = {
                    remindersEnabled = it
                    store.remindersEnabled = it
                    onProtectionChanged(protectionEnabled)
                },
                onInstagramChanged = { instagramEnabled = it; store.instagramEnabled = it },
                onYoutubeChanged = { youtubeEnabled = it; store.youtubeEnabled = it },
                onTiktokChanged = { tiktokEnabled = it; store.tiktokEnabled = it },
                onFacebookChanged = { facebookEnabled = it; store.facebookEnabled = it },
                onOpenVideos = { selectedTab = 2 }
            )
            1 -> StatsScreen(
                modifier = Modifier.fillMaxSize(),
                statusTop = statusTop,
                bottomPadding = contentBottom,
                store = store,
                refreshKey = refreshKey
            )
            2 -> MotivationLibraryScreen(
                modifier = Modifier.fillMaxSize(),
                statusTop = statusTop,
                bottomPadding = contentBottom,
                library = motivationLibrary,
                refreshKey = libraryRefreshKey,
                onAddMedia = {
                    mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onChanged = onLibraryChanged
            )
            else -> SettingsScreen(
                modifier = Modifier.fillMaxSize(),
                statusTop = statusTop,
                bottomPadding = contentBottom,
                serviceEnabled = serviceEnabled,
                videoEnabled = videoEnabled,
                muteDuringIntervention = muteDuringIntervention,
                skipSponsoredVideos = skipSponsoredVideos,
                hapticsEnabled = hapticsEnabled,
                showCounter = showCounter,
                showTimer = showTimer,
                onVideoEnabled = { videoEnabled = it; store.videoEnabled = it },
                onMuteDuringIntervention = { muteDuringIntervention = it; store.muteDuringIntervention = it },
                onSkipSponsored = { skipSponsoredVideos = it; store.skipSponsoredVideos = it },
                onHaptics = { hapticsEnabled = it; store.hapticsEnabled = it },
                onShowCounter = { showCounter = it; store.showCounter = it },
                onShowTimer = { showTimer = it; store.showTimer = it },
                onOpenAccessibility = {
                    if (store.accessibilityDisclosureAccepted) onOpenAccessibility() else showAccessibilityDisclosure = true
                },
                onPrivacy = { showPrivacyDialog = true },
                onResetStats = { showResetStatsConfirm = true },
                onDeleteAll = { showDeleteAllConfirm = true }
            )
        }

        if (selectedTab == 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusTop + 28.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0f)
                            )
                        )
                    )
            )
        }

        MindScrollBottomBar(
            selected = selectedTab,
            navigationBottom = navigationBottom,
            onSelected = { selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    statusTop: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    store: SettingsStore,
    serviceEnabled: Boolean,
    protectionEnabled: Boolean,
    todayCount: Int,
    todayWatchMs: Long,
    firstPause: Int,
    repeatPause: Int,
    reflection: Int,
    remindersEnabled: Boolean,
    instagramEnabled: Boolean,
    youtubeEnabled: Boolean,
    tiktokEnabled: Boolean,
    facebookEnabled: Boolean,
    motivationLibrary: MotivationLibrary,
    libraryRefreshKey: Int,
    onProtectionChanged: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onTestIntervention: () -> Unit,
    onFirstPause: (Int) -> Unit,
    onRepeatPause: (Int) -> Unit,
    onReflection: (Int) -> Unit,
    onRemindersChanged: (Boolean) -> Unit,
    onInstagramChanged: (Boolean) -> Unit,
    onYoutubeChanged: (Boolean) -> Unit,
    onTiktokChanged: (Boolean) -> Unit,
    onFacebookChanged: (Boolean) -> Unit,
    onOpenVideos: () -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = statusTop + 30.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_mindscroll_logo),
                contentDescription = "MindScroll logo",
                modifier = Modifier.size(56.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.width(10.dp))
            Image(
                painter = painterResource(R.drawable.mindscroll_wordmark),
                contentDescription = "MindScroll",
                modifier = Modifier.weight(1f).height(48.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        }

        Text("Take back your attention.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)

        TodayOverviewCard(
            todayCount = todayCount,
            todayWatchMs = todayWatchMs,
            store = store,
            remindersEnabled = remindersEnabled
        )

        SupportedAppsCard(
            instagramEnabled = instagramEnabled,
            youtubeEnabled = youtubeEnabled,
            tiktokEnabled = tiktokEnabled,
            facebookEnabled = facebookEnabled,
            onInstagramChanged = onInstagramChanged,
            onYoutubeChanged = onYoutubeChanged,
            onTiktokChanged = onTiktokChanged,
            onFacebookChanged = onFacebookChanged
        )

        ProtectionCompactCard(
            serviceEnabled = serviceEnabled,
            protectionEnabled = protectionEnabled,
            onProtectionChanged = onProtectionChanged
        )

        if (!serviceEnabled) PermissionRequiredCard(onOpenAccessibility)

        SectionTitle("Focus controls")
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = serviceEnabled,
            onClick = onTestIntervention
        ) { Text("Test reminder") }

        NumberSetting(
            label = "First reminder after",
            description = "Time before the first reminder appears.",
            value = firstPause,
            suffix = "min",
            range = 1..60,
            onChange = onFirstPause
        )
        ReminderPresetRow(currentMinutes = firstPause, onSelect = onFirstPause)

        NumberSetting(
            label = "Remind me again after",
            description = "Time before the next reminder after you continue.",
            value = repeatPause,
            suffix = "min",
            range = 1..60,
            onChange = onRepeatPause
        )

        NumberSetting(
            label = "Decision countdown",
            description = "Delay before the choices appear.",
            value = reflection,
            suffix = "sec",
            range = 5..30,
            onChange = onReflection
        )

        SettingToggle(
            title = "Reminder interventions",
            subtitle = "Show the pause screen when your limit is reached.",
            checked = remindersEnabled,
            onChecked = onRemindersChanged
        )

        MotivationLibrarySummaryCard(
            library = motivationLibrary,
            refreshKey = libraryRefreshKey,
            onManage = onOpenVideos,
            onAdd = onOpenVideos
        )
    }
}

@Composable
private fun TodayOverviewCard(
    todayCount: Int,
    todayWatchMs: Long,
    store: SettingsStore,
    remindersEnabled: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF101A28),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Metric("TODAY", "$todayCount", "videos", Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(62.dp).background(Color.White.copy(alpha = 0.12f)))
                Spacer(Modifier.width(16.dp))
                Metric("SCREEN TIME", formatDuration(todayWatchMs), "short-form", Modifier.weight(1f))
            }
            FocusRing(
                todayCount = todayCount,
                todayWatchMs = todayWatchMs,
                store = store,
                remindersEnabled = remindersEnabled
            )
        }
    }
}

@Composable
private fun FocusRing(
    todayCount: Int,
    todayWatchMs: Long,
    store: SettingsStore,
    remindersEnabled: Boolean
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val session = store.loadSession()
    val intervalMs = if (session != null && session.nextInterventionAt > session.startedAt) {
        session.nextInterventionAt - session.startedAt
    } else 0L
    // Freeze the progress at the service's most recent persisted moment while the
    // user is outside a short-video surface. This avoids a fake countdown while paused.
    val effectiveNow = if (session != null) minOf(nowMs, session.lastSeenAt + 1_500L) else nowMs
    val elapsedMs = if (session != null && intervalMs > 0L) {
        (effectiveNow - session.startedAt).coerceIn(0L, intervalMs)
    } else 0L
    val progress = if (remindersEnabled && intervalMs > 0L) {
        (elapsedMs.toFloat() / intervalMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val remainingMs = (intervalMs - elapsedMs).coerceAtLeast(0L)
    val activeRecently = session != null && nowMs - session.lastSeenAt <= 2_500L

    val primary = Color(0xFF00C7FF)
    val secondary = Color(0xFF9C4DFF)
    val track = Color(0xFF1F3149)

    Box(modifier = Modifier.fillMaxWidth().height(226.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(198.dp)) {
            val stroke = 13.dp.toPx()
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            if (progress > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(primary, secondary, primary)),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$todayCount", fontSize = 40.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            Text("Videos today", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))
            Box(Modifier.width(82.dp).height(1.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)))
            Spacer(Modifier.height(7.dp))
            Text(formatDuration(todayWatchMs), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !remindersEnabled -> "Reminders off"
                    session == null || intervalMs <= 0L -> "Starts when you scroll"
                    activeRecently -> "Next pause in ${formatCountdown(remainingMs)}"
                    else -> "Paused • ${formatCountdown(remainingMs)} left"
                },
                fontSize = 10.sp,
                color = if (remindersEnabled) MaterialTheme.colorScheme.secondary else Color(0xFFFF7B83),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatCountdown(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%dh %02dm".format(hours, minutes) else "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun ProtectionCompactCard(
    serviceEnabled: Boolean,
    protectionEnabled: Boolean,
    onProtectionChanged: (Boolean) -> Unit
) {
    val active = serviceEnabled && protectionEnabled
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF101A28),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).background(
                    if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
                    CircleShape
                ),
                contentAlignment = Alignment.Center
            ) { Text("◉", color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Active Protection", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when {
                        !serviceEnabled -> "Accessibility permission required"
                        active -> "Watching for endless scrolling"
                        else -> "Protection paused"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            val view = LocalView.current
            Switch(
                checked = active,
                onCheckedChange = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onProtectionChanged(it)
                }
            )
        }
    }
}

@Composable
private fun StatsScreen(
    modifier: Modifier,
    statusTop: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    store: SettingsStore,
    refreshKey: Int
) {
    var range by remember { mutableIntStateOf(1) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = statusTop + 24.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageTitle("Stats", "Day, month and year views")
        RangeSelector(selected = range, onSelected = { range = it })
        when (range) {
            0 -> DayStatsCard(store = store, refreshKey = refreshKey)
            1 -> MonthlyActivityCard(store = store, refreshKey = refreshKey)
            else -> YearStatsCard(store = store, refreshKey = refreshKey)
        }
    }
}

@Composable
private fun RangeSelector(selected: Int, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF131C29), RoundedCornerShape(18.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        listOf("Day", "Month", "Year").forEachIndexed { index, label ->
            TextButton(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (selected == index) Color(0xFF173B65) else Color.Transparent,
                    contentColor = if (selected == index) Color(0xFF4CB7FF) else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                onClick = { onSelected(index) }
            ) { Text(label, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun DayStatsCard(store: SettingsStore, refreshKey: Int) {
    val today = LocalDate.now()
    var date by remember { mutableStateOf(today) }
    val count = remember(date, refreshKey) { store.getDayCount(date) }
    val watch = remember(date, refreshKey) { store.getDayWatchMs(date) }
    val appStats = remember(date, refreshKey) { store.getDayAppStats(date) }
    val locale = Locale.getDefault()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { date = date.minusDays(1) }) { Text("‹", fontSize = 26.sp) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEEE", locale)), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", locale)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                TextButton(enabled = date.isBefore(today), onClick = { if (date.isBefore(today)) date = date.plusDays(1) }) { Text("›", fontSize = 26.sp) }
            }
            Row(Modifier.fillMaxWidth()) {
                CompactMetric("VIDEOS", "$count", "this day", Modifier.weight(1f))
                CompactMetric("WATCH", formatDuration(watch), "this day", Modifier.weight(1f))
            }
            appStats.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF17171E), RoundedCornerShape(14.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(app.label, fontWeight = FontWeight.SemiBold)
                    Text("${app.reels} • ${formatDuration(app.watchMs)}", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun YearStatsCard(store: SettingsStore, refreshKey: Int) {
    val now = LocalDate.now()
    var year by remember { mutableIntStateOf(now.year) }
    var selectedMonth by remember { mutableIntStateOf(now.monthValue) }
    val yearStats = remember(year, refreshKey) { store.getYearStats(year) }
    val monthly = remember(yearStats) {
        (1..12).map { m ->
            val rows = yearStats.filter { it.date.monthValue == m }
            Triple(m, rows.sumOf { it.reels }, rows.sumOf { it.watchMs })
        }
    }
    val totalCount = monthly.sumOf { it.second }
    val totalWatch = monthly.sumOf { it.third }
    val selected = monthly.first { it.first == selectedMonth }
    val locale = Locale.getDefault()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Year $year", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Row {
                    TextButton(onClick = { year-- }) { Text("‹", fontSize = 24.sp) }
                    TextButton(enabled = year < now.year, onClick = { if (year < now.year) year++ }) { Text("›", fontSize = 24.sp) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                CompactMetric("VIDEOS", "$totalCount", "this year", Modifier.weight(1f))
                CompactMetric("WATCH", formatDuration(totalWatch), "this year", Modifier.weight(1f))
            }
            MonthBarChart(monthly = monthly, selectedMonth = selectedMonth, onMonthSelected = { selectedMonth = it })
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF17171E), RoundedCornerShape(14.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(Month.of(selected.first).getDisplayName(TextStyle.FULL, locale), fontWeight = FontWeight.Bold)
                Text("${selected.second} videos • ${formatDuration(selected.third)}", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MonthBarChart(
    monthly: List<Triple<Int, Int, Long>>,
    selectedMonth: Int,
    onMonthSelected: (Int) -> Unit
) {
    val maxValue = monthly.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val base = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    val selected = MaterialTheme.colorScheme.secondary
    Canvas(
        modifier = Modifier.fillMaxWidth().height(140.dp).pointerInput(monthly) {
            detectTapGestures { offset ->
                if (size.width <= 0) return@detectTapGestures
                val index = ((offset.x / size.width.toFloat()) * 12).toInt().coerceIn(0, 11)
                onMonthSelected(index + 1)
            }
        }
    ) {
        val gap = 7.dp.toPx()
        val totalGap = gap * 11
        val width = ((size.width - totalGap) / 12f).coerceAtLeast(5.dp.toPx())
        monthly.forEachIndexed { index, row ->
            val fraction = row.second.toFloat() / maxValue.toFloat()
            val height = if (row.second == 0) 3.dp.toPx() else (size.height * 0.88f * fraction).coerceAtLeast(8.dp.toPx())
            drawRoundRect(
                color = if (row.first == selectedMonth) selected else base,
                topLeft = Offset(index * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2f, width / 2f)
            )
        }
    }
}

@Composable
private fun MotivationLibraryScreen(
    modifier: Modifier,
    statusTop: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    library: MotivationLibrary,
    refreshKey: Int,
    onAddMedia: () -> Unit,
    onChanged: () -> Unit
) {
    val media = remember(refreshKey) { library.listMedia() }
    var playbackMode by remember(refreshKey) { mutableStateOf(library.playbackMode) }

    LazyColumn(
        modifier = modifier.padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = statusTop + 24.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            PageTitle("Motivational Videos", "Images and videos shown during reminders")
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF101A28),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Playback", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            MotivationLibrary.MODE_SHUFFLE to "Shuffle",
                            MotivationLibrary.MODE_IN_ORDER to "In order",
                            MotivationLibrary.MODE_FAVORITES to "Favorites"
                        ).forEach { (mode, label) ->
                            val active = playbackMode == mode
                            TextButton(
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (active) Color(0xFF173B65) else Color.Transparent,
                                    contentColor = if (active) Color(0xFF4CB7FF) else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                onClick = {
                                    playbackMode = mode
                                    library.playbackMode = mode
                                    onChanged()
                                }
                            ) { Text(label, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onAddMedia) { Text("+ Add image or video") }
                    if (library.hiddenBuiltInCount() > 0) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { library.restoreBuiltIns(); onChanged() }
                        ) { Text("Restore ${library.hiddenBuiltInCount()} removed built-in item(s)") }
                    }
                }
            }
        }
        items(media, key = { it.id }) { item ->
            MotivationMediaCard(library = library, item = item, refreshKey = refreshKey, onChanged = onChanged)
        }
        if (media.isEmpty()) {
            item { Text("No motivation media yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun MotivationMediaCard(
    library: MotivationLibrary,
    item: MotivationLibrary.VideoItem,
    refreshKey: Int,
    onChanged: () -> Unit
) {
    val thumbnail = remember(item.id, refreshKey) { library.loadThumbnail(item, 360) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF101A28),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 108.dp, height = 70.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1B2638)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(if (item.isImage) "▧" else "▶", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, fontWeight = FontWeight.Bold, maxLines = 2, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when {
                        item.builtIn -> "Built-in video"
                        item.isImage -> "My image"
                        else -> "My video"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(contentPadding = PaddingValues(horizontal = 4.dp), onClick = {
                        library.setFavorite(item.id, !item.favorite)
                        onChanged()
                    }) { Text(if (item.favorite) "★ Favorite" else "☆ Favorite", fontSize = 10.sp) }
                    TextButton(contentPadding = PaddingValues(horizontal = 4.dp), onClick = {
                        if (item.builtIn) library.removeBuiltIn(item.id) else library.deleteUserMedia(item.id)
                        onChanged()
                    }) { Text("Remove", color = Color(0xFFFF8A90), fontSize = 10.sp) }
                }
            }
            Switch(
                checked = item.enabled,
                onCheckedChange = { library.setEnabled(item.id, it); onChanged() }
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    statusTop: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    serviceEnabled: Boolean,
    videoEnabled: Boolean,
    muteDuringIntervention: Boolean,
    skipSponsoredVideos: Boolean,
    hapticsEnabled: Boolean,
    showCounter: Boolean,
    showTimer: Boolean,
    onVideoEnabled: (Boolean) -> Unit,
    onMuteDuringIntervention: (Boolean) -> Unit,
    onSkipSponsored: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onShowCounter: (Boolean) -> Unit,
    onShowTimer: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onPrivacy: () -> Unit,
    onResetStats: () -> Unit,
    onDeleteAll: () -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = statusTop + 24.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageTitle("Settings", "Keep MindScroll simple and personal")

        SectionTitle("Experience")
        SettingToggle("Motivation media", "Show an image or video during reminders.", videoEnabled, onVideoEnabled)
        SettingToggle("Pause background media", "Let motivation media take audio focus during reminders.", muteDuringIntervention, onMuteDuringIntervention)
        SettingToggle("Ignore sponsored videos", "Do not add sponsored/promoted videos to the count.", skipSponsoredVideos, onSkipSponsored)
        SettingToggle("Haptic on each video", "Soft tick when a video is counted.", hapticsEnabled, onHaptics)
        SettingToggle("Show video count", "Show today's video count in the floating bubble.", showCounter, onShowCounter)
        SettingToggle("Show timer", "Show active short-video watch time in the bubble.", showTimer, onShowTimer)

        if (!serviceEnabled) {
            SectionTitle("Accessibility")
            PermissionRequiredCard(onOpenAccessibility)
        }

        SectionTitle("About")
        InfoCard("MindScroll", "A mindful short-form video companion from Jack Labs.")

        SectionTitle("Privacy & data")
        InfoCard("Local by design", "Tracking, settings, activity and motivation media stay on this device. MindScroll has no INTERNET permission.")
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onPrivacy) { Text("Privacy & Accessibility") }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onResetStats) { Text("Reset statistics") }
        Button(
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1718)),
            onClick = onDeleteAll
        ) { Text("Delete all my data") }

        Spacer(Modifier.height(10.dp))
        Text("MindScroll V27 • MVP", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text("Created with purpose by Akash", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text("Jack Labs", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MindScrollBottomBar(
    selected: Int,
    navigationBottom: androidx.compose.ui.unit.Dp,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    data class NavItem(val iconRes: Int, val label: String)
    val items = listOf(
        NavItem(R.drawable.ic_nav_home, "Home"),
        NavItem(R.drawable.ic_nav_stats, "Stats"),
        NavItem(R.drawable.ic_nav_videos, "Videos"),
        NavItem(R.drawable.ic_nav_settings, "Settings")
    )

    val localView = LocalView.current
    val density = LocalDensity.current
    val glassShape = RoundedCornerShape(31.dp)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(selected.toFloat()) }
    var lastScrubIndex by remember { mutableIntStateOf(selected) }

    // Regular taps animate the selector to the chosen item. While the user is
    // holding and dragging, the selector follows the finger directly.
    LaunchedEffect(selected, scrubbing) {
        if (!scrubbing) scrubPosition = selected.toFloat()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = navigationBottom + 10.dp)
            .shadow(16.dp, glassShape, clip = false)
            .border(1.dp, Color.White.copy(alpha = 0.12f), glassShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xE6223145),
                        Color(0xE0142030),
                        Color(0xE80A121D)
                    )
                ),
                glassShape
            )
            .padding(6.dp)
    ) {
        // Lightweight liquid-glass highlight; intentionally no live blur.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.20f), Color.Transparent)
                    )
                )
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val itemWidth = maxWidth / items.size
            val itemWidthPx = with(density) { itemWidth.toPx() }
            val tappedOffset by animateDpAsState(
                targetValue = itemWidth * selected,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "bottomNavSelector"
            )
            val selectorOffset = if (scrubbing) itemWidth * scrubPosition else tappedOffset

            // One shared selector instead of four independent backgrounds. It
            // follows the finger continuously during a hold-and-swipe gesture.
            Box(
                modifier = Modifier
                    .offset(x = selectorOffset)
                    .width(itemWidth)
                    .height(49.dp)
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 2.dp)
                    .shadow(8.dp, RoundedCornerShape(24.dp), clip = false)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0x66309CFF), Color(0x4D8A5CFF), Color(0x3325D8D0))
                        ),
                        RoundedCornerShape(24.dp)
                    )
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.14f),
                        RoundedCornerShape(24.dp)
                    )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(itemWidthPx, items.size) {
                        // V26: immediate scrub navigation. There is deliberately no
                        // long-press timeout. The press gives feedback immediately,
                        // then the selector follows the finger on the very first move.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val maxX = (itemWidthPx * items.size - 1f).coerceAtLeast(0f)
                            val startX = down.position.x.coerceIn(0f, maxX)
                            val startIndex = (startX / itemWidthPx)
                                .toInt()
                                .coerceIn(0, items.lastIndex)

                            scrubbing = true
                            lastScrubIndex = startIndex
                            scrubPosition = ((startX / itemWidthPx) - 0.5f)
                                .coerceIn(0f, (items.size - 1).toFloat())

                            // Immediate tactile acknowledgement on touch-down instead
                            // of waiting for Android's long-press timeout.
                            localView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onSelected(startIndex)

                            var finished = false
                            while (!finished) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }

                                if (change == null || !change.pressed) {
                                    finished = true
                                } else {
                                    val x = change.position.x.coerceIn(0f, maxX)

                                    // Direct state update: no animation/filter between
                                    // the finger and the liquid selector while dragging.
                                    scrubPosition = ((x / itemWidthPx) - 0.5f)
                                        .coerceIn(0f, (items.size - 1).toFloat())

                                    val index = (x / itemWidthPx)
                                        .toInt()
                                        .coerceIn(0, items.lastIndex)

                                    if (index != lastScrubIndex) {
                                        lastScrubIndex = index
                                        localView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        onSelected(index)
                                    }

                                    // Once the gesture is moving, keep it owned by the
                                    // navigation bar so child click handlers cannot add
                                    // latency or fight the scrub gesture.
                                    change.consume()
                                }
                            }

                            scrubbing = false
                            scrubPosition = lastScrubIndex.toFloat()
                        }
                    },
                horizontalArrangement = Arrangement.Start
            ) {
                items.forEachIndexed { index, item ->
                    val active = selected == index
                    val iconColor = if (active) Color(0xFF42C6FF) else Color(0xFF8EA0B7)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                // Pointer-down haptics are handled by the parent scrub
                                // gesture so taps and drags feel identical and instant.
                                onSelected(index)
                            }
                            .padding(vertical = 7.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Image(
                            painter = painterResource(item.iconRes),
                            contentDescription = item.label,
                            modifier = Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(iconColor),
                            contentScale = ContentScale.Fit
                        )
                        Text(
                            item.label,
                            color = iconColor,
                            fontSize = 9.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    serviceEnabled: Boolean,
    protectionEnabled: Boolean,
    todayCount: Int,
    todayWatchMs: Long,
    onProtectionChanged: (Boolean) -> Unit
) {
    val active = serviceEnabled && protectionEnabled
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("PROTECTION", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            !serviceEnabled && protectionEnabled -> "Permission required"
                            !serviceEnabled -> "Off"
                            active -> "Active"
                            else -> "Paused"
                        },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            active -> MaterialTheme.colorScheme.secondary
                            !serviceEnabled && protectionEnabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                val localView = LocalView.current
                Switch(
                    checked = protectionEnabled && serviceEnabled,
                    enabled = true,
                    onCheckedChange = { value ->
                        localView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onProtectionChanged(value)
                    }
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Metric("TODAY", "$todayCount", "videos", Modifier.weight(1f))
                Metric("WATCH TIME", formatDuration(todayWatchMs), "today", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PermissionRequiredCard(onOpenAccessibility: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1724)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("!", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Accessibility is off",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "MindScroll cannot count or intervene until this permission is enabled.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenAccessibility) {
                Text("⚙  Open Accessibility settings")
            }
        }
    }
}

@Composable
private fun MonthlyActivityCard(store: SettingsStore, refreshKey: Int) {
    val now = LocalDate.now()
    var selectedYear by remember { mutableIntStateOf(now.year) }
    var selectedMonth by remember { mutableIntStateOf(now.monthValue) }
    var selectedDay by remember { mutableIntStateOf(now.dayOfMonth) }
    val month = remember(selectedYear, selectedMonth) { YearMonth.of(selectedYear, selectedMonth) }
    val stats = remember(month, refreshKey) { store.getMonthStats(month) }
    val yearStats = remember(selectedYear, refreshKey) { store.getYearStats(selectedYear) }
    val monthVideos = stats.sumOf { it.reels }
    val monthWatch = stats.sumOf { it.watchMs }
    val yearVideos = yearStats.sumOf { it.reels }
    val selected = stats.getOrNull((selectedDay - 1).coerceIn(0, (stats.size - 1).coerceAtLeast(0)))
    val locale = Locale.getDefault()
    val monthName = Month.of(selectedMonth).getDisplayName(TextStyle.FULL, locale)

    fun setMonth(value: Int) {
        selectedMonth = value.coerceIn(1, 12)
        val newMonth = YearMonth.of(selectedYear, selectedMonth)
        selectedDay = if (selectedYear == now.year && selectedMonth == now.monthValue) {
            now.dayOfMonth
        } else {
            selectedDay.coerceIn(1, newMonth.lengthOfMonth())
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$monthName $selectedYear", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        onClick = {
                            selectedYear--
                            selectedDay = selectedDay.coerceIn(1, YearMonth.of(selectedYear, selectedMonth).lengthOfMonth())
                        }
                    ) { Text("‹", fontSize = 24.sp) }
                    Text(
                        "$selectedYear",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        onClick = {
                            selectedYear++
                            selectedDay = selectedDay.coerceIn(1, YearMonth.of(selectedYear, selectedMonth).lengthOfMonth())
                        }
                    ) { Text("›", fontSize = 24.sp) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                (1..12).forEach { monthNumber ->
                    val label = Month.of(monthNumber).getDisplayName(TextStyle.SHORT, locale)
                    val selectedChip = monthNumber == selectedMonth
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selectedChip) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                            contentColor = if (selectedChip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        onClick = { setMonth(monthNumber) }
                    ) { Text(label, fontWeight = if (selectedChip) FontWeight.Bold else FontWeight.Medium) }
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                CompactMetric("MONTH", "$monthVideos", "videos", Modifier.weight(1f))
                CompactMetric("WATCH", formatDuration(monthWatch), "this month", Modifier.weight(1f))
                CompactMetric("YEAR", "$yearVideos", "videos", Modifier.weight(1f))
            }

            MonthlyBarChart(
                stats = stats,
                selectedDay = selectedDay,
                onDaySelected = { selectedDay = it }
            )

            selected?.let { stat ->
                val title = stat.date.format(DateTimeFormatter.ofPattern("EEEE, dd MMM", locale))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF17171E), RoundedCornerShape(14.dp))
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(
                        "${stat.reels} videos  •  ${formatDuration(stat.watchMs)}",
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }
            }

            selected?.let { stat ->
                val appStats = store.getDayAppStats(stat.date)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    appStats.forEach { app ->
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF17171E), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Column {
                                Text(app.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${app.reels} • ${formatDuration(app.watchMs)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Text(
                "Tap a bar to inspect a day. Counts combine Instagram Reels, YouTube Shorts, TikTok and Facebook Reels.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MonthlyBarChart(
    stats: List<SettingsStore.DailyStat>,
    selectedDay: Int,
    onDaySelected: (Int) -> Unit
) {
    if (stats.isEmpty()) return
    val maxValue = stats.maxOfOrNull { it.reels }?.coerceAtLeast(1) ?: 1
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    val selectedColor = MaterialTheme.colorScheme.secondary
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp)
            .pointerInput(stats) {
                detectTapGestures { offset ->
                    if (size.width <= 0) return@detectTapGestures
                    val index = ((offset.x / size.width.toFloat()) * stats.size)
                        .toInt()
                        .coerceIn(0, stats.lastIndex)
                    onDaySelected(index + 1)
                }
            }
    ) {
        drawLine(
            color = baselineColor,
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1.dp.toPx()
        )

        val gap = 2.dp.toPx()
        val totalGap = gap * (stats.size - 1).coerceAtLeast(0)
        val barWidth = ((size.width - totalGap) / stats.size).coerceAtLeast(2.dp.toPx())
        stats.forEachIndexed { index, stat ->
            val fraction = stat.reels.toFloat() / maxValue.toFloat()
            val barHeight = if (stat.reels == 0) 2.dp.toPx() else (size.height * 0.86f * fraction).coerceAtLeast(5.dp.toPx())
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = if (index + 1 == selectedDay) selectedColor else barColor,
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm".format(hours, minutes)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "${seconds}s"
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 27.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
        Text(unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CompactMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
        Text(unit, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun ReminderPresetRow(
    currentMinutes: Int,
    onSelect: (Int) -> Unit
) {
    val presets = listOf(5, 10, 15, 20, 30, 60, 120)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (currentMinutes in presets) "Quick preset" else "Quick preset • Custom ${formatMinutes(currentMinutes)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { minutes ->
                val selected = currentMinutes == minutes
                TextButton(
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    onClick = { onSelect(minutes) }
                ) { Text(formatMinutes(minutes), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
            }
        }
    }
}

private fun formatMinutes(minutes: Int): String = when {
    minutes == 60 -> "1h"
    minutes == 120 -> "2h"
    minutes > 60 && minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes}m"
}

@Composable
private fun NumberSetting(
    label: String,
    description: String,
    value: Int,
    suffix: String,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    val localView = LocalView.current
    var sliderValue by remember(label, value) { mutableIntStateOf(value.coerceIn(range)) }
    var dragging by remember(label) { mutableStateOf(false) }
    var lastHapticValue by remember(label, value) { mutableIntStateOf(value.coerceIn(range)) }
    val displayedValue = if (dragging) sliderValue else value

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (suffix == "min") formatMinutes(displayedValue) else "$displayedValue $suffix",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Slider(
                value = sliderValue.toFloat(),
                onValueChange = { raw ->
                    dragging = true
                    val newValue = raw.toInt().coerceIn(range)
                    sliderValue = newValue
                    if (newValue != lastHapticValue) {
                        localView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        lastHapticValue = newValue
                    }
                },
                onValueChangeFinished = {
                    dragging = false
                    if (sliderValue != value) onChange(sliderValue)
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0)
            )
        }
    }
}

@Composable
private fun SupportedAppsCard(
    instagramEnabled: Boolean,
    youtubeEnabled: Boolean,
    tiktokEnabled: Boolean,
    facebookEnabled: Boolean,
    onInstagramChanged: (Boolean) -> Unit,
    onYoutubeChanged: (Boolean) -> Unit,
    onTiktokChanged: (Boolean) -> Unit,
    onFacebookChanged: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            PlatformToggle(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.ic_instagram,
                label = "Instagram",
                enabled = instagramEnabled,
                onChanged = onInstagramChanged
            )
            PlatformToggle(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.ic_youtube_shorts,
                label = "Shorts",
                enabled = youtubeEnabled,
                onChanged = onYoutubeChanged
            )
            PlatformToggle(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.ic_tiktok,
                label = "TikTok",
                enabled = tiktokEnabled,
                onChanged = onTiktokChanged
            )
            PlatformToggle(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.ic_facebook,
                label = "Facebook",
                enabled = facebookEnabled,
                onChanged = onFacebookChanged
            )
        }
    }
}

@Composable
private fun PlatformToggle(
    modifier: Modifier,
    iconRes: Int,
    label: String,
    enabled: Boolean,
    onChanged: (Boolean) -> Unit
) {
    val localView = LocalView.current
    val shape = RoundedCornerShape(17.dp)
    val activeBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.90f)
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    val container = if (enabled) Color(0xFF261D34) else Color(0xFF17171E)

    Column(
        modifier = modifier
            .border(
                width = if (enabled) 1.dp else 0.dp,
                color = if (enabled) activeBorder else Color.Transparent,
                shape = shape
            )
            .background(container, shape)
            .clickable {
                localView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onChanged(!enabled)
            }
            .padding(horizontal = 3.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color(0xFF25252B),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier
                    .size(36.dp)
                    .alpha(if (enabled) 1f else 0.42f),
                contentScale = ContentScale.Fit,
                colorFilter = if (enabled) null else ColorFilter.tint(Color(0xFF8D8A96))
            )
        }
        Text(
            label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else inactive,
            fontSize = 9.sp,
            fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            val localView = LocalView.current
            Switch(
                checked = checked,
                onCheckedChange = { value ->
                    localView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onChecked(value)
                }
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16141F)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun MotivationLibrarySummaryCard(
    library: MotivationLibrary,
    refreshKey: Int,
    onManage: () -> Unit,
    onAdd: () -> Unit
) {
    val videos = remember(refreshKey) { library.listVideos() }
    val enabled = videos.count { it.enabled }
    val custom = videos.count { !it.builtIn }
    val builtInCount = remember(refreshKey) { library.builtInCount() }
    val modeLabel = when (library.playbackMode) {
        MotivationLibrary.MODE_IN_ORDER -> "In order"
        MotivationLibrary.MODE_FAVORITES -> "Favorites"
        else -> "Shuffle"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16141F)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Motivation Library", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "$enabled enabled • $custom personal • $modeLabel",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("${videos.size}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                }
            }
            Text(
                "MindScroll includes $builtInCount offline motivation videos. Add your own images or videos privately, hide built-ins you do not want, and choose what can appear during reminders.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onAdd) { Text("+ Add media") }
                Button(modifier = Modifier.weight(1f), onClick = onManage) { Text("Manage") }
            }
        }
    }
}

@Composable
private fun ThinDivider(alpha: Float = 0.12f) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    )
}

@Composable
private fun MotivationLibraryDialog(
    library: MotivationLibrary,
    refreshKey: Int,
    onAddVideo: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val videos = remember(refreshKey) { library.listVideos() }
    var playbackMode by remember(refreshKey) { mutableStateOf(library.playbackMode) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101015)),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Motivation Library", fontSize = 23.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        Text("Offline • private • user controlled", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }

                Text("Playback", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        MotivationLibrary.MODE_SHUFFLE to "Shuffle",
                        MotivationLibrary.MODE_IN_ORDER to "In order",
                        MotivationLibrary.MODE_FAVORITES to "Favorites only"
                    ).forEach { (mode, label) ->
                        val selected = playbackMode == mode
                        TextButton(
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            onClick = {
                                playbackMode = mode
                                library.playbackMode = mode
                                onChanged()
                            }
                        ) { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
                    }
                }

                Button(modifier = Modifier.fillMaxWidth(), onClick = onAddVideo) {
                    Text("+ Add image or video")
                }
                Text(
                    "Android’s media picker gives MindScroll access only to the image or video you choose. MindScroll copies it into private app storage so no broad gallery permission is needed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                ThinDivider(alpha = 0.15f)

                if (library.hiddenBuiltInCount() > 0) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            library.restoreBuiltIns()
                            onChanged()
                        }
                    ) {
                        Text("Restore ${library.hiddenBuiltInCount()} removed built-in item(s)")
                    }
                }

                videos.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (item.builtIn) "Built-in video" else if (item.isImage) "My image" else "My video",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp
                            )
                        }
                        TextButton(onClick = {
                            library.setFavorite(item.id, !item.favorite)
                            onChanged()
                        }) {
                            Text(if (item.favorite) "★" else "☆", fontSize = 22.sp)
                        }
                        Switch(
                            checked = item.enabled,
                            onCheckedChange = { value ->
                                library.setEnabled(item.id, value)
                                onChanged()
                            }
                        )
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (item.builtIn) library.removeBuiltIn(item.id)
                            else library.deleteUserMedia(item.id)
                            onChanged()
                        }
                    ) {
                        Text(
                            if (item.builtIn) "Remove built-in media" else "Remove personal media",
                            color = Color(0xFFFF8A90)
                        )
                    }
                    if (index != videos.lastIndex) {
                        ThinDivider(alpha = 0.08f)
                    }
                }

                if (videos.isEmpty()) {
                    Text("No motivation media is enabled yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
