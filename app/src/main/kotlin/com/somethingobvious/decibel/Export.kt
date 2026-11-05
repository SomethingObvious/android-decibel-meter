package com.somethingobvious.decibel

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.somethingobvious.decibel.meter.Session
import com.somethingobvious.decibel.meter.Weighting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Writes a session's per-second log to Downloads/Decibel as CSV. */
object Export {
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val fileStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)
    private val endStamp = DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT)
    private const val MILLIS = 1000.0

    /** Anything that stopped the file being written, with a message fit to show. */
    class Failed(message: String?, cause: Throwable) : Exception(message, cause)

    /** Returns null when there is nothing to save. */
    @Throws(Failed::class)
    suspend fun save(
        context: Context,
        session: Session,
        offset: Double,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Uri? =
        withContext(io) {
            // MediaStore reports a full disk or a name clash as runtime exceptions, so they're caught by type here.
            try {
                saveBlocking(context, session, offset)
            } catch (e: IOException) {
                throw Failed(e.message, e)
            } catch (e: IllegalStateException) {
                throw Failed(e.message, e)
            } catch (e: SQLException) {
                throw Failed(e.message, e)
            } catch (e: SecurityException) {
                throw Failed(e.message, e)
            } catch (e: IllegalArgumentException) {
                throw Failed(e.message, e)
            }
        }

    fun share(context: Context, uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun saveBlocking(context: Context, session: Session, offset: Double): Uri? {
        if (session.seconds.size == 0) return null
        val origin = session.runs.firstOrNull()?.wallMillis ?: System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val resolver = context.contentResolver
        // Named for when the log starts and when this save ends it, so saving again never clashes.
        val end = origin + (session.seconds[session.seconds.size - 1, Session.ELAPSED] * MILLIS).toLong()
        val from = fileStamp.format(Instant.ofEpochMilli(origin).atZone(zone))
        val to = endStamp.format(Instant.ofEpochMilli(end).atZone(zone))
        val name = "decibel-$from-to-$to.csv"
        val uri = insertPending(resolver, name)
        var isWritten = false
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.bufferedWriter().use { writer ->
                    write(out = writer, session = session, offset = offset, origin = origin, zone = zone)
                }
            } ?: throw IOException("Downloads gave no stream")
            isWritten = true
        } finally {
            if (!isWritten) resolver.delete(uri, null, null)
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun insertPending(resolver: ContentResolver, name: String): Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Decibel")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads refused the file")
    }

    private fun write(out: Writer, session: Session, offset: Double, origin: Long, zone: ZoneId) {
        val columns = Weighting.entries.flatMap { listOf("L${it.name}eq", "L${it.name}Fmax", "L${it.name}peak") }
        out.write((listOf("time", "elapsed_s") + columns + "over_range").joinToString(","))
        out.write("\n")
        val rows = session.seconds
        for (row in 0 until rows.size) {
            val elapsed = rows[row, Session.ELAPSED].toDouble()
            val time = stamp.format(Instant.ofEpochMilli(origin + (elapsed * MILLIS).toLong()).atZone(zone))
            val seconds = String.format(Locale.ROOT, "%.0f", elapsed)
            val cells =
                (0 until Session.CLIPPED).map { column ->
                    Reading.level(rows[row, column], offset)?.let { String.format(Locale.ROOT, "%.1f", it) }.orEmpty()
                }
            out.write("$time,$seconds,${cells.joinToString(",")},${rows[row, Session.CLIPPED].toInt()}\n")
        }
    }
}
