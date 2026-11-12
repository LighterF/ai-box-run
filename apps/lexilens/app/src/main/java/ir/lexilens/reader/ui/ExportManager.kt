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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
