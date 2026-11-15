package ir.lexilens.reader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ir.lexilens.reader.data.Document
import ir.lexilens.reader.databinding.ItemDocumentBinding
import ir.lexilens.reader.util.TextUtils

class DocumentAdapter(
    private val onClick: (Document) -> Unit,
    private val onMenu: (Document, android.view.View) -> Unit,
) : ListAdapter<Document, DocumentAdapter.VH>(Diff) {

    inner class VH(val binding: ItemDocumentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(doc: Document) {
            binding.docTitle.text = doc.title
            val pages = TextUtils.faNumber(doc.pageCount)
            val last = if (doc.lastReadAt > 0) " · ${TextUtils.relativeTime(doc.lastReadAt)}" else ""
            binding.docMeta.text = "$pages صفحه$last"
            val pct = if (doc.pageCount > 0) {
                ((doc.lastPage + 1).toFloat() / doc.pageCount * 100).toInt().coerceIn(0, 100)
            } else 0
            binding.docProgress.progress = pct
            binding.docPercent.text = "${TextUtils.faNumber(pct)}٪"
            binding.docCards.text = if (doc.cardCount > 0) {
                "${TextUtils.faNumber(doc.cardCount)} کارت ساخته‌شده"
            } else {
                "هنوز کارتی ندارد — اولین واژه را انتخاب کن"
            }
            binding.docInitial.text = doc.title.trim().take(1).ifBlank { "P" }.uppercase()
            binding.root.setOnClickListener { onClick(doc) }
            binding.docMenu.setOnClickListener { onMenu(doc, it) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    object Diff : DiffUtil.ItemCallback<Document>() {
        override fun areItemsTheSame(oldItem: Document, newItem: Document) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Document, newItem: Document) = oldItem == newItem
    }
}
