package ir.lexilens.reader

import ir.lexilens.reader.data.Card
import ir.lexilens.reader.data.CardState
import ir.lexilens.reader.data.Rating
import ir.lexilens.reader.data.Srs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrsTest {
    private val now = 1_700_000_000_000L

    private fun card(
        state: CardState = CardState.NEW,
        step: Int = 0,
        interval: Double = 0.0,
        ease: Double = 2.5,
        reps: Int = 0,
        lapses: Int = 0,
    ) = Card(
        id = 1, docId = 1, word = "read", normWord = "read", translation = "خواندن",
        ipa = "", pos = "", sourceLine = "", sentence = "", page = 0, cropPath = "",
        rectL = 0f, rectT = 0f, rectR = 1f, rectB = 1f, audioPath = "",
        createdAt = now, dueAt = now, intervalDays = interval, ease = ease,
        reps = reps, lapses = lapses, state = state.ordinal, step = step,
        lastReview = 0, suspended = false, note = "",
    )

    @Test
    fun learningStepsGraduate() {
        val first = Srs.grade(card(), Rating.GOOD, now, fuzz = false)
        assertEquals(CardState.LEARNING.ordinal, first.state)
        assertEquals(10 * 60_000L, first.dueAt - now)
        val second = Srs.grade(card(CardState.LEARNING, step = 1), Rating.GOOD, now, fuzz = false)
        assertEquals(CardState.REVIEW.ordinal, second.state)
        assertEquals(1.0, second.intervalDays, 0.001)
    }

    @Test
    fun easyGraduatesFourDays() {
        val out = Srs.grade(card(), Rating.EASY, now, fuzz = false)
        assertEquals(CardState.REVIEW.ordinal, out.state)
        assertEquals(4.0, out.intervalDays, 0.001)
        assertEquals(4 * 86_400_000L, out.dueAt - now)
    }

    @Test
    fun reviewGrowsByEase() {
        val out = Srs.grade(card(CardState.REVIEW, interval = 10.0), Rating.GOOD, now, fuzz = false)
        assertEquals(CardState.REVIEW.ordinal, out.state)
        assertEquals(25.0, out.intervalDays, 0.001)
    }

    @Test
    fun againEntersRelearning() {
        val out = Srs.grade(card(CardState.REVIEW, interval = 10.0), Rating.AGAIN, now, fuzz = false)
        assertEquals(CardState.RELEARNING.ordinal, out.state)
        assertEquals(1, out.lapses)
        assertTrue(out.dueAt - now <= 10 * 60_000L)
    }

    @Test
    fun previewUsesPersianDigits() {
        val p = Srs.preview(card(), Rating.GOOD, now)
        assertTrue(p.contains("دقیقه"))
        assertTrue(p.any { it in '۰'..'۹' })
    }
}
