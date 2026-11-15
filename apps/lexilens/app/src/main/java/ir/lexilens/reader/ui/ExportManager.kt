package ir.lexilens.reader.ui

import android.content.Context
import android.net.Uri
import ir.lexilens.reader.App
import ir.lexilens.reader.data.Card
import ir.lexilens.reader.data.CardState
import ir.lexilens.reader.util.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.BufferedInputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RestoreResult(
    val documents: Int,
    val cards: Int,
    val skipped: Int,
)

object ExportManager {

    fun dateStamp(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun exportCsv(context: Context, app: App, uri: Uri): Int {
        val cards = app.db.allCards()
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append("Word,Translation,Context,SourceSentence,Document,Page,Due,State,Reps\n")
        for (c in cards) {
            sb.append(TextUtils.csvEscape(c.word)).append(',')
            sb.append(TextUtils.csvEscape(c.translation)).append(',')
            sb.append(TextUtils.csvEscape(c.sourceLine)).append(',')
            sb.append(TextUtils.csvEscape(c.sentence)).append(',')
            sb.append(TextUtils.csvEscape(c.docTitle)).append(',')
            sb.append(c.page + 1).append(',')
            sb.append(TextUtils.csvEscape(TextUtils.dueLabel(c))).append(',')
            sb.append(TextUtils.csvEscape(stateName(c))).append(',')
            sb.append(c.reps).append('\n')
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        } ?: error("نمی‌توان فایل را نوشت")
        return cards.size
    }

    fun backupZip(context: Context, app: App, uri: Uri): Int {
        val docs = app.db.documents()
        val cards = app.db.allCards()
        val root = JSONObject()
        root.put("app", "lexilens")
        root.put("version", 1)
        root.put("created_at", System.currentTimeMillis())

        val docArray = JSONArray()
        for (d in docs) {
            docArray.put(JSONObject().apply {
                put("id", d.id)
                put("title", d.title)
                put("page_count", d.pageCount)
                put("last_page", d.lastPage)
                put("added_at", d.addedAt)
            })
        }
        root.put("documents", docArray)

        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                val cardArray = JSONArray()
                for (c in cards) {
                    val jo = cardJson(c)
                    val crop = c.cropPath.takeIf { it.isNotBlank() && File(it).exists() }
                    val audio = c.audioPath.takeIf { it.isNotBlank() && File(it).exists() }
                    if (crop != null) {
                        val name = "crops/" + File(crop).name
                        jo.put("crop_entry", name)
                        zip.putNextEntry(ZipEntry(name))
                        File(crop).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    if (audio != null) {
                        val name = "audio/" + File(audio).name
                        jo.put("audio_entry", name)
                        zip.putNextEntry(ZipEntry(name))
                        File(audio).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    cardArray.put(jo)
                }
                root.put("cards", cardArray)
                zip.putNextEntry(ZipEntry("lexilens.json"))
                zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } ?: error("نمی‌توان فایل پشتیبان را نوشت")
        return cards.size
    }

    fun restoreZip(context: Context, app: App, uri: Uri): RestoreResult {
        val temp = File(context.cacheDir, "restore-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val rootDir = temp.canonicalFile
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name ?: ""
                        val target = File(rootDir, name)
                        val canonical = target.canonicalFile
                        if (!canonical.path.startsWith(rootDir.path + File.separator) && canonical != rootDir) {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            canonical.mkdirs()
                        } else {
                            canonical.parentFile?.mkdirs()
                            canonical.outputStream().buffered().use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("فایل پشتیبان قابل خواندن نیست")

            val jsonFile = File(rootDir, "lexilens.json")
            if (!jsonFile.exists()) error("این فایل، پشتیبان لکسی‌لنز نیست")
            val root = JSONObject(jsonFile.readText())
            if (root.optString("app") != "lexilens") error("این فایل، پشتیبان لکسی‌لنز نیست")

            val documentArray = root.optJSONArray("documents") ?: JSONArray()
            val idMap = HashMap<Long, Long>()
            var restoredDocs = 0
            for (i in 0 until documentArray.length()) {
                val jo = documentArray.optJSONObject(i) ?: continue
                val oldId = jo.optLong("id", i.toLong())
                val title = jo.optString("title", "بدون عنوان").ifBlank { "بدون عنوان" }
                val path = jo.optString("path", "")
                val pageCount = jo.optInt("page_count", 0)
                val existing = if (path.isBlank()) {
                    app.db.findDocumentByTitle(title)
                        ?: app.db.findDocumentByTitleAndPath(title, "")
                } else {
                    app.db.findDocumentByTitleAndPath(title, path)
                }
                val newId = existing?.id ?: app.db.insertDocument(
                    title = title,
                    path = path,
                    pageCount = pageCount,
                    addedAt = jo.optLong("added_at", System.currentTimeMillis()),
                    lastPage = jo.optInt("last_page", 0),
                    lastReadAt = 0L,
                )
                idMap[oldId] = newId
                if (existing == null) restoredDocs++
            }

            val cardArray = root.optJSONArray("cards") ?: JSONArray()
            var restoredCards = 0
            var skipped = 0
            for (i in 0 until cardArray.length()) {
                val jo = cardArray.optJSONObject(i) ?: continue
                val docId = idMap[jo.optLong("doc_id", -1L)] ?: continue
                val doc = app.db.document(docId) ?: continue
                val cropEntry = jo.optString("crop_entry", "")
                val audioEntry = jo.optString("audio_entry", "")
                val cropPath = copyEntry(rootDir, cropEntry, File(context.filesDir, "crops"), "restored_crop_")
                val audioPath = copyEntry(rootDir, audioEntry, File(context.filesDir, "audio"), "restored_audio_")
                val card = Card(
                    id = 0L,
                    docId = docId,
                    word = jo.optString("word", ""),
                    normWord = jo.optString("norm_word", ""),
                    translation = jo.optString("translation", ""),
                    ipa = jo.optString("ipa", ""),
                    pos = jo.optString("pos", ""),
                    sourceLine = jo.optString("source_line", ""),
                    sentence = jo.optString("sentence", ""),
                    page = jo.optInt("page", 0),
                    cropPath = cropPath,
                    rectL = jo.optDouble("rect_l", 0.0).toFloat(),
                    rectT = jo.optDouble("rect_t", 0.0).toFloat(),
                    rectR = jo.optDouble("rect_r", 0.0).toFloat(),
                    rectB = jo.optDouble("rect_b", 0.0).toFloat(),
                    audioPath = audioPath,
                    createdAt = jo.optLong("created_at", System.currentTimeMillis()),
                    dueAt = jo.optLong("due_at", System.currentTimeMillis()),
                    intervalDays = jo.optDouble("interval_days", 0.0),
                    ease = jo.optDouble("ease", 2.5),
                    reps = jo.optInt("reps", 0),
                    lapses = jo.optInt("lapses", 0),
                    state = jo.optInt("state", CardState.NEW.ordinal),
                    step = jo.optInt("step", 0),
                    lastReview = jo.optLong("last_review", 0L),
                    suspended = jo.optBoolean("suspended", false),
                    note = jo.optString("note", ""),
                    docTitle = doc.title,
                )
                if (card.word.isBlank() || card.normWord.isBlank()) {
                    skipped++
                    deleteQuietly(cropPath)
                    deleteQuietly(audioPath)
                    continue
                }
                val id = app.db.importCard(card)
                if (id == -1L) {
                    skipped++
                    deleteQuietly(cropPath)
                    deleteQuietly(audioPath)
                } else {
                    restoredCards++
                }
            }
            return RestoreResult(restoredDocs, restoredCards, skipped)
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun copyEntry(root: File, entry: String, dir: File, prefix: String): String {
        if (entry.isBlank()) return ""
        val source = File(root, entry)
        if (!source.exists() || !source.isFile) return ""
        dir.mkdirs()
        val ext = source.extension.ifBlank { "bin" }
        val target = File(dir, "$prefix${UUID.randomUUID()}.$ext")
        return runCatching {
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }.getOrDefault("")
    }

    private fun deleteQuietly(path: String) {
        if (path.isNotBlank()) runCatching { File(path).delete() }
    }

    private fun cardJson(c: Card): JSONObject = JSONObject().apply {
        put("doc_id", c.docId)
        put("word", c.word)
        put("norm_word", c.normWord)
        put("translation", c.translation)
        put("ipa", c.ipa)
        put("pos", c.pos)
        put("source_line", c.sourceLine)
        put("sentence", c.sentence)
        put("page", c.page)
        put("rect_l", c.rectL.toDouble())
        put("rect_t", c.rectT.toDouble())
        put("rect_r", c.rectR.toDouble())
        put("rect_b", c.rectB.toDouble())
        put("created_at", c.createdAt)
        put("due_at", c.dueAt)
        put("interval_days", c.intervalDays)
        put("ease", c.ease)
        put("reps", c.reps)
        put("lapses", c.lapses)
        put("state", c.state)
        put("step", c.step)
        put("last_review", c.lastReview)
        put("suspended", c.suspended)
        put("note", c.note)
    }

    private fun stateName(c: Card): String = when (CardState.values().getOrElse(c.state) { CardState.NEW }) {
        CardState.NEW -> "new"
        CardState.LEARNING -> "learning"
        CardState.REVIEW -> "review"
        CardState.RELEARNING -> "relearning"
    }
}
