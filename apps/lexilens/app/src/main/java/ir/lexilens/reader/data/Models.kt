package ir.lexilens.reader.data

import java.io.Serializable

enum class CardState { NEW, LEARNING, REVIEW, RELEARNING }

enum class Rating { AGAIN, HARD, GOOD, EASY }

data class Card(
    val id: Long = 0L,
    val docId: Long,
    val word: String,
    val normWord: String,
    val translation: String = "",
    val ipa: String = "",
    val pos: String = "",
    val sourceLine: String = "",
    val sentence: String = "",
    val contextRev: Int = 0,
    val page: Int = 0,
    val cropPath: String = "",
    val rectL: Float = 0f,
    val rectT: Float = 0f,
    val rectR: Float = 0f,
    val rectB: Float = 0f,
    val audioPath: String = "",
    val createdAt: Long = 0L,
    val dueAt: Long = 0L,
    val intervalDays: Double = 0.0,
    val ease: Double = 2.5,
    val reps: Int = 0,
    val lapses: Int = 0,
    val state: Int = CardState.NEW.ordinal,
    val step: Int = 0,
    val lastReview: Long = 0L,
    val suspended: Boolean = false,
    val note: String = "",
    val docTitle: String = "",
) : Serializable

data class Document(
    val id: Long,
    val title: String,
    val path: String,
    val pageCount: Int,
    val addedAt: Long,
    val lastPage: Int,
    val lastReadAt: Long = 0L,
    val cardCount: Int = 0,
) : Serializable

data class DeckStats(
    val total: Int,
    val newCards: Int,
    val dueCards: Int,
    val learningCards: Int,
    val suspended: Int,
    val reviewedToday: Int,
    val newToday: Int,
)

data class DictionaryEntry(
    val word: String,
    val display: String,
    val ipa: String,
    val pos: String,
    val translations: List<String>,
    val glosses: List<String>,
    val source: String,
    val matchedForm: String? = null,
)

data class SrsOutcome(
    val state: Int,
    val dueAt: Long,
    val intervalDays: Double,
    val ease: Double,
    val reps: Int,
    val lapses: Int,
    val step: Int,
)
