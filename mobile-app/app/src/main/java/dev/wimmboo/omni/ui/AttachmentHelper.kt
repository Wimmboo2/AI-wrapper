package dev.wimmboo.omni.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import dev.wimmboo.omni.data.model.PendingFile
import java.io.ByteArrayOutputStream

object AttachmentHelper {
    private const val MAX_TEXT_CHARS = 8000
    private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

    fun readAttachments(resolver: ContentResolver, uris: List<Uri>): List<PendingFile> {
        val out = mutableListOf<PendingFile>()
        for (uri in uris) {
            val item = readSingleAttachment(resolver, uri) ?: continue
            out += item
        }
        return out
    }

    private fun readSingleAttachment(resolver: ContentResolver, uri: Uri): PendingFile? {
        val meta = queryMeta(resolver, uri)
        val mime = resolver.getType(uri) ?: guessMimeFromName(meta.name)
        val isImage = mime.startsWith("image/")

        return try {
            resolver.openInputStream(uri)?.use { stream ->
                if (isImage) {
                    val bytes = readBoundedBytes(stream, MAX_IMAGE_BYTES)
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    PendingFile(
                        name = meta.name,
                        type = mime,
                        data = "data:$mime;base64,$b64",
                        size = bytes.size.toLong(),
                    )
                } else {
                    val rawText = stream.bufferedReader(Charsets.UTF_8).readText()
                    val body = if (rawText.length > MAX_TEXT_CHARS) {
                        rawText.substring(0, MAX_TEXT_CHARS) + "\n... [truncated]"
                    } else {
                        rawText
                    }
                    PendingFile(
                        name = meta.name,
                        type = mime,
                        data = body,
                        size = meta.size,
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readUtf8Text(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
    } catch (_: Exception) {
        null
    }

    fun writeUtf8Text(resolver: ContentResolver, uri: Uri, content: String): Boolean = try {
        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        true
    } catch (_: Exception) {
        false
    }

    private data class FileMeta(val name: String, val size: Long)

    private fun queryMeta(resolver: ContentResolver, uri: Uri): FileMeta {
        var name = "attachment"
        var size = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameCol >= 0) name = cursor.getString(nameCol) ?: name
                        if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol)
                    }
                }
        } catch (_: Exception) {
            // fall back to defaults
        }
        return FileMeta(name, size)
    }

    private fun readBoundedBytes(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun guessMimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "json" -> "application/json"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "ts" -> "text/typescript"
            "py" -> "text/x-python"
            else -> "text/plain"
        }
    }
}
