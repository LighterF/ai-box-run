package ir.lexilens.reader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ir.lexilens.reader.data.Card
import ir.lexilens.reader.databinding.ItemCardBinding
import ir.lexilens.reader.util.TextUtils

class CardAdapter(
    private val onClick: (Card) -> Unit,
) : ListAdapter<Card, CardAdapter.VH>(Diff) {

    inner class VH(val binding: ItemCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(card: Card) {
            binding.cardWord.text = card.word
            binding.cardTranslation.text = TextUtils.translationLines(card.translation)
                .take(2).joinToString(" · ").ifBlank { "بدون معنی — برای ویرایش ضربه بزن" }
            binding.cardSource.text = card.sourceLine.ifBlank { card.docTitle }
            binding.cardDue.text = TextUtils.dueLabel(card)
            binding.root.setOnClickListener { onClick(card) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    object Diff : DiffUtil.ItemCallback<Card>() {
        override fun areItemsTheSame(oldItem: Card, newItem: Card) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Card, newItem: Card) = oldItem == newItem
    }
}
