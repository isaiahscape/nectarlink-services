// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.Html
import android.text.Spanned
import android.text.style.CharacterStyle
import android.util.Log
import java.util.concurrent.Executors

class ClipboardController(
    context: Context,
    private val onClipboardChanged: (text: String, html: String?, isSensitive: Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nl-clipboard").apply { isDaemon = true }
    }

    @Volatile private var manager: ClipboardManager? = null
    @Volatile private var lastSentClipKey: String? = null
    @Volatile private var started = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        worker.execute { readAndDispatchPrimaryClip() }
    }

    fun start() {
        if (started) return
        started = true
        mainHandler.post {
            runCatching {
                val mgr = appContext.getSystemService(ClipboardManager::class.java) ?: return@runCatching
                manager = mgr
                mgr.addPrimaryClipChangedListener(listener)
            }.onFailure {
                Log.w(TAG, "unable to register clipboard listener", it)
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        mainHandler.post {
            runCatching { manager?.removePrimaryClipChangedListener(listener) }
        }
    }

    fun setClipboard(text: String, html: String?, sensitive: Boolean): Boolean = runCatching {
        val mgr = manager ?: appContext.getSystemService(ClipboardManager::class.java)?.also { manager = it }
            ?: return false
        val cleanHtml = html?.takeIf { it.isNotBlank() }
        val clip = if (cleanHtml != null) {
            ClipData.newHtmlText(LABEL_NECTARLINK, text, cleanHtml)
        } else {
            ClipData.newPlainText(LABEL_NECTARLINK, text)
        }
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_SUPPRESS_OVERLAY, true)
            putBoolean(EXTRA_IS_REMOTE_DEVICE, true)
            if (sensitive) putBoolean(EXTRA_IS_SENSITIVE, true)
        }
        mgr.setPrimaryClip(clip)
        true
    }.onFailure {
        Log.w(TAG, "can't set clipboard", it)
    }.getOrDefault(false)

    fun clearClipboard(expectedText: String): Boolean = runCatching {
        val mgr = manager ?: appContext.getSystemService(ClipboardManager::class.java)?.also { manager = it }
            ?: return false
        val clip = mgr.primaryClip ?: return false
        if (clip.itemCount == 0 || clip.getItemAt(0).text?.toString() != expectedText) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mgr.clearPrimaryClip()
        } else {
            mgr.setPrimaryClip(ClipData.newPlainText(LABEL_NECTARLINK, ""))
        }
        true
    }.onFailure {
        Log.w(TAG, "can't clear clipboard", it)
    }.getOrDefault(false)

    private fun readAndDispatchPrimaryClip() {
        runCatching {
            val mgr = manager ?: return
            val clip = mgr.primaryClip ?: return
            val desc = clip.description
            val label = desc?.label?.toString()
            val extras = desc?.extras
            val isRemoteOrSuppressed = label == "host clipboard" ||
                label == LABEL_NECTARLINK ||
                extras?.getBoolean(EXTRA_SUPPRESS_OVERLAY) == true ||
                extras?.getBoolean(EXTRA_IS_REMOTE_DEVICE) == true
            if (isRemoteOrSuppressed) {
                lastSentClipKey = null
                return
            }
            val isSensitive = extras?.getBoolean(EXTRA_IS_SENSITIVE) == true
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                val cs = item.text ?: continue
                val text = cs.toString().takeIf { it.isNotBlank() } ?: continue
                val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                val html = item.htmlText?.takeIf { it.isNotBlank() } ?: run {
                    if (cs is Spanned && cs.getSpans(0, cs.length, CharacterStyle::class.java).isNotEmpty()) {
                        Html.toHtml(cs, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE).takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
                val dedupKey = "$normalized\u0000${html.orEmpty()}"
                if (dedupKey == lastSentClipKey) return
                lastSentClipKey = dedupKey
                onClipboardChanged(text, html, isSensitive)
                return
            }
        }.onFailure {
            Log.d(TAG, "clipboard read skipped: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "ClipboardController"
        private const val LABEL_NECTARLINK = "Nectarlink"
        private const val EXTRA_SUPPRESS_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"
        private const val EXTRA_IS_REMOTE_DEVICE = "android.content.extra.IS_REMOTE_DEVICE"
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
