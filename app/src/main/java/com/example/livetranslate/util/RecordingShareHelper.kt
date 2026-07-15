package com.example.livetranslate.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.livetranslate.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Share / export helpers for SRT, audio WAV, and clipboard.
 */
object RecordingShareHelper {
    private const val TAG = "RecordingShare"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun authority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"

    fun uriForFile(context: Context, file: File) =
        FileProvider.getUriForFile(context, authority(context), file)

    fun copyToClipboard(
        context: Context,
        text: String,
        label: String = "text",
        toastOk: String? = null
    ) {
        if (text.isBlank()) {
            Toast.makeText(context, context.getString(R.string.share_content_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(
            context,
            toastOk ?: context.getString(R.string.copied_zh),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Write text as a file under Downloads (default .srt).
     * Returns display name on success.
     */
    fun exportTextToDownloads(
        context: Context,
        content: String,
        baseName: String,
        extension: String = "srt",
        mimeType: String = "application/x-subrip"
    ): String? {
        if (content.isBlank()) {
            Toast.makeText(context, context.getString(R.string.share_content_empty), Toast.LENGTH_SHORT).show()
            return null
        }
        val ext = extension.removePrefix(".")
        val displayName = if (baseName.endsWith(".$ext", ignoreCase = true)) {
            baseName
        } else {
            "$baseName.$ext"
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                resolver.openOutputStream(item)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("openOutputStream failed")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(item, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val dest = uniqueFile(dir, displayName)
                FileOutputStream(dest).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            }
            Toast.makeText(
                context,
                context.getString(R.string.share_export_ok, displayName),
                Toast.LENGTH_LONG
            ).show()
            displayName
        } catch (e: Exception) {
            Log.e(TAG, "exportTextToDownloads failed", e)
            Toast.makeText(
                context,
                context.getString(R.string.share_export_fail, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            null
        }
    }

    /** Share SRT (or plain) text via system chooser. */
    fun shareText(
        context: Context,
        text: String,
        chooserTitle: String? = null,
        mimeType: String = "text/plain"
    ) {
        if (text.isBlank()) {
            Toast.makeText(context, context.getString(R.string.share_content_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(
                send,
                chooserTitle ?: context.getString(R.string.share)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun shareAudio(context: Context, file: File, chooserTitle: String? = null) {
        if (!file.isFile || file.length() <= 0L) {
            Toast.makeText(context, context.getString(R.string.share_audio_missing), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = uriForFile(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        }
        context.startActivity(
            Intent.createChooser(
                send,
                chooserTitle ?: context.getString(R.string.share_audio)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Copy session WAV into public Downloads. */
    fun exportAudioToDownloads(context: Context, file: File): String? {
        if (!file.isFile || file.length() <= 0L) {
            Toast.makeText(context, context.getString(R.string.share_audio_missing), Toast.LENGTH_SHORT).show()
            return null
        }
        val displayName = file.name
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                resolver.openOutputStream(item)?.use { out ->
                    FileInputStream(file).use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("openOutputStream failed")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(item, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val dest = uniqueFile(dir, displayName)
                FileInputStream(file).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
            Toast.makeText(
                context,
                context.getString(R.string.share_export_ok, displayName),
                Toast.LENGTH_LONG
            ).show()
            displayName
        } catch (e: Exception) {
            Log.e(TAG, "exportAudioToDownloads failed", e)
            Toast.makeText(
                context,
                context.getString(R.string.share_audio_export_fail, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) name.substringAfterLast('.') else ""
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")
            i++
        }
        return candidate
    }
}
