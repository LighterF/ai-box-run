package ir.lexilens.reader.data

object CardLookup {
    data class Resolution(val samePage: Card?, val savedMeaning: Card?)

    fun resolve(savedCards: List<Card>, page: Int): Resolution {
        val samePage = savedCards.firstOrNull { it.page == page }
        val savedMeaning = samePage?.takeIf { it.translation.isNotBlank() }
            ?: savedCards.firstOrNull { it.translation.isNotBlank() }
        return Resolution(samePage, savedMeaning)
    }
}
