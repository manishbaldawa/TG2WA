package com.bridge.telewa

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * TeleWaFloatingBridge
 *
 * Manual Floating Share Button & Android Share-Sheet Bridge between:
 *  - Graph Messenger (ir.ilmili.telegraph)
 *  - WhatsApp (com.whatsapp)
 *
 * Features:
 *  1. Floating Overlay Button (FloatingShareService):
 *     - Displays a draggable floating share button when Graph Messenger or WhatsApp is open.
 *     - Single-tap captures the visible/selected post caption, triggers the host app's native
 *       Share action, and routes both media (EXTRA_STREAM) and caption (EXTRA_TEXT) directly
 *       into the opposite app.
 *     - Long-press manually toggles direction (TG -> WA vs WA -> TG).
 *  2. Smart Caption Preservation (PostBridgeAccessibilityService):
 *     - Captures captions from selected messages before WhatsApp or Graph Messenger strips them
 *       during media sharing.
 *  3. Re-hosted FileProvider Stream Router (ShareBridgeActivity):
 *     - Re-streams transient content:// URIs through local cache so neither WhatsApp nor
 *       Graph Messenger throws a SecurityException on cross-package URI access.
 */
object BridgeState {
    const val PKG_GRAPH_MESSENGER = "ir.ilmili.telegraph"
    const val PKG_WHATSAPP = "com.whatsapp"

    @Volatile
    var activeForegroundPackage: String = PKG_GRAPH_MESSENGER

    @Volatile
    var lastCapturedCaption: String = ""

    @Volatile
    var lastCaptionTimestampMs: Long = 0L

    @Volatile
    var pendingAutoIntercept: Boolean = false

    fun recordCaption(text: String) {
        val cleaned = text.trim()
        if (cleaned.isNotEmpty()) {
            lastCapturedCaption = cleaned
            lastCaptionTimestampMs = System.currentTimeMillis()
        }
    }

    fun getFreshCaption(maxAgeMs: Long = 30_000L): String? {
        val age = System.currentTimeMillis() - lastCaptionTimestampMs
        return if (lastCapturedCaption.isNotBlank() && age <= maxAgeMs) {
            lastCapturedCaption
        } else {
            null
        }
    }
}

/**
 * 1. Main Setup & Permission Launcher Activity
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val title = TextView(this).apply {
            text = "Graph Messenger ⇄ WhatsApp Bridge"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 24)
        }

        val subtitle = TextView(this).apply {
            text = "Share posts (photo/video + caption) manually via a floating button between " +
                "Graph Messenger (ir.ilmili.telegraph) and WhatsApp (com.whatsapp)."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, 48)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Grant Floating Button Overlay Permission"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(context, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "2. Enable Caption & Share Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnStartBubble = Button(this).apply {
            text = "3. Start Floating Share Button"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(context, "Please grant Overlay Permission first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val serviceIntent = Intent(this@MainActivity, FloatingShareService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(context, "Floating Share Button active!", Toast.LENGTH_SHORT).show()
            }
        }

        val btnStopBubble = Button(this).apply {
            text = "Stop Floating Share Button"
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingShareService::class.java))
                Toast.makeText(context, "Floating Share Button stopped.", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(btnOverlay)
        root.addView(btnAccessibility)
        root.addView(btnStartBubble)
        root.addView(btnStopBubble)
        setContentView(root)
    }
}

/**
 * 2. Floating Share Button Overlay Service
 *
 * Renders a draggable floating button on screen:
 *  - Single Tap: Captures the caption from the selected post or clipboard and triggers Share
 *    to the opposite app (ir.ilmili.telegraph <-> com.whatsapp).
 *  - Long Press: Manually switches target direction (TG -> WA vs WA -> TG).
 */
class FloatingShareService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "telewa_floating_bridge"
        private const val NOTIFICATION_ID = 1001
        var instance: FloatingShareService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotification()
        createFloatingBubble()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Share Bridge",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TG ⇄ WA Floating Share Active")
                .setContentText("Tap floating button after selecting a post in Graph Messenger or WhatsApp")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TG ⇄ WA Floating Share Active")
                .setContentText("Tap floating button to cross-share post + caption")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .build()
        }

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val bubble = TextView(this).apply {
            text = "TG ➔ WA"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(28, 20, 28, 20)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 64f
                setColor(Color.parseColor("#10B981"))
                setStroke(3, Color.WHITE)
            }
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 420
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchStartTime = 0L

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    val duration = System.currentTimeMillis() - touchStartTime
                    if (dx < 18 && dy < 18) {
                        if (duration >= 600L) {
                            toggleDirectionManually()
                        } else {
                            onFloatingButtonTapped()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = bubble
        windowManager.addView(bubble, params)
        refreshBubbleAppearance()
    }

    fun refreshBubbleAppearance() {
        mainHandler.post {
            val bubble = floatingView ?: return@post
            val isGraphActive = BridgeState.activeForegroundPackage == BridgeState.PKG_GRAPH_MESSENGER
            val bg = bubble.background as? GradientDrawable
            if (isGraphActive) {
                bubble.text = "TG ➔ WA"
                bg?.setColor(Color.parseColor("#10B981")) // WhatsApp Emerald
            } else {
                bubble.text = "WA ➔ TG"
                bg?.setColor(Color.parseColor("#0284C7")) // Telegram Sky Blue
            }
        }
    }

    private fun toggleDirectionManually() {
        BridgeState.activeForegroundPackage =
            if (BridgeState.activeForegroundPackage == BridgeState.PKG_GRAPH_MESSENGER) {
                BridgeState.PKG_WHATSAPP
            } else {
                BridgeState.PKG_GRAPH_MESSENGER
            }
        refreshBubbleAppearance()
        val targetLabel = if (BridgeState.activeForegroundPackage == BridgeState.PKG_GRAPH_MESSENGER) {
            "Graph Messenger ➔ WhatsApp"
        } else {
            "WhatsApp ➔ Graph Messenger"
        }
        Toast.makeText(this, "Mode switched: $targetLabel", Toast.LENGTH_SHORT).show()
    }

    private fun onFloatingButtonTapped() {
        // 1. Check clipboard fallback and store if fresh
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!clipText.isNullOrBlank() && BridgeState.getFreshCaption() == null) {
            BridgeState.recordCaption(clipText)
        }

        // 2. Ask the Accessibility Service to capture the visible post caption & click Share
        val a11yService = PostBridgeAccessibilityService.instance
        if (a11yService != null) {
            val triggered = a11yService.captureCaptionAndTriggerShare()
            if (triggered) {
                Toast.makeText(
                    this,
                    "Caption captured — select 'TG⇄WA Bridge' in Share sheet...",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }

        // 3. Fallback: If user only copied text/caption without media, share text directly to target app
        val captionOnly = BridgeState.getFreshCaption() ?: clipText
        if (!captionOnly.isNullOrBlank()) {
            val targetPkg = if (BridgeState.activeForegroundPackage == BridgeState.PKG_GRAPH_MESSENGER) {
                BridgeState.PKG_WHATSAPP
            } else {
                BridgeState.PKG_GRAPH_MESSENGER
            }
            val directTextIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, captionOnly)
                setPackage(targetPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(directTextIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Target app ($targetPkg) not installed.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(
                this,
                "Select a post first (or tap Share -> TG⇄WA Bridge) to forward media + caption.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        floatingView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * 3. Accessibility Service for Caption Extraction & 1-Tap Share Trigger
 *
 * Watches `ir.ilmili.telegraph` and `com.whatsapp`:
 *  - Automatically updates the Floating Button direction when switching between the two apps.
 *  - Extracts the selected post's caption before native Share strips it.
 *  - Clicks the native Share button when the user taps the Floating Button.
 */
class PostBridgeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PostBridgeAccessibilityService? = null
        private val TIMESTAMP_REGEX = Regex("^\\d{1,2}:\\d{2}(\\s?[APap][Mm])?$")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        if (pkg == BridgeState.PKG_GRAPH_MESSENGER || pkg == BridgeState.PKG_WHATSAPP) {
            if (BridgeState.activeForegroundPackage != pkg) {
                BridgeState.activeForegroundPackage = pkg
                FloatingShareService.instance?.refreshBubbleAppearance()
            }
            // Passively scan selected message text whenever selection changes
            rootInActiveWindow?.let { root ->
                val extracted = findLongestMessageCaption(root, pkg)
                if (!extracted.isNullOrBlank()) {
                    BridgeState.recordCaption(extracted)
                }
            }
        }
    }

    /**
     * Called when user taps the Floating Share Button.
     * 1. Extracts the caption from the currently selected/visible message.
     * 2. Finds and clicks the native "Share" action button in WhatsApp or Graph Messenger.
     */
    fun captureCaptionAndTriggerShare(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: BridgeState.activeForegroundPackage

        val caption = findLongestMessageCaption(root, pkg)
        if (!caption.isNullOrBlank()) {
            BridgeState.recordCaption(caption)
            // Also copy caption to clipboard as a reliable backup
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("post_caption", caption))
        }

        // Look for a visible "Share" button on the action bar, bottom bar, or overflow menu
        val shareNode = findShareActionNode(root)
        if (shareNode != null) {
            BridgeState.pendingAutoIntercept = true
            return shareNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    private fun findLongestMessageCaption(root: AccessibilityNodeInfo, pkg: String): String? {
        val candidates = mutableListOf<String>()
        val ignoredLabels = setOf(
            "Share", "Forward", "Reply", "Copy", "Delete", "Info",
            "Message", "Type a message", "Search", "Mute", "Star"
        )

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val viewId = node.viewIdResourceName ?: ""
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            // Prioritize WhatsApp caption/message text IDs
            if (pkg == BridgeState.PKG_WHATSAPP &&
                (viewId.endsWith(":id/caption") || viewId.endsWith(":id/message_text"))
            ) {
                if (!text.isNullOrBlank()) candidates.add(text)
            }

            // Graph Messenger / Telegram custom-drawn chat cells expose message text in text or contentDescription
            if (!text.isNullOrBlank() && text.length > 3 && text !in ignoredLabels) {
                if (!TIMESTAMP_REGEX.matches(text)) {
                    candidates.add(text)
                }
            } else if (pkg == BridgeState.PKG_GRAPH_MESSENGER && !desc.isNullOrBlank() && desc.length > 5) {
                val cleanedDesc = cleanTelegramContentDescription(desc)
                if (cleanedDesc.isNotBlank() && cleanedDesc !in ignoredLabels) {
                    candidates.add(cleanedDesc)
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return candidates.maxByOrNull { it.length }
    }

    private fun cleanTelegramContentDescription(raw: String): String {
        // Graph Messenger contentDescription on media cells often starts with "Photo, " or "Video, "
        // and ends with timestamp metadata like ", Received at 17:10"
        return raw
            .replace(Regex("^(Photo|Video|GIF|Sticker|File),\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",?\\s*(Received|Sent|Viewed).*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun findShareActionNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val shareKeywords = listOf("Share", "share")
        for (keyword in shareKeywords) {
            val matches = root.findAccessibilityNodeInfosByText(keyword)
            for (node in matches) {
                if (node.isClickable) return node
                if (node.parent?.isClickable == true) return node.parent
            }
        }
        // Fallback: check contentDescription for "Share"
        fun searchDesc(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.equals("Share", ignoreCase = true)) {
                return if (node.isClickable) node else node.parent
            }
            for (i in 0 until node.childCount) {
                val found = searchDesc(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        return searchDesc(root)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}

/**
 * 4. Share Sheet Receiver & Cross-App Router Activity
 *
 * Appears in the Android Share Menu as "TG ⇄ WA Bridge (With Caption)".
 * When a post is shared from Graph Messenger (`ir.ilmili.telegraph`) or WhatsApp (`com.whatsapp`):
 *  1. Collects single (`ACTION_SEND`) or multi-media (`ACTION_SEND_MULTIPLE`) stream URIs.
 *  2. Recovers the caption from `EXTRA_TEXT`, Accessibility cache, or Clipboard.
 *  3. Copies media files into `cacheDir/shared_posts/` and wraps them with `FileProvider`
 *     so the receiving app has full read permission.
 *  4. Launches the opposite app (`com.whatsapp` or `ir.ilmili.telegraph`) with both media and caption.
 */
class ShareBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingShare(intent)
        finish()
    }

    private fun handleIncomingShare(incoming: Intent?) {
        if (incoming == null) return

        // Determine source -> target direction
        val referrerPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            referrer?.host
        } else {
            null
        }
        val sourcePkg = when {
            referrerPkg == BridgeState.PKG_GRAPH_MESSENGER -> BridgeState.PKG_GRAPH_MESSENGER
            referrerPkg == BridgeState.PKG_WHATSAPP -> BridgeState.PKG_WHATSAPP
            else -> BridgeState.activeForegroundPackage
        }
        val targetPkg = if (sourcePkg == BridgeState.PKG_GRAPH_MESSENGER) {
            BridgeState.PKG_WHATSAPP
        } else {
            BridgeState.PKG_GRAPH_MESSENGER
        }

        // Resolve caption: 1) Intent EXTRA_TEXT, 2) Accessibility captured caption, 3) Clipboard
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        val caption = incoming.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: BridgeState.getFreshCaption()
            ?: clipText?.takeIf { it.isNotBlank() }
            ?: ""

        // Keep caption in clipboard as an extra convenience
        if (caption.isNotBlank()) {
            clipboard.setPrimaryClip(ClipData.newPlainText("post_caption", caption))
        }

        val mimeType = incoming.type ?: "*/*"

        when (incoming.action) {
            Intent.ACTION_SEND -> {
                val streamUri = getStreamUri(incoming)
                if (streamUri != null) {
                    val rehostedUri = rehostUriToCache(streamUri, mimeType) ?: streamUri
                    launchTargetShare(
                        targetPkg = targetPkg,
                        action = Intent.ACTION_SEND,
                        mimeType = mimeType,
                        singleUri = rehostedUri,
                        multiUris = null,
                        caption = caption
                    )
                } else if (caption.isNotBlank()) {
                    launchTargetShare(
                        targetPkg = targetPkg,
                        action = Intent.ACTION_SEND,
                        mimeType = "text/plain",
                        singleUri = null,
                        multiUris = null,
                        caption = caption
                    )
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getStreamUriList(incoming)
                if (!uris.isNullOrEmpty()) {
                    val rehostedList = ArrayList<Uri>()
                    for (u in uris) {
                        rehostedList.add(rehostUriToCache(u, mimeType) ?: u)
                    }
                    launchTargetShare(
                        targetPkg = targetPkg,
                        action = Intent.ACTION_SEND_MULTIPLE,
                        mimeType = mimeType,
                        singleUri = null,
                        multiUris = rehostedList,
                        caption = caption
                    )
                }
            }
        }
    }

    private fun getStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun getStreamUriList(intent: Intent): ArrayList<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
    }

    /**
     * Copies a transient content:// URI from Graph Messenger or WhatsApp into our own
     * cacheDir/shared_posts/ directory and returns a FileProvider content:// URI.
     */
    private fun rehostUriToCache(sourceUri: Uri, mimeType: String): Uri? {
        return try {
            val sharedDir = File(cacheDir, "shared_posts").apply { mkdirs() }
            // Clean up files older than 10 minutes
            val cutoff = System.currentTimeMillis() - 600_000L
            sharedDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }

            val ext = when {
                mimeType.contains("video") -> ".mp4"
                mimeType.contains("png") -> ".png"
                mimeType.contains("gif") -> ".gif"
                mimeType.contains("webp") -> ".webp"
                mimeType.contains("image") -> ".jpg"
                else -> ".bin"
            }
            val outFile = File(sharedDir, "post_${System.currentTimeMillis()}$ext")
            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
        } catch (e: Exception) {
            null
        }
    }

    private fun launchTargetShare(
        targetPkg: String,
        action: String,
        mimeType: String,
        singleUri: Uri?,
        multiUris: ArrayList<Uri>?,
        caption: String
    ) {
        val outIntent = Intent(action).apply {
            type = mimeType
            setPackage(targetPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (caption.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, caption)
            }

            if (singleUri != null) {
                putExtra(Intent.EXTRA_STREAM, singleUri)
                clipData = ClipData.newUri(contentResolver, "shared_media", singleUri)
                grantUriPermission(targetPkg, singleUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else if (!multiUris.isNullOrEmpty()) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, multiUris)
                val clip = ClipData.newUri(contentResolver, "shared_media", multiUris[0])
                for (i in 1 until multiUris.size) {
                    clip.addItem(ClipData.Item(multiUris[i]))
                }
                clipData = clip
                multiUris.forEach { uri ->
                    grantUriPermission(targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        try {
            startActivity(outIntent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not open $targetPkg — please verify it is installed.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}