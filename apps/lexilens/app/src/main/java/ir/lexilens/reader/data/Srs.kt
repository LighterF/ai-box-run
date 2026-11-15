package ir.lexilens.reader.data

import ir.lexilens.reader.util.TextUtils
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

object Srs {
    private const val MIN_EASE = 1.3
    private const val MAX_EASE = 3.1
    private const val MINUTE = 60_000L
    private const val DAY = 86_400_000.0

    private val learningSteps = intArrayOf(1, 10)

    fun grade(
        card: Card,
        rating: Rating,
        now: Long = System.currentTimeMillis(),
        fuzz: Boolean = true,
    ): SrsOutcome = outcome(card, rating, now, fuzz)

    fun outcome(
        card: Card,
        rating: Rating,
        now: Long = System.currentTimeMillis(),
        fuzz: Boolean = true,
    ): SrsOutcome {
        val state = CardState.values().getOrElse(card.state) { CardState.NEW }
        val ease = card.ease.coerceIn(MIN_EASE, MAX_EASE)
        val reps = card.reps + 1
        return when (state) {
            CardState.NEW, CardState.LEARNING -> {
                val step = card.step.coerceIn(0, 1)
                when (rating) {
                    Rating.AGAIN -> SrsOutcome(
                        CardState.LEARNING.ordinal,
                        now + learningSteps[0] * MINUTE,
                        0.0,
                        ease,
                        reps,
                        card.lapses,
                        0,
                    )
                    Rating.HARD -> SrsOutcome(
                        CardState.LEARNING.ordinal,
                        now + 6 * MINUTE,
                        0.0,
                        ease,
                        reps,
                        card.lapses,
                        step,
                    )
                    Rating.GOOD -> if (step < 1) {
                        SrsOutcome(
                            CardState.LEARNING.ordinal,
                            now + learningSteps[1] * MINUTE,
                            0.0,
                            ease,
                            reps,
                            card.lapses,
                            step + 1,
                        )
                    } else {
                        SrsOutcome(
                            CardState.REVIEW.ordinal,
                            now + dueMillis(1.0, false),
                            1.0,
                            ease,
                            reps,
                            card.lapses,
                            0,
                        )
                    }
                    Rating.EASY -> SrsOutcome(
                        CardState.REVIEW.ordinal,
                        now + dueMillis(4.0, false),
                        4.0,
                        min(MAX_EASE, ease + 0.15),
                        reps,
                        card.lapses,
                        0,
                    )
                }
            }
            CardState.REVIEW -> {
                val base = max(1.0, card.intervalDays)
                when (rating) {
                    Rating.AGAIN -> SrsOutcome(
                        CardState.RELEARNING.ordinal,
                        now + 10 * MINUTE,
                        round2(max(1.0, base * 0.2)),
                        max(MIN_EASE, ease - 0.2),
                        reps,
                        card.lapses + 1,
                        0,
                    )
                    Rating.HARD -> {
                        val interval = round2(base * 1.2)
                        SrsOutcome(
                            CardState.REVIEW.ordinal,
                            now + dueMillis(interval, fuzz),
                            interval,
                            max(MIN_EASE, ease - 0.15),
                            reps,
                            card.lapses,
                            0,
                        )
                    }
                    Rating.GOOD -> {
                        val interval = round2(base * ease)
                        SrsOutcome(
                            CardState.REVIEW.ordinal,
                            now + dueMillis(interval, fuzz),
                            interval,
                            ease,
                            reps,
                            card.lapses,
                            0,
                        )
                    }
                    Rating.EASY -> {
                        val interval = round2(base * ease * 1.3)
                        SrsOutcome(
                            CardState.REVIEW.ordinal,
                            now + dueMillis(interval, fuzz),
                            interval,
                            min(MAX_EASE, ease + 0.15),
                            reps,
                            card.lapses,
                            0,
                        )
                    }
                }
            }
            CardState.RELEARNING -> {
                val base = max(1.0, card.intervalDays)
                when (rating) {
                    Rating.AGAIN -> SrsOutcome(
                        CardState.RELEARNING.ordinal,
                        now + 10 * MINUTE,
                        base,
                        max(MIN_EASE, ease - 0.2),
                        reps,
                        card.lapses + 1,
                        0,
                    )
                    Rating.HARD -> SrsOutcome(
                        CardState.RELEARNING.ordinal,
                        now + 10 * MINUTE,
                        base,
                        ease,
                        reps,
                        card.lapses,
                        0,
                    )
                    Rating.GOOD -> SrsOutcome(
                        CardState.REVIEW.ordinal,
                        now + dueMillis(base, false),
                        base,
                        ease,
                        reps,
                        card.lapses,
                        0,
                    )
                    Rating.EASY -> {
                        val interval = max(4.0, base * 1.5)
                        SrsOutcome(
                            CardState.REVIEW.ordinal,
                            now + dueMillis(interval, false),
                            interval,
                            min(MAX_EASE, ease + 0.15),
                            reps,
                            card.lapses,
                            0,
                        )
                    }
                }
            }
        }
    }

    fun preview(card: Card, rating: Rating, now: Long = System.currentTimeMillis()): String {
        val outcome = outcome(card, rating, now, false)
        if (outcome.state != CardState.LEARNING.ordinal && outcome.state != CardState.RELEARNING.ordinal) {
            val days = outcome.intervalDays
            if (days < 1.0) {
                return TextUtils.faNumber(max(1, (days * 24).roundToInt())) + " ساعت"
            }
            val wholeDays = days.roundToInt()
            if (wholeDays < 30) return TextUtils.faNumber(wholeDays) + " روز"
            if (wholeDays < 365) {
                return TextUtils.faNumber(max(1, (wholeDays / 30.0).roundToInt())) + " ماه"
            }
            val years = String.format(Locale.US, "%.1f", wholeDays / 365.0).replace('.', '٫')
            return TextUtils.faDigits(years) + " سال"
        }
        val minutes = max(1, ((outcome.dueAt - now) / (60.0 * 1000.0)).roundToLong().toInt())
        if (minutes < 60) return TextUtils.faNumber(minutes) + " دقیقه"
        if (minutes == 60) return "۱ ساعت"
        val extra = minutes % 60
        return if (extra == 0) {
            TextUtils.faNumber(minutes / 60) + " ساعت"
        } else {
            TextUtils.faNumber(minutes / 60) + " ساعت و " + TextUtils.faNumber(extra) + " دقیقه"
        }
    }

    private fun dueMillis(days: Double, fuzz: Boolean): Long {
        var value = days
        if (fuzz && value > 2.0) {
            value *= 0.95 + Random.nextDouble() * 0.1
        }
        val millis = (value * DAY).roundToLong()
        return max(MINUTE, millis)
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0
}
