package ir.lexilens.reader

import ir.lexilens.reader.data.Card
import ir.lexilens.reader.data.CardLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardLookupTest {

    @Test
    fun samePageCardIsTheOneThatGetsUpdated() {
        val other = card(id = 1L, page = 2, translation = "معنی قدیمی")
        val same = card(id = 2L, page = 5, translation = "معنی من")
        val resolved = CardLookup.resolve(listOf(other, same), page = 5)
        assertEquals(2L, resolved.samePage?.id)
        assertEquals("معنی من", resolved.savedMeaning?.translation)
    }

    @Test
    fun savedMeaningIsPrefilledEvenWhenNoCardOnThisPage() {
        val other = card(id = 1L, page = 2, translation = "معنی من")
        val resolved = CardLookup.resolve(listOf(other), page = 9)
        assertNull(resolved.samePage)
        assertEquals("معنی من", resolved.savedMeaning?.translation)
    }

    @Test
    fun blankSamePageCardFallsBackToTheSavedMeaningElsewhere() {
        val blank = card(id = 1L, page = 4, translation = "")
        val saved = card(id = 2L, page = 8, translation = "دست‌نویس من")
        val resolved = CardLookup.resolve(listOf(blank, saved), page = 4)
        assertEquals(1L, resolved.samePage?.id)
        assertEquals("دست‌نویس من", resolved.savedMeaning?.translation)
    }

    @Test
    fun noCardsMeansDictionaryBehaviourStays() {
        val resolved = CardLookup.resolve(emptyList(), page = 0)
        assertNull(resolved.samePage)
        assertNull(resolved.savedMeaning)
    }

    private fun card(id: Long, page: Int, translation: String) = Card(
        id = id,
        docId = 7L,
        word = "odd",
        normWord = "odd",
        translation = translation,
        page = page,
    )
}
