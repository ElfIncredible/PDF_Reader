package com.folio.pdf.net

import android.content.Context
import android.net.Uri
import com.folio.pdf.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/** A clear, user-facing problem talking to the server. */
class ServerException(message: String) : IOException(message)

data class ServerHealth(
    val version: String,
    val ai: Boolean,
    val model: String?,
    val convert: Boolean,
    val pdfa: Boolean,
    val validate: Boolean,
)

data class Summary(val summary: String, val points: List<String>)
data class Citation(val page: Int, val snippet: String)
data class Answer(val answer: String, val citations: List<Citation>)
data class ExtractedTable(val page: Int, val index: Int, val rows: List<List<String>>)

/**
 * Client for the Folio server (see folio-server/). Plain HttpURLConnection + org.json,
 * both built into Android, so there are no extra libraries.
 */
class FolioServer(private val context: Context, private val baseUrl: String, private val token: String) {

    companion object {
        fun from(context: Context, s: AppSettings): FolioServer? =
            s.serverUrl.takeIf { it.isNotBlank() }?.let { FolioServer(context, it, s.serverToken) }

        /** A plain-http address outside the home network would send documents unencrypted. */
        fun isInsecure(url: String): Boolean {
            val u = runCatching { URL(url) }.getOrNull() ?: return false
            if (u.protocol != "http") return false
            val h = u.host
            val local = h == "localhost" || h.startsWith("10.") || h.startsWith("192.168.") || h.endsWith(".local") ||
                Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(h)
            return !local
        }
    }

    suspend fun health(): ServerHealth = withContext(Dispatchers.IO) {
        val json = JSONObject(request("GET", "/health", timeoutSec = 10).decodeToString())
        val f = json.getJSONObject("features")
        ServerHealth(
            version = json.optString("version"),
            ai = f.optBoolean("ai"),
            model = f.optString("model").takeIf { it.isNotBlank() && it != "null" },
            convert = f.optBoolean("convert"),
            pdfa = f.optBoolean("pdfa"),
            validate = f.optBoolean("validate"),
        )
    }

    suspend fun summarize(pages: List<String>, title: String?, length: String): Summary = withContext(Dispatchers.IO) {
        val body = JSONObject().put("pages", JSONArray(pages)).put("length", length).apply { title?.let { put("title", it) } }
        val json = JSONObject(postJson("/summarize", body).decodeToString())
        Summary(json.optString("summary"), json.optJSONArray("points").strings())
    }

    suspend fun ask(pages: List<String>, question: String, history: List<Pair<String, String>>): Answer =
        withContext(Dispatchers.IO) {
            val turns = JSONArray(history.map { (q, a) -> JSONObject().put("q", q).put("a", a) })
            val body = JSONObject().put("pages", JSONArray(pages)).put("question", question).put("history", turns)
            val json = JSONObject(postJson("/ask", body).decodeToString())
            val cites = json.optJSONArray("citations") ?: JSONArray()
            Answer(
                json.optString("answer"),
                (0 until cites.length()).map { i ->
                    val c = cites.getJSONObject(i)
                    Citation(c.optInt("page"), c.optString("snippet"))
                },
            )
        }

    suspend fun tables(file: Uri): List<ExtractedTable> = withContext(Dispatchers.IO) {
        val json = JSONObject(upload("/tables?format=json", file, "document.pdf", "application/pdf").decodeToString())
        val arr = json.optJSONArray("tables") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val rows = t.getJSONArray("rows")
            ExtractedTable(
                t.optInt("page"), t.optInt("index"),
                (0 until rows.length()).map { r -> rows.getJSONArray(r).strings() },
            )
        }
    }

    suspend fun tablesXlsx(file: Uri): ByteArray = withContext(Dispatchers.IO) {
        upload("/tables?format=xlsx", file, "document.pdf", "application/pdf")
    }

    suspend fun convertToPdf(file: Uri, fileName: String, mime: String): ByteArray = withContext(Dispatchers.IO) {
        upload("/convert", file, fileName, mime, timeoutSec = 300)
    }

    /** Returns (PDF/A bytes, validation: "passed" / "failed: …" / "not-run"). */
    suspend fun pdfa(file: Uri, part: Int = 2): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        var validation = "not-run"
        val bytes = upload("/pdfa?part=$part", file, "document.pdf", "application/pdf", timeoutSec = 600) { conn ->
            validation = conn.getHeaderField("X-Folio-Validation") ?: "not-run"
        }
        bytes to validation
    }

    // ---- HTTP ----------------------------------------------------------------------

    private fun open(method: String, path: String, timeoutSec: Int): HttpURLConnection {
        val url = runCatching { URL(baseUrl + path) }.getOrElse { throw ServerException("The server address isn't valid") }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = timeoutSec * 1000
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json, */*")
        }
    }

    private fun request(method: String, path: String, timeoutSec: Int = 300): ByteArray {
        val conn = open(method, path, timeoutSec)
        return finish(conn)
    }

    private fun postJson(path: String, body: JSONObject): ByteArray {
        val conn = open("POST", path, timeoutSec = 600)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val data = body.toString().toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(data.size)
        send(conn) { it.write(data) }
        return finish(conn)
    }

    private fun upload(
        path: String,
        file: Uri,
        fileName: String,
        mime: String,
        timeoutSec: Int = 300,
        onHeaders: (HttpURLConnection) -> Unit = {},
    ): ByteArray {
        val boundary = "folio-" + UUID.randomUUID()
        val conn = open("POST", path, timeoutSec)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setChunkedStreamingMode(64 * 1024)
        val safeName = fileName.replace("\"", "")
        send(conn) { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            context.contentResolver.openInputStream(file)?.use { it.copyTo(out, 64 * 1024) }
                ?: throw ServerException("Couldn't read the file to send")
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val bytes = finish(conn)
        onHeaders(conn)
        return bytes
    }

    private fun send(conn: HttpURLConnection, write: (OutputStream) -> Unit) {
        try {
            conn.outputStream.use(write)
        } catch (e: ServerException) {
            throw e
        } catch (e: IOException) {
            throw ServerException(unreachable(e))
        }
    }

    private fun finish(conn: HttpURLConnection): ByteArray {
        try {
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.use { it.readBytes() }
            val detail = runCatching {
                JSONObject(conn.errorStream.use { it.readBytes() }.decodeToString()).optString("detail")
            }.getOrNull().orEmpty()
            throw ServerException(
                when (code) {
                    401 -> "The server rejected the access token. Check it in Settings."
                    413 -> detail.ifBlank { "That file is too big for the server." }
                    503 -> detail.ifBlank { "The server's AI model isn't available." }
                    else -> detail.ifBlank { "The server answered with an error ($code)." }
                },
            )
        } catch (e: ServerException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ServerException("The server took too long to answer. Large documents and small computers can be slow; try again or use a shorter document.")
        } catch (e: IOException) {
            throw ServerException(unreachable(e))
        } finally {
            conn.disconnect()
        }
    }

    private fun unreachable(e: IOException) =
        "Couldn't reach the Folio server at $baseUrl. Is it running, and is your phone on the same network? (${e.javaClass.simpleName})"

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
