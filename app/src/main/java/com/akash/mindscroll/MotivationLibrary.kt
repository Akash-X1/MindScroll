package com.akash.mindscroll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Local-only motivation media library.
 *
 * Built-in videos live in res/raw. User-selected images/videos are copied into
 * app-private internal storage so MindScroll never needs broad gallery access.
 */
class MotivationLibrary(private val context: Context) {
    companion object {
        private const val PREFS = "mindscroll_motivation_library"
        private const val KEY_ENABLED = "enabled_ids"
        private const val KEY_FAVORITES = "favorite_ids"
        private const val KEY_HIDDEN_BUILTINS = "hidden_builtin_ids"
        private const val KEY_KNOWN_BUILTINS = "known_builtin_ids_v22"
        private const val KEY_MODE = "playback_mode"
        private const val KEY_LAST_PLAYED = "last_played_id"
        // Keep the original directory name for backwards compatibility with V19 imports.
        private const val USER_DIR = "motivation_videos"
        private const val MAX_IMPORT_BYTES = 200L * 1024L * 1024L

        const val MODE_SHUFFLE = "shuffle"
        const val MODE_IN_ORDER = "in_order"
        const val MODE_FAVORITES = "favorites"

        const val TYPE_VIDEO = "video"
        const val TYPE_IMAGE = "image"
    }

    data class VideoItem(
        val id: String,
        val title: String,
        val builtIn: Boolean,
        val rawName: String? = null,
        val file: File? = null,
        val enabled: Boolean = true,
        val favorite: Boolean = false,
        val mediaType: String = TYPE_VIDEO
    ) {
        val isVideo: Boolean get() = mediaType == TYPE_VIDEO
        val isImage: Boolean get() = mediaType == TYPE_IMAGE
    }

    data class ImportResult(val success: Boolean, val message: String, val item: VideoItem? = null)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val userDir: File = File(context.filesDir, USER_DIR).apply { mkdirs() }
    private val thumbnailCache = object : LruCache<String, Bitmap>(24) {}

    private val defaultBuiltInTitles = listOf(
        "The feed can wait",
        "Choose your next action",
        "Protect your attention",
        "Five focused minutes",
        "Your time is finite",
        "Future you",
        "Pause and choose",
        "The feed never ends",
        "Pick your life back up",
        "Enough for now",
        "Choose what matters next"
    )

    /**
     * V22 supports a user-supplied built-in pack named motivational_01.mp4,
     * motivational_02.mp4, ... placed in res/raw.
     *
     * If at least one motivational_* resource exists, MindScroll uses that
     * series exclusively. Otherwise it falls back to the older motivation_*
     * files bundled with previous MVP builds.
     */
    private val builtIns: List<Triple<String, String, String>> by lazy {
        val preferred = discoverBuiltIns("motivational")
        if (preferred.isNotEmpty()) preferred else discoverBuiltIns("motivation")
    }

    private fun discoverBuiltIns(prefix: String): List<Triple<String, String, String>> {
        return (1..30).mapNotNull { index ->
            val number = index.toString().padStart(2, '0')
            val rawName = "${prefix}_$number"
            val rawId = context.resources.getIdentifier(rawName, "raw", context.packageName)
            if (rawId == 0) {
                null
            } else {
                val title = defaultBuiltInTitles.getOrNull(index - 1) ?: "Motivation $number"
                Triple("builtin_$number", title, rawName)
            }
        }
    }

    var playbackMode: String
        get() = prefs.getString(KEY_MODE, MODE_SHUFFLE) ?: MODE_SHUFFLE
        set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    fun listVideos(): List<VideoItem> = listMedia()

    fun listMedia(): List<VideoItem> {
        val enabled = enabledIdsOrDefault()
        val favorites = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        val hiddenBuiltIns = hiddenBuiltInIds()

        val builtInItems = builtIns
            .filterNot { (id, _, _) -> id in hiddenBuiltIns }
            .map { (id, title, rawName) ->
                VideoItem(
                    id = id,
                    title = title,
                    builtIn = true,
                    rawName = rawName,
                    enabled = id in enabled,
                    favorite = id in favorites,
                    mediaType = TYPE_VIDEO
                )
            }

        val userItems = userDir.listFiles()
            ?.filter {
                it.isFile &&
                    !it.name.endsWith(".title", ignoreCase = true) &&
                    !it.name.endsWith(".type", ignoreCase = true)
            }
            ?.sortedBy { it.name.lowercase() }
            ?.map { file ->
                val id = "user_${file.nameWithoutExtension}"
                VideoItem(
                    id = id,
                    title = readUserTitle(file),
                    builtIn = false,
                    file = file,
                    enabled = id in enabled,
                    favorite = id in favorites,
                    mediaType = readMediaType(file)
                )
            }
            .orEmpty()

        return builtInItems + userItems
    }

    /** Small local preview used by the Videos tab. Nothing leaves the device. */
    fun loadThumbnail(item: VideoItem, targetPx: Int = 360): Bitmap? {
        thumbnailCache.get(item.id)?.let { return it }
        val source = runCatching {
            if (item.isImage) {
                item.file?.absolutePath?.let { BitmapFactory.decodeFile(it) }
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    if (item.builtIn && item.rawName != null) {
                        val rawId = context.resources.getIdentifier(item.rawName, "raw", context.packageName)
                        if (rawId == 0) return@runCatching null
                        context.resources.openRawResourceFd(rawId)?.use { afd ->
                            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        } ?: return@runCatching null
                    } else {
                        val path = item.file?.absolutePath ?: return@runCatching null
                        retriever.setDataSource(path)
                    }
                    retriever.getFrameAtTime(250_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime()
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }.getOrNull() ?: return null

        val scaled = runCatching {
            val w = source.width.coerceAtLeast(1)
            val h = source.height.coerceAtLeast(1)
            val scale = minOf(1f, targetPx.toFloat() / maxOf(w, h).toFloat())
            if (scale >= 0.999f) source else Bitmap.createScaledBitmap(
                source,
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1),
                true
            )
        }.getOrDefault(source)
        thumbnailCache.put(item.id, scaled)
        return scaled
    }

    fun clearThumbnailCache() = thumbnailCache.evictAll()

    fun setEnabled(id: String, value: Boolean) {
        val set = enabledIdsOrDefault().toMutableSet()
        if (value) set += id else set -= id
        prefs.edit().putStringSet(KEY_ENABLED, set).apply()
    }

    fun setFavorite(id: String, value: Boolean) {
        val set = (prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()).toMutableSet()
        if (value) set += id else set -= id
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply()
    }

    /** Built-in media cannot be physically deleted from the APK, so "Remove" hides it. */
    fun removeBuiltIn(id: String): Boolean {
        if (builtIns.none { it.first == id }) return false
        val hidden = hiddenBuiltInIds().toMutableSet().apply { add(id) }
        val enabled = enabledIdsOrDefault().toMutableSet().apply { remove(id) }
        val favorites = (prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()).toMutableSet().apply { remove(id) }
        prefs.edit()
            .putStringSet(KEY_HIDDEN_BUILTINS, hidden)
            .putStringSet(KEY_ENABLED, enabled)
            .putStringSet(KEY_FAVORITES, favorites)
            .apply()
        thumbnailCache.remove(id)
        return true
    }

    fun restoreBuiltIns() {
        val enabled = enabledIdsOrDefault().toMutableSet().apply { addAll(builtIns.map { it.first }) }
        prefs.edit()
            .remove(KEY_HIDDEN_BUILTINS)
            .putStringSet(KEY_ENABLED, enabled)
            .apply()
    }

    fun hiddenBuiltInCount(): Int = hiddenBuiltInIds().size

    fun builtInCount(): Int = builtIns.size

    fun deleteUserVideo(id: String): Boolean = deleteUserMedia(id)

    fun deleteUserMedia(id: String): Boolean {
        if (!id.startsWith("user_")) return false
        val item = listMedia().firstOrNull { it.id == id && !it.builtIn } ?: return false
        val file = item.file ?: return false
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        File(userDir, file.name + ".title").delete()
        File(userDir, file.name + ".type").delete()
        val enabled = enabledIdsOrDefault().toMutableSet().apply { remove(id) }
        val favorites = (prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()).toMutableSet().apply { remove(id) }
        prefs.edit().putStringSet(KEY_ENABLED, enabled).putStringSet(KEY_FAVORITES, favorites).apply()
        thumbnailCache.remove(id)
        return deleted
    }

    fun importVideo(uri: Uri): ImportResult = importMedia(uri)

    fun importMedia(uri: Uri): ImportResult {
        return runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri).orEmpty().lowercase()
            val displayName = queryDisplayName(uri)?.take(120)?.ifBlank { null }
            val mediaType = when {
                mime.startsWith("image/") -> TYPE_IMAGE
                mime.startsWith("video/") -> TYPE_VIDEO
                else -> inferMediaType(displayName.orEmpty())
            } ?: return ImportResult(false, "Please choose an image or video file.")

            val declaredLength = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            if (declaredLength > MAX_IMPORT_BYTES) {
                return ImportResult(false, "File is too large. Please choose media under 200 MB.")
            }

            val safeTitle = displayName ?: if (mediaType == TYPE_IMAGE) "My motivation image" else "My motivation video"
            val extension = extensionFor(mime, safeTitle, mediaType)
            val token = UUID.randomUUID().toString().replace("-", "")
            val outFile = File(userDir, "$token.$extension")
            var copied = 0L

            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        copied += read
                        if (copied > MAX_IMPORT_BYTES) {
                            throw IllegalArgumentException("File is too large. Please choose media under 200 MB.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return ImportResult(false, "MindScroll could not open that media file.")

            if (copied <= 0L) {
                outFile.delete()
                return ImportResult(false, "The selected media was empty or unavailable.")
            }

            writeUserTitle(outFile, safeTitle)
            writeMediaType(outFile, mediaType)
            val id = "user_${outFile.nameWithoutExtension}"
            val enabled = enabledIdsOrDefault().toMutableSet().apply { add(id) }
            prefs.edit().putStringSet(KEY_ENABLED, enabled).apply()
            val item = VideoItem(
                id = id,
                title = safeTitle,
                builtIn = false,
                file = outFile,
                enabled = true,
                mediaType = mediaType
            )
            ImportResult(true, if (mediaType == TYPE_IMAGE) "Image added to Motivation Library" else "Video added to Motivation Library", item)
        }.getOrElse { error ->
            ImportResult(false, error.message ?: "Could not import that media file.")
        }
    }

    /** Selects enabled media without immediately repeating the previous item when possible. */
    fun chooseNextVideo(): VideoItem? = chooseNextMedia()

    fun chooseNextMedia(): VideoItem? {
        val enabled = listMedia().filter { it.enabled }
        if (enabled.isEmpty()) return null
        val favorites = enabled.filter { it.favorite }
        val pool = when (playbackMode) {
            MODE_FAVORITES -> favorites.ifEmpty { enabled }
            else -> enabled
        }

        val lastId = prefs.getString(KEY_LAST_PLAYED, null)
        val candidates = if (pool.size > 1) pool.filter { it.id != lastId } else pool
        val selected = when (playbackMode) {
            MODE_IN_ORDER -> {
                val lastIndex = pool.indexOfFirst { it.id == lastId }
                pool[(lastIndex + 1).coerceAtLeast(0) % pool.size]
            }
            else -> candidates.random()
        }
        prefs.edit().putString(KEY_LAST_PLAYED, selected.id).apply()
        return selected
    }

    fun uriFor(item: VideoItem): Uri? {
        return if (item.builtIn) {
            val rawName = item.rawName ?: return null
            val rawId = context.resources.getIdentifier(rawName, "raw", context.packageName)
            if (rawId == 0) null else Uri.parse("android.resource://${context.packageName}/$rawId")
        } else {
            item.file?.takeIf { it.exists() }?.let { Uri.fromFile(it) }
        }
    }

    fun deleteAllUserVideos() = deleteAllUserMedia()

    fun deleteAllUserMedia() {
        userDir.listFiles()?.forEach { runCatching { it.delete() } }
        prefs.edit().clear().apply()
    }

    fun resetLibraryPreferences() {
        prefs.edit().clear().apply()
    }

    private fun enabledIdsOrDefault(): Set<String> {
        val stored = prefs.getStringSet(KEY_ENABLED, null)
        if (stored != null) {
            // V22 migration: automatically enable newly added built-in media once,
            // without re-enabling items the user had already disabled/removed.
            val currentBuiltIns = builtIns.map { it.first }.toSet()
            val legacyKnown = (1..10).map { "builtin_${it.toString().padStart(2, '0')}" }.toSet()
            val known = prefs.getStringSet(KEY_KNOWN_BUILTINS, null) ?: legacyKnown
            val hidden = hiddenBuiltInIds()
            val newlyDiscovered = currentBuiltIns - known
            val merged = stored.toMutableSet().apply {
                addAll(newlyDiscovered.filterNot { it in hidden })
            }
            if (newlyDiscovered.isNotEmpty() || prefs.getStringSet(KEY_KNOWN_BUILTINS, null) == null) {
                prefs.edit()
                    .putStringSet(KEY_ENABLED, merged)
                    .putStringSet(KEY_KNOWN_BUILTINS, currentBuiltIns)
                    .apply()
            }
            return merged
        }
        val defaults = builtIns.map { it.first }.toMutableSet()
        userDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".title") && !it.name.endsWith(".type") }
            ?.forEach { defaults += "user_${it.nameWithoutExtension}" }
        prefs.edit().putStringSet(KEY_KNOWN_BUILTINS, builtIns.map { it.first }.toSet()).apply()
        return defaults
    }

    private fun hiddenBuiltInIds(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_BUILTINS, emptySet()) ?: emptySet()

    private fun inferMediaType(name: String): String? {
        val lower = name.lowercase()
        return when {
            listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif").any { lower.endsWith(it) } -> TYPE_IMAGE
            listOf(".mp4", ".webm", ".3gp", ".mov", ".m4v", ".mkv").any { lower.endsWith(it) } -> TYPE_VIDEO
            else -> null
        }
    }

    private fun extensionFor(mime: String, name: String, mediaType: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }
        if (fromName != null) return fromName
        return if (mediaType == TYPE_IMAGE) {
            when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("heic") || mime.contains("heif") -> "heic"
                mime.contains("gif") -> "gif"
                else -> "jpg"
            }
        } else {
            when {
                mime.contains("webm") -> "webm"
                mime.contains("3gpp") -> "3gp"
                mime.contains("quicktime") -> "mov"
                mime.contains("matroska") -> "mkv"
                else -> "mp4"
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    private fun writeUserTitle(file: File, title: String) {
        val encoded = Base64.encodeToString(title.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        File(userDir, file.name + ".title").writeText(encoded)
    }

    private fun readUserTitle(file: File): String {
        val sidecar = File(userDir, file.name + ".title")
        return runCatching {
            val encoded = sidecar.takeIf { it.exists() }?.readText().orEmpty()
            if (encoded.isBlank()) file.nameWithoutExtension
            else String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault(file.nameWithoutExtension)
    }

    private fun writeMediaType(file: File, mediaType: String) {
        File(userDir, file.name + ".type").writeText(mediaType)
    }

    private fun readMediaType(file: File): String {
        val sidecar = File(userDir, file.name + ".type")
        val stored = runCatching { sidecar.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()
        if (stored == TYPE_IMAGE || stored == TYPE_VIDEO) return stored
        return inferMediaType(file.name) ?: TYPE_VIDEO
    }
}
