package ir.lexilens.reader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import ir.lexilens.reader.util.TextUtils
import java.io.File

class DictionaryRepository private constructor(private val context: Context) {

    @Volatile
    private var database: SQLiteDatabase? = null

    private val lock = Any()

    fun ensureReady() {
        if (database != null) return
        synchronized(lock) {
            if (database != null) return
            val target = context.getDatabasePath("dict.db")
            if (!target.exists() || target.length() < 1_000_000L) {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".part")
                context.assets.open("dict.db").use { input ->
                    tmp.outputStream().buffered(65_536).use { output -> input.copyTo(output) }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            database = SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        }
    }

    fun lookup(rawWord: String): DictionaryEntry? {
        ensureReady()
        val db = database ?: return null
        val norm = TextUtils.cleanToken(rawWord)
        if (norm.isBlank()) return null

        val direct = entry(db, norm)
        val formHead = formHead(db, norm)
        if (formHead != null && (direct == null || direct.source == "vahidn" || direct.source == "shirin")) {
            val viaForm = entry(db, formHead)
            if (viaForm != null) return viaForm.copy(matchedForm = norm)
        }
        if (direct != null) return direct

        for (candidate in TextUtils.morphologyCandidates(norm)) {
            val head = formHead(db, candidate) ?: candidate
            val found = entry(db, head) ?: entry(db, candidate) ?: continue
            return found.copy(matchedForm = candidate)
        }
        return null
    }

    private fun formHead(db: SQLiteDatabase, form: String): String? {
        db.rawQuery("SELECT head FROM forms WHERE form = ? LIMIT 1", arrayOf(form)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun entry(db: SQLiteDatabase, norm: String): DictionaryEntry? {
        db.rawQuery(
            "SELECT display, ipa, pos, trans, gloss, source FROM entries WHERE norm = ? LIMIT 1",
            arrayOf(norm),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val translations = cursor.getString(3)
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(10)
            val glosses = cursor.getString(4)
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(2)
            return DictionaryEntry(
                word = norm,
                display = cursor.getString(0).ifBlank { norm },
                ipa = cursor.getString(1),
                pos = cursor.getString(2),
                translations = translations,
                glosses = glosses,
                source = cursor.getString(5),
            )
        }
    }

    companion object {
        @Volatile
        private var instance: DictionaryRepository? = null

        fun get(context: Context): DictionaryRepository {
            val existing = instance
            if (existing != null) return existing
            synchronized(this) {
                val current = instance
                if (current != null) return current
                return DictionaryRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
