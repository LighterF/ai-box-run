package ir.lexilens.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import java.util.Locale

class AppDb(context: Context) : SQLiteOpenHelper(context, "lexilens.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE documents(\n" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                "    title TEXT NOT NULL,\n" +
                "    path TEXT NOT NULL,\n" +
                "    page_count INTEGER NOT NULL,\n" +
                "    added_at INTEGER NOT NULL,\n" +
                "    last_page INTEGER NOT NULL DEFAULT 0,\n" +
                "    last_read_at INTEGER NOT NULL DEFAULT 0\n" +
                ")"
        )
        db.execSQL(
            "CREATE TABLE cards(\n" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                "    doc_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,\n" +
                "    word TEXT NOT NULL,\n" +
                "    norm_word TEXT NOT NULL,\n" +
                "    translation TEXT NOT NULL DEFAULT '',\n" +
                "    ipa TEXT NOT NULL DEFAULT '',\n" +
                "    pos TEXT NOT NULL DEFAULT '',\n" +
                "    source_line TEXT NOT NULL DEFAULT '',\n" +
                "    sentence TEXT NOT NULL DEFAULT '',\n" +
                "    page INTEGER NOT NULL DEFAULT 0,\n" +
                "    crop_path TEXT NOT NULL DEFAULT '',\n" +
                "    rect_l REAL NOT NULL DEFAULT 0,\n" +
                "    rect_t REAL NOT NULL DEFAULT 0,\n" +
                "    rect_r REAL NOT NULL DEFAULT 0,\n" +
                "    rect_b REAL NOT NULL DEFAULT 0,\n" +
                "    audio_path TEXT NOT NULL DEFAULT '',\n" +
                "    created_at INTEGER NOT NULL,\n" +
                "    due_at INTEGER NOT NULL,\n" +
                "    interval_days REAL NOT NULL DEFAULT 0,\n" +
                "    ease REAL NOT NULL DEFAULT 2.5,\n" +
                "    reps INTEGER NOT NULL DEFAULT 0,\n" +
                "    lapses INTEGER NOT NULL DEFAULT 0,\n" +
                "    state INTEGER NOT NULL DEFAULT 0,\n" +
                "    step INTEGER NOT NULL DEFAULT 0,\n" +
                "    last_review INTEGER NOT NULL DEFAULT 0,\n" +
                "    suspended INTEGER NOT NULL DEFAULT 0,\n" +
                "    note TEXT NOT NULL DEFAULT '',\n" +
                "    UNIQUE(doc_id, norm_word, page, rect_l, rect_t)\n" +
                ")"
        )
        db.execSQL(
            "CREATE TABLE reviews(\n" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                "    card_id INTEGER NOT NULL,\n" +
                "    reviewed_at INTEGER NOT NULL,\n" +
                "    rating INTEGER NOT NULL,\n" +
                "    prev_interval REAL NOT NULL,\n" +
                "    new_interval REAL NOT NULL,\n" +
                "    prev_state INTEGER NOT NULL,\n" +
                "    new_state INTEGER NOT NULL\n" +
                ")"
        )
        db.execSQL("CREATE INDEX idx_cards_due ON cards(state, due_at)")
        db.execSQL("CREATE INDEX idx_cards_doc ON cards(doc_id)")
        db.execSQL("CREATE INDEX idx_cards_norm ON cards(norm_word)")
        db.execSQL("CREATE INDEX idx_reviews_time ON reviews(reviewed_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    fun addDocument(title: String, path: String, pageCount: Int): Long {
        return insertDocument(
            title = title,
            path = path,
            pageCount = pageCount,
            addedAt = System.currentTimeMillis(),
            lastPage = 0,
            lastReadAt = 0L,
        )
    }

    fun insertDocument(
        title: String,
        path: String,
        pageCount: Int,
        addedAt: Long,
        lastPage: Int,
        lastReadAt: Long,
    ): Long {
        val values = ContentValues().apply {
            put("title", title)
            put("path", path)
            put("page_count", pageCount)
            put("added_at", addedAt)
            put("last_page", lastPage)
            put("last_read_at", lastReadAt)
        }
        return writableDatabase.insertOrThrow("documents", null, values)
    }

    fun findDocumentByTitleAndPath(title: String, path: String): Document? {
        val cursor = readableDatabase.rawQuery(
            "SELECT d.*, (SELECT COUNT(*) FROM cards c WHERE c.doc_id = d.id) AS card_count " +
                "FROM documents d WHERE d.title = ? AND d.path = ? LIMIT 1",
            arrayOf(title, path)
        )
        cursor.use {
            return if (it.moveToFirst()) readDocument(it) else null
        }
    }

    fun documents(): List<Document> {
        val out = mutableListOf<Document>()
        readableDatabase.rawQuery(
            "SELECT d.*, (SELECT COUNT(*) FROM cards c WHERE c.doc_id = d.id) AS card_count\n" +
                "FROM documents d ORDER BY d.last_read_at DESC, d.added_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(readDocument(cursor))
        }
        return out
    }

    fun document(id: Long): Document? {
        readableDatabase.rawQuery(
            "SELECT d.*, (SELECT COUNT(*) FROM cards c WHERE c.doc_id = d.id) AS card_count\n" +
                "FROM documents d WHERE d.id = ?",
            arrayOf(id.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) readDocument(cursor) else null
        }
    }

    fun updateLastPage(id: Long, page: Int) {
        writableDatabase.execSQL(
            "UPDATE documents SET last_page = ?, last_read_at = ? WHERE id = ?",
            arrayOf<Any>(page, System.currentTimeMillis(), id)
        )
    }

    fun renameDocument(id: Long, title: String) {
        writableDatabase.execSQL(
            "UPDATE documents SET title = ? WHERE id = ?",
            arrayOf<Any>(title, id)
        )
    }

    fun deleteDocument(id: Long) {
        writableDatabase.delete("documents", "id = ?", arrayOf(id.toString()))
    }

    fun allCards(query: String = "", docId: Long = 0L): List<Card> {
        val args = mutableListOf<String>()
        val where = StringBuilder(" WHERE 1=1")
        if (docId > 0L) {
            where.append(" AND c.doc_id = ?")
            args.add(docId.toString())
        }
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isNotEmpty()) {
            where.append(" AND (LOWER(c.word) LIKE ? OR LOWER(c.translation) LIKE ? OR LOWER(c.source_line) LIKE ?)")
            val like = "%$q%"
            args.add(like)
            args.add(like)
            args.add(like)
        }
        where.append(" ORDER BY c.created_at DESC")
        return queryCards(
            "\n            SELECT c.*, d.title AS doc_title\n" +
                "            FROM cards c JOIN documents d ON d.id = c.doc_id\n" +
                "        " + where,
            args.toTypedArray(),
        )
    }

    fun cardsForDocument(docId: Long): List<Card> = allCards("", docId)

    fun insertCard(card: Card): Long {
        val values = cardValues(card)
        return writableDatabase.insertWithOnConflict(
            "cards",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun importCard(card: Card): Long = insertCard(card)

    fun updateCard(card: Card) {
        val values = ContentValues().apply {
            put("translation", card.translation)
            put("note", card.note)
            put("suspended", if (card.suspended) 1 else 0)
            put("audio_path", card.audioPath)
            put("crop_path", card.cropPath)
        }
        writableDatabase.update("cards", values, "id = ?", arrayOf(card.id.toString()))
    }

    fun deleteCard(id: Long) {
        writableDatabase.delete("cards", "id = ?", arrayOf(id.toString()))
    }

    fun dueLearning(now: Long, limit: Int): List<Card> = queryCards(
        "\n            SELECT c.*, d.title AS doc_title\n" +
            "            FROM cards c JOIN documents d ON d.id = c.doc_id\n" +
            "        WHERE c.suspended = 0 AND c.state IN (?, ?) AND c.due_at <= ?\n" +
            "ORDER BY c.due_at ASC LIMIT ?",
        arrayOf(CardState.LEARNING.ordinal.toString(), CardState.RELEARNING.ordinal.toString(), now.toString(), limit.toString()),
    )

    fun dueReviews(now: Long, limit: Int): List<Card> = queryCards(
        "\n            SELECT c.*, d.title AS doc_title\n" +
            "            FROM cards c JOIN documents d ON d.id = c.doc_id\n" +
            "        WHERE c.suspended = 0 AND c.state = ? AND c.due_at <= ?\n" +
            "ORDER BY c.due_at ASC LIMIT ?",
        arrayOf(CardState.REVIEW.ordinal.toString(), now.toString(), limit.toString()),
    )

    fun newCards(limit: Int): List<Card> {
        if (limit <= 0) return emptyList()
        return queryCards(
            "\n            SELECT c.*, d.title AS doc_title\n" +
            "            FROM cards c JOIN documents d ON d.id = c.doc_id\n" +
            "        WHERE c.suspended = 0 AND c.state = ?\n" +
            "ORDER BY c.created_at ASC LIMIT ?",
            arrayOf(CardState.NEW.ordinal.toString(), limit.toString()),
        )
    }

    fun stats(): DeckStats {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val midnight = cal.timeInMillis
        val db = readableDatabase
        return DeckStats(
            total = count(db, "SELECT COUNT(*) FROM cards WHERE suspended = 0"),
            newCards = count(db, "SELECT COUNT(*) FROM cards WHERE suspended = 0 AND state = 0"),
            dueCards = count(db, "SELECT COUNT(*) FROM cards WHERE suspended = 0 AND state = 2 AND due_at <= $now"),
            learningCards = count(db, "SELECT COUNT(*) FROM cards WHERE suspended = 0 AND state IN (1,3) AND due_at <= $now"),
            suspended = count(db, "SELECT COUNT(*) FROM cards WHERE suspended = 1"),
            reviewedToday = count(db, "SELECT COUNT(*) FROM reviews WHERE reviewed_at >= $midnight"),
            newToday = count(db, "SELECT COUNT(*) FROM reviews WHERE reviewed_at >= $midnight AND prev_state = 0"),
        )
    }

    fun recentlyReviewed(limit: Int = 8): List<Pair<Card, Long>> {
        val out = mutableListOf<Pair<Card, Long>>()
        queryCards(
            "\n            SELECT c.*, d.title AS doc_title\n" +
            "            FROM cards c JOIN documents d ON d.id = c.doc_id\n" +
            "         WHERE c.last_review > 0 ORDER BY c.last_review DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).forEach { out.add(it to it.lastReview) }
        return out
    }

    fun recordReview(card: Card, rating: Int, outcome: SrsOutcome): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("due_at", outcome.dueAt)
                put("interval_days", outcome.intervalDays)
                put("ease", outcome.ease)
                put("reps", outcome.reps)
                put("lapses", outcome.lapses)
                put("state", outcome.state)
                put("step", outcome.step)
                put("last_review", System.currentTimeMillis())
            }
            db.update("cards", values, "id = ?", arrayOf(card.id.toString()))
            val log = ContentValues().apply {
                put("card_id", card.id)
                put("reviewed_at", System.currentTimeMillis())
                put("rating", rating)
                put("prev_interval", card.intervalDays)
                put("new_interval", outcome.intervalDays)
                put("prev_state", card.state)
                put("new_state", outcome.state)
            }
            val id = db.insertOrThrow("reviews", null, log)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun undoReview(cardBefore: Card, reviewId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("due_at", cardBefore.dueAt)
                put("interval_days", cardBefore.intervalDays)
                put("ease", cardBefore.ease)
                put("reps", cardBefore.reps)
                put("lapses", cardBefore.lapses)
                put("state", cardBefore.state)
                put("step", cardBefore.step)
                put("last_review", cardBefore.lastReview)
            }
            db.update("cards", values, "id = ?", arrayOf(cardBefore.id.toString()))
            db.delete("reviews", "id = ?", arrayOf(reviewId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun queryCards(sql: String, args: Array<String>?): List<Card> {
        val out = mutableListOf<Card>()
        readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) out.add(readCard(cursor))
        }
        return out
    }

    private fun count(db: SQLiteDatabase, sql: String): Int {
        db.rawQuery(sql, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun readDocument(cursor: Cursor): Document = Document(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
        path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
        pageCount = cursor.getInt(cursor.getColumnIndexOrThrow("page_count")),
        addedAt = cursor.getLong(cursor.getColumnIndexOrThrow("added_at")),
        lastPage = cursor.getInt(cursor.getColumnIndexOrThrow("last_page")),
        lastReadAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_read_at")),
        cardCount = cursor.getInt(cursor.getColumnIndexOrThrow("card_count")),
    )

    private fun readCard(cursor: Cursor): Card = Card(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        docId = cursor.getLong(cursor.getColumnIndexOrThrow("doc_id")),
        word = cursor.getString(cursor.getColumnIndexOrThrow("word")),
        normWord = cursor.getString(cursor.getColumnIndexOrThrow("norm_word")),
        translation = cursor.getString(cursor.getColumnIndexOrThrow("translation")),
        ipa = cursor.getString(cursor.getColumnIndexOrThrow("ipa")),
        pos = cursor.getString(cursor.getColumnIndexOrThrow("pos")),
        sourceLine = cursor.getString(cursor.getColumnIndexOrThrow("source_line")),
        sentence = cursor.getString(cursor.getColumnIndexOrThrow("sentence")),
        page = cursor.getInt(cursor.getColumnIndexOrThrow("page")),
        cropPath = cursor.getString(cursor.getColumnIndexOrThrow("crop_path")),
        rectL = cursor.getFloat(cursor.getColumnIndexOrThrow("rect_l")),
        rectT = cursor.getFloat(cursor.getColumnIndexOrThrow("rect_t")),
        rectR = cursor.getFloat(cursor.getColumnIndexOrThrow("rect_r")),
        rectB = cursor.getFloat(cursor.getColumnIndexOrThrow("rect_b")),
        audioPath = cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
        dueAt = cursor.getLong(cursor.getColumnIndexOrThrow("due_at")),
        intervalDays = cursor.getDouble(cursor.getColumnIndexOrThrow("interval_days")),
        ease = cursor.getDouble(cursor.getColumnIndexOrThrow("ease")),
        reps = cursor.getInt(cursor.getColumnIndexOrThrow("reps")),
        lapses = cursor.getInt(cursor.getColumnIndexOrThrow("lapses")),
        state = cursor.getInt(cursor.getColumnIndexOrThrow("state")),
        step = cursor.getInt(cursor.getColumnIndexOrThrow("step")),
        lastReview = cursor.getLong(cursor.getColumnIndexOrThrow("last_review")),
        suspended = cursor.getInt(cursor.getColumnIndexOrThrow("suspended")) != 0,
        note = cursor.getString(cursor.getColumnIndexOrThrow("note")),
        docTitle = cursor.getString(cursor.getColumnIndexOrThrow("doc_title")),
    )

    private fun cardValues(card: Card): ContentValues = ContentValues().apply {
        put("doc_id", card.docId)
        put("word", card.word)
        put("norm_word", card.normWord)
        put("translation", card.translation)
        put("ipa", card.ipa)
        put("pos", card.pos)
        put("source_line", card.sourceLine)
        put("sentence", card.sentence)
        put("page", card.page)
        put("crop_path", card.cropPath)
        put("rect_l", card.rectL)
        put("rect_t", card.rectT)
        put("rect_r", card.rectR)
        put("rect_b", card.rectB)
        put("audio_path", card.audioPath)
        put("created_at", card.createdAt)
        put("due_at", card.dueAt)
        put("interval_days", card.intervalDays)
        put("ease", card.ease)
        put("reps", card.reps)
        put("lapses", card.lapses)
        put("state", card.state)
        put("step", card.step)
        put("last_review", card.lastReview)
        put("suspended", if (card.suspended) 1 else 0)
        put("note", card.note)
    }
}
