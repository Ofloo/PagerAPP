package io.ofloo.pagerapp.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.Pgp
import io.ofloo.pagerapp.shared.sha256Hex
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Local photo files, chunk persistence and JPEG preparation for chat photos. */
object ChatPhotos {

    /** A share-link photo is compressed to this before encryption/upload. */
    const val MAX_LINK_BYTES = 8_000_000

    /** Attachments larger than this are refused before reading them into memory. */
    const val MAX_ATTACHMENT_BYTES = 20_000_000

    data class Attachment(val bytes: ByteArray, val name: String, val mime: String)

    private const val MAX_DIRECT_DIMENSION = 2048
    private const val MAX_LINK_DIMENSION = 4096
    private const val MIN_DIMENSION = 320
    private const val PENDING_DIR = "chat-images/pending"
    private const val IMAGE_DIR = "chat-images"

    data class CompletedPhoto(
        val imageKey: String,
        val path: String,
        val from: String?,
        val fromName: String?,
        val createdAt: Double,
    )

    fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                } else {
                    -1L
                }
            } ?: -1L
    }.getOrDefault(-1L)

    /**
     * Decodes, rotates and JPEG-compresses [uri] until it fits [maxBytes]. [maxDimension] bounds
     * the long side; the quality and then the dimensions are reduced. Returns null when the image
     * cannot be read or cannot be squeezed under the limit.
     */
    fun compress(
        context: Context,
        uri: Uri,
        maxBytes: Int,
        maxDimension: Int = MAX_DIRECT_DIMENSION,
    ): ByteArray? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        bitmap = orient(uri, resolver, bitmap)
        val scale = maxDimension.toFloat() / max(bitmap.width, bitmap.height)
        if (scale < 1f) {
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }

        var quality = 88
        val out = ByteArrayOutputStream()
        while (true) {
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes || quality <= 30) break
            quality -= 8
        }
        while (out.size() > maxBytes && max(bitmap.width, bitmap.height) > MIN_DIMENSION) {
            val nextWidth = (bitmap.width * 4 / 5).coerceAtLeast(MIN_DIMENSION)
            val nextHeight = (bitmap.height * 4 / 5).coerceAtLeast(MIN_DIMENSION)
            bitmap = Bitmap.createScaledBitmap(bitmap, nextWidth, nextHeight, true)
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 65, out)
        }
        out.toByteArray().takeIf { it.size <= maxBytes }
    }.getOrNull()

    /**
     * Decodes a local chat photo at a bounded size. A share-link photo can be 4096 px and would
     * otherwise allocate tens of MB per bubble; sampling keeps a photo-heavy chat from OOMing.
     */
    fun decodeSampled(path: String, maxSize: Int = 720): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    /** Saves a JPEG under a stable local key and returns its absolute path. */
    fun saveImage(context: Context, imageKey: String, jpeg: ByteArray): String? = runCatching {
        val dir = File(context.filesDir, IMAGE_DIR).apply { mkdirs() }
        val file = File(dir, safeName(imageKey) + ".jpg")
        file.writeBytes(jpeg)
        android.util.Log.i("PagerAPP", "photo file saved: ${file.absolutePath} (${jpeg.size} bytes)")
        file.absolutePath
    }.getOrNull()

    /** Reads a generic attachment, its display name and its MIME type, with a hard size cap. */
    fun readAttachment(context: Context, uri: Uri, maxBytes: Int = MAX_ATTACHMENT_BYTES): Attachment? =
        runCatching {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }?.takeIf { it.isNotBlank() } ?: "attachment"
            val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"
            val declared = sizeOf(context, uri)
            if (declared > maxBytes) return null
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: return null
            if (bytes.isEmpty()) return null
            Attachment(bytes, name, mime)
        }.getOrNull()

    /** Stores a local attachment under the app's private files and returns its path. */
    fun saveAttachment(context: Context, key: String, name: String, bytes: ByteArray): String? =
        runCatching {
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val file = File(dir, safeName(key) + "-" + safeName(name))
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()

    /** Opens a local attachment through the system's viewer. */
    fun openAttachment(context: Context, path: String, mime: String): Boolean = runCatching {
        val file = File(path)
        if (!file.isFile) return false
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Saves a local attachment to Downloads/PagerAPP (Android 10+) or app Pictures on older. */
    fun saveAttachmentToDownloads(
        context: Context,
        path: String,
        name: String,
        mime: String,
    ): Boolean = runCatching {
        val file = File(path)
        if (!file.isFile) return false
        val displayName = safeName(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/PagerAPP",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            val written = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    true
                } ?: false
            }.getOrDefault(false)
            if (!written) {
                resolver.delete(uri, null, null)
                return@runCatching false
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "PagerAPP",
            ).apply { mkdirs() }
            file.copyTo(File(dir, displayName), overwrite = true)
            true
        }
    }.getOrDefault(false)

    /**
     * Saves a local chat photo to the device gallery (Android 10+) or the app's Pictures folder on
     * older releases. Returns true when the file was written.
     */
    fun saveToGallery(context: Context, path: String, displayName: String): Boolean = runCatching {
        val file = File(path)
        if (!file.isFile) return@runCatching false
        val name = safeName(displayName) + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/PagerAPP",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            val written = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    true
                } ?: false
            }.getOrDefault(false)
            if (!written) {
                resolver.delete(uri, null, null)
                return@runCatching false
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "PagerAPP",
            ).apply { mkdirs() }
            file.copyTo(File(dir, name), overwrite = true)
            true
        }
    }.getOrDefault(false)

    /** Persists one chunk and its metadata so a later pass can finish an interrupted image. */
    fun storeChunk(context: Context, page: Page): Boolean = runCatching {
        val imageId = page.imageId?.takeIf { it.isNotBlank() } ?: return false
        val part = page.imagePart ?: return false
        val total = page.imageTotal ?: return false
        val sha = page.imageSha256?.takeIf { it.length == 64 } ?: return false
        val chunk = page.imageChunk ?: return false
        if (total !in 1..80 || part !in 1..total || chunk.length > 60_000) return false

        val dir = File(context.filesDir, "$PENDING_DIR/${safeName(imageId)}").apply { mkdirs() }
        File(dir, "%05d.part".format(part)).writeText(chunk)
        val meta = JSONObject()
            .put("total", total)
            .put("sha", sha)
            .put("from", page.from ?: JSONObject.NULL)
            .put("from_name", page.fromName ?: JSONObject.NULL)
            .put("created_at", page.createdAt)
        File(dir, "meta.json").writeText(meta.toString())
        true
    }.getOrDefault(false)

    /**
     * Scans pending images and completes every one whose chunks are all present: verifies the
     * SHA-256, decrypts with [secret], writes the JPEG and removes the pending directory.
     */
    fun completed(context: Context, secret: String?): List<CompletedPhoto> {
        if (secret.isNullOrBlank()) return emptyList()
        val root = File(context.filesDir, PENDING_DIR)
        if (!root.isDirectory) return emptyList()
        val result = mutableListOf<CompletedPhoto>()
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val metaFile = File(dir, "meta.json")
            if (!metaFile.isFile) return@forEach
            val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return@forEach
            val total = meta.optInt("total")
            val sha = meta.optString("sha")
            if (total !in 1..80 || sha.length != 64) {
                dir.deleteRecursively()
                return@forEach
            }
            val parts = (1..total).map { File(dir, "%05d.part".format(it)) }
            if (parts.any { !it.isFile }) return@forEach
            val armor = runCatching { parts.joinToString("") { it.readText() } }.getOrNull()
                ?: return@forEach
            if (sha256Hex(armor) != sha) {
                dir.deleteRecursively()
                return@forEach
            }
            val jpeg = runCatching { Pgp.decryptBytes(armor, secret) }.getOrNull()
            if (jpeg == null || jpeg.isEmpty() || jpeg.size > 2_000_000) {
                dir.deleteRecursively()
                return@forEach
            }
            val imageKey = "image-${dir.name}"
            val path = saveImage(context, imageKey, jpeg) ?: return@forEach
            val from = meta.optString("from").takeIf { it.isNotBlank() && it != "null" }
            val fromName = meta.optString("from_name").takeIf { it.isNotBlank() && it != "null" }
            val createdAt = meta.optDouble("created_at", 0.0)
            dir.deleteRecursively()
            result += CompletedPhoto(imageKey, path, from, fromName, createdAt)
        }
        return result
    }

    private fun orient(uri: Uri, resolver: android.content.ContentResolver, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun safeName(key: String): String =
        key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "image" }
}
