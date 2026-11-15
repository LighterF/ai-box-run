package ir.lexilens.reader.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import ir.lexilens.reader.App
import ir.lexilens.reader.data.Document
import ir.lexilens.reader.databinding.FragmentLibraryBinding
import ir.lexilens.reader.pdf.PageRenderer
import ir.lexilens.reader.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DocumentAdapter

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DocumentAdapter(
            onClick = { (requireActivity() as MainActivity).openReader(it.id) },
            onMenu = ::showDocumentMenu,
        )
        binding.docList.layoutManager = LinearLayoutManager(requireContext())
        binding.docList.adapter = adapter
        binding.importButton.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        binding.emptyImportButton.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        binding.docList.itemAnimator = null
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val app = requireActivity().application as App
        lifecycleScope.launch {
            val docs = withContext(Dispatchers.IO) { app.db.documents() }
            if (_binding == null) return@launch
            adapter.submitList(docs)
            binding.emptyState.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
            binding.docList.visibility = if (docs.isEmpty()) View.GONE else View.VISIBLE
            val cards = docs.sumOf { it.cardCount }
            binding.librarySubtitle.text = if (docs.isEmpty()) {
                "PDF انگلیسی را باز کن، واژه را نگه دار، معنی را بفهم."
            } else {
                "${TextUtils.faNumber(docs.size)} کتاب · ${TextUtils.faNumber(cards)} کارت"
            }
        }
    }

    private fun showDocumentMenu(doc: Document, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "باز کردن")
        popup.menu.add(0, 2, 1, "کارت‌های این کتاب")
        popup.menu.add(0, 3, 2, "مرور همین کتاب")
        popup.menu.add(0, 4, 3, "تغییر نام")
        popup.menu.add(0, 5, 4, "حذف")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> (requireActivity() as MainActivity).openReader(doc.id)
                2 -> (requireActivity() as MainActivity).showCardsForDocument(doc.id, doc.title)
                3 -> (requireActivity() as MainActivity).openReview(doc.id, doc.title)
                4 -> rename(doc)
                5 -> confirmDelete(doc)
            }
            true
        }
        popup.show()
    }

    private fun rename(doc: Document) {
        val input = EditText(requireContext()).apply {
            setText(doc.title)
            setSelectAllOnFocus(true)
            setPadding(dp(22), dp(12), dp(22), dp(8))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("تغییر نام کتاب")
            .setView(input)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val title = input.text.toString().trim().ifBlank { doc.title }
                val app = requireActivity().application as App
                lifecycleScope.launch(Dispatchers.IO) {
                    app.db.writableDatabase.execSQL(
                        "UPDATE documents SET title = ? WHERE id = ?",
                        arrayOf(title, doc.id)
                    )
                    withContext(Dispatchers.Main) { refresh() }
                }
            }
            .show()
    }

    private fun confirmDelete(doc: Document) {
        val message = if (doc.cardCount > 0) {
            "کتاب و ${TextUtils.faNumber(doc.cardCount)} کارت آن حذف می‌شوند. تصویر جمله‌ها و صداها هم پاک می‌شوند."
        } else {
            "این کتاب از کتابخانه حذف می‌شود."
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("حذف «${doc.title}»؟")
            .setMessage(message)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف") { _, _ ->
                val app = requireActivity().application as App
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val cards = app.db.cardsForDocument(doc.id)
                        cards.forEach { c ->
                            if (c.cropPath.isNotBlank()) runCatching { File(c.cropPath).delete() }
                            if (c.audioPath.isNotBlank()) runCatching { File(c.audioPath).delete() }
                        }
                        app.db.deleteDocument(doc.id)
                        runCatching { File(File(requireContext().filesDir, "text"), doc.id.toString()).deleteRecursively() }
                    }
                    refresh()
                }
            }
            .show()
    }

    private fun importPdf(uri: Uri) {
        val app = requireActivity().application as App
        val resolver = requireContext().contentResolver
        binding.importProgress.visibility = View.VISIBLE
        binding.importButton.isEnabled = false
        binding.emptyImportButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryName(uri)
                    val title = name.substringBeforeLast('.').replace('_', ' ').trim()
                        .ifBlank { "بدون عنوان" }
                    val dir = File(requireContext().filesDir, "pdf").apply { mkdirs() }
                    val target = File(dir, "doc_${System.currentTimeMillis()}.pdf")
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().buffered().use { out -> input.copyTo(out) }
                    } ?: error("فایل قابل خواندن نیست")
                    if (target.length() < 1024) error("فایل خالی یا خراب است")
                    val pages = PageRenderer(target).use { it.pageCount }
                    if (pages <= 0) error("این PDF صفحه‌ای ندارد")
                    val id = app.db.addDocument(title, target.absolutePath, pages)
                    id to title
                }
            }
            if (_binding == null) return@launch
            binding.importProgress.visibility = View.GONE
            binding.importButton.isEnabled = true
            binding.emptyImportButton.isEnabled = true
            result.onSuccess { (id, title) ->
                refresh()
                Snackbar.make(binding.root, "«$title» اضافه شد", Snackbar.LENGTH_SHORT).show()
                (requireActivity() as MainActivity).openReader(id)
            }.onFailure { e ->
                Snackbar.make(
                    binding.root,
                    "افزودن PDF نشد: ${e.message ?: "خطای ناشناخته"}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun queryName(uri: Uri): String {
        runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        binding.docList.adapter = null
        _binding = null
    }
}
