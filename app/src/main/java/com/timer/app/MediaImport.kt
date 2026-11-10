package com.timer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Copies user-chosen media into app-private storage so KALAM never needs broad storage permissions,
 * never touches the original file, and keeps working if the original is moved or its permission is revoked.
 * All functions block: call them from a background thread.
 */
object MediaImport {
    private const val MAX_WALLPAPER_SIDE = 2560
    private const val MAX_AUDIO_BYTES = 25L * 1024L * 1024L

    /** Decodes (applying EXIF orientation), downsizes, and stores a JPEG copy. Returns the stored file name or null. */
    fun importWallpaper(ctx: Context, uri: Uri): String? {
        return try {
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            val bmp: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_WALLPAPER_SIDE) {
                    val f = MAX_WALLPAPER_SIDE.toFloat() / longest.toFloat()
                    decoder.setTargetSize(
                        max(1, (info.size.width * f).toInt()),
                        max(1, (info.size.height * f).toInt())
                    )
                }
            }
            val name = "wallpaper_" + System.currentTimeMillis() + ".jpg"
            FileOutputStream(File(ctx.filesDir, name)).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bmp.recycle()
            pruneOld(ctx, "wallpaper_", name)
            name
        } catch (e: Throwable) {
            null // unsupported format, corrupt file, permission problem or out of memory
        }
    }

    /** Copies an audio file. Returns Pair(storedFileName, displayName) or null. */
    fun importAudio(ctx: Context, uri: Uri, slot: Int): Pair<String, String>? {
        return try {
            var display = "Custom sound"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) display = c.getString(i) ?: display
                }
            }
            val ext = display.substringAfterLast('.', "").lowercase().let { if (it.isEmpty() || it.length > 5) "audio" else it }
            val prefix = "alarm_custom_" + slot + "_"
            val name = prefix + System.currentTimeMillis() + "." + ext
            val dest = File(ctx.filesDir, name)
            var total = 0L
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_AUDIO_BYTES) throw IllegalStateException("too large")
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null
            if (total == 0L) {
                dest.delete()
                return null
            }
            pruneOld(ctx, prefix, name)
            Pair(name, display)
        } catch (e: Throwable) {
            null
        }
    }

    private fun pruneOld(ctx: Context, prefix: String, keep: String) {
        try {
            val files = ctx.filesDir.listFiles() ?: return
            for (f in files) if (f.name.startsWith(prefix) && f.name != keep) f.delete()
        } catch (e: Throwable) {
        }
    }

    fun exists(ctx: Context, name: String?): Boolean {
        if (name == null) return false
        return try {
            File(ctx.filesDir, name).let { it.exists() && it.length() > 0L }
        } catch (e: Throwable) {
            false
        }
    }
}
