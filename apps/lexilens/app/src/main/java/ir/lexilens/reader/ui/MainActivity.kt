package ir.lexilens.reader.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import ir.lexilens.reader.R
import ir.lexilens.reader.databinding.ActivityMainBinding
import ir.lexilens.reader.reader.ReaderActivity
import ir.lexilens.reader.review.ReviewActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val tags = mapOf(
        R.id.nav_library to "library",
        R.id.nav_review to "review",
        R.id.nav_cards to "cards",
        R.id.nav_settings to "settings",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            show(tags[item.itemId] ?: "library")
            true
        }
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_library
        } else {
            val tag = savedInstanceState.getString("tab", "library")
            binding.bottomNav.selectedItemId = tags.entries.firstOrNull { it.value == tag }?.key ?: R.id.nav_library
            show(tag)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val current = tags.entries.firstOrNull { it.value == currentTag() }?.value ?: "library"
        outState.putString("tab", current)
    }

    private fun currentTag(): String {
        val fragment = supportFragmentManager.fragments.firstOrNull { !it.isHidden } ?: return "library"
        return fragment.tag ?: "library"
    }

    private fun show(tag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        var target: Fragment? = fm.findFragmentByTag(tag)
        if (target == null) {
            target = when (tag) {
                "review" -> ReviewHomeFragment()
                "cards" -> BrowseFragment()
                "settings" -> SettingsFragment()
                else -> LibraryFragment()
            }
            tx.add(R.id.fragment_container, target, tag)
        }
        for (f in fm.fragments) {
            if (f !== target) tx.hide(f)
        }
        tx.show(target)
        tx.commitAllowingStateLoss()
    }

    fun openReader(docId: Long) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_DOC_ID, docId))
    }

    fun openReview(docId: Long = 0L, docTitle: String? = null) {
        startActivity(
            Intent(this, ReviewActivity::class.java)
                .putExtra(ReviewActivity.EXTRA_DOC_ID, docId)
                .putExtra(ReviewActivity.EXTRA_DOC_TITLE, docTitle)
        )
    }

    fun showCardsForDocument(docId: Long, title: String) {
        binding.bottomNav.selectedItemId = R.id.nav_cards
        show("cards")
        val f = supportFragmentManager.findFragmentByTag("cards") as? BrowseFragment
        f?.setDocumentFilter(docId, title)
    }

    fun switchToLibrary() {
        binding.bottomNav.selectedItemId = R.id.nav_library
        show("library")
    }

    fun switchToReview() {
        binding.bottomNav.selectedItemId = R.id.nav_review
        show("review")
    }
}
