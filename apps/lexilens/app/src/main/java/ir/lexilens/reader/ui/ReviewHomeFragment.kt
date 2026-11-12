package ir.lexilens.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ir.lexilens.reader.App
import ir.lexilens.reader.data.Prefs
import ir.lexilens.reader.databinding.FragmentReviewHomeBinding
import ir.lexilens.reader.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class ReviewHomeFragment : Fragment() {

    private var _binding: FragmentReviewHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CardAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentReviewHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = CardAdapter { card ->
            CardDetailSheet.show(requireContext(), card, onChanged = { refresh() })
        }
        binding.recentList.layoutManager = LinearLayoutManager(requireContext())
        binding.recentList.adapter = adapter
        binding.recentList.isNestedScrollingEnabled = false
        binding.startReview.setOnClickListener {
            (requireActivity() as MainActivity).openReview()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val app = requireActivity().application as App
        val prefs = Prefs(requireContext())
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { app.db.stats() }
            val recent = withContext(Dispatchers.IO) { app.db.recentlyReviewed(8) }
            if (_binding == null) return@launch
            val reviewRemaining = (prefs.reviewsPerDay - stats.reviewedToday).coerceAtLeast(0)
            val newRemaining = (prefs.newPerDay - stats.newToday).coerceAtLeast(0)
            val due = stats.learningCards + min(stats.dueCards, reviewRemaining)
            val fresh = min(stats.newCards, newRemaining)
            val total = due + fresh
            binding.dueCount.text = TextUtils.faNumber(total)
            binding.dueDetail.text = buildString {
                append(TextUtils.faNumber(fresh)).append(" جدید")
                append(" · ").append(TextUtils.faNumber(stats.learningCards)).append(" در حال یادگیری")
                if (stats.dueCards > 0) {
                    append(" · ").append(TextUtils.faNumber(min(stats.dueCards, reviewRemaining))).append(" مرور")
                }
            }
            binding.totalCards.text = TextUtils.faNumber(stats.total)
            binding.reviewedToday.text = TextUtils.faNumber(stats.reviewedToday)
            adapter.submitList(recent.map { it.first })
            binding.noCardsState.visibility = if (stats.total == 0) View.VISIBLE else View.GONE
            binding.recentList.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
            binding.startReview.isEnabled = total > 0
            binding.startReview.text = when {
                total == 0 && stats.total > 0 -> "برای امروز تمام شد"
                total == 0 -> "اول یک کارت بساز"
                else -> "شروع مرور (${TextUtils.faNumber(total)})"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recentList.adapter = null
        _binding = null
    }
}
