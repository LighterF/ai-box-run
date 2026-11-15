package ir.lexilens.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ir.lexilens.reader.App
import ir.lexilens.reader.databinding.FragmentBrowseBinding
import ir.lexilens.reader.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CardAdapter
    private var docId = 0L
    private var docTitle: String? = null
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = CardAdapter { card ->
            CardDetailSheet.show(requireContext(), card) { load() }
        }
        binding.cardsList.layoutManager = LinearLayoutManager(requireContext())
        binding.cardsList.adapter = adapter
        binding.searchInput.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(220)
                load()
            }
        }
        binding.filterChip.setOnClickListener {
            docId = 0L
            docTitle = null
            updateChip()
            load()
        }
        updateChip()
    }

    fun setDocumentFilter(id: Long, title: String) {
        docId = id
        docTitle = title
        updateChip()
        if (_binding != null) load()
    }

    private fun updateChip() {
        val b = _binding ?: return
        val title = docTitle
        if (docId > 0 && title != null) {
            b.filterChip.text = "فیلتر: $title  ×"
            b.filterChip.visibility = View.VISIBLE
        } else {
            b.filterChip.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val app = requireActivity().application as App
        val query = _binding?.searchInput?.text?.toString()?.trim().orEmpty()
        val filterId = docId
        lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) { app.db.allCards(query, filterId) }
            if (_binding == null) return@launch
            adapter.submitList(cards)
            binding.browseEmpty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
            binding.cardsList.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
            binding.browseCount.text = buildString {
                append(TextUtils.faNumber(cards.size)).append(" کارت")
                if (query.isNotBlank()) append(" برای «").append(query).append("»")
            }
            if (cards.isEmpty()) {
                binding.browseEmpty.text = when {
                    query.isNotBlank() -> "چیزی برای «$query» پیدا نشد."
                    filterId > 0 -> "این کتاب هنوز کارتی ندارد. یک PDF باز کن و واژه‌ای را نگه دار."
                    else -> "هنوز کارتی نساخته‌ای. یک PDF باز کن و روی واژه‌های ناآشنا نگه دار."
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        binding.cardsList.adapter = null
        _binding = null
    }
}
