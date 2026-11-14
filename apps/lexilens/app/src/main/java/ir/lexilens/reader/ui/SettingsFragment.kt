package ir.lexilens.reader.ui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import ir.lexilens.reader.App
import ir.lexilens.reader.BuildConfig
import ir.lexilens.reader.data.Prefs
import ir.lexilens.reader.databinding.FragmentSettingsBinding
import ir.lexilens.reader.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs

    private val csvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportCsv(uri)
    }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backup(uri)
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restore(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs(requireContext())
        bind()
        binding.rowNewPerDay.setOnClickListener {
            chooseCount(
                "کارت جدید در روز",
                prefs.newPerDay,
                intArrayOf(0, 5, 10, 20, 30, 50, 100, 200),
                Prefs.MAX_NEW_PER_DAY,
            ) { prefs.newPerDay = it; bind() }
        }
        binding.rowReviewsPerDay.setOnClickListener {
            chooseCount(
                "حداکثر مرور در روز",
                prefs.reviewsPerDay,
                intArrayOf(50, 100, 200, 300, 500, 800, 1000, 2000),
                Prefs.MAX_REVIEWS_PER_DAY,
            ) { prefs.reviewsPerDay = it; bind() }
        }
        binding.rowTheme.setOnClickListener { chooseTheme() }
        binding.rowExportCsv.setOnClickListener {
            csvLauncher.launch("lexilens-cards-${ExportManager.dateStamp()}.csv")
        }
        binding.rowBackup.setOnClickListener {
            backupLauncher.launch("lexilens-backup-${ExportManager.dateStamp()}.zip")
        }
        binding.rowRestore.setOnClickListener {
            restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
    }

    private fun bind() {
        val b = _binding ?: return
        b.valueNewPerDay.text = TextUtils.faNumber(prefs.newPerDay)
        b.valueReviewsPerDay.text = TextUtils.faNumber(prefs.reviewsPerDay)
        b.valueTheme.text = when (prefs.nightMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> "روشن"
            AppCompatDelegate.MODE_NIGHT_YES -> "تاریک"
            else -> "هماهنگ با سیستم"
        }
        b.valueLastBackup.text = if (prefs.lastBackupAt == 0L) {
            "هنوز پشتیبانی نگرفته‌ای"
        } else {
            "آخرین پشتیبان: ${TextUtils.relativeTime(prefs.lastBackupAt)}"
        }
        b.versionInfo.text = buildString {
            append("لِکسی‌لنز ").append(TextUtils.faDigits(BuildConfig.VERSION_NAME))
            append("\nواژه‌نامهٔ آفلاین: ویکی‌واژه، VahidN و مجموعهٔ Shirin")
            append("\nمجوز واژه‌نامه: CC-BY-SA")
        }

        b.switchAutoplay.setOnCheckedChangeListener(null)
        b.switchAutoplay.isChecked = prefs.autoPlay
        b.switchAutoplay.setOnCheckedChangeListener { _, checked -> prefs.autoPlay = checked }

        b.switchFitWidth.setOnCheckedChangeListener(null)
        b.switchFitWidth.isChecked = prefs.fitWidth
        b.switchFitWidth.setOnCheckedChangeListener { _, checked -> prefs.fitWidth = checked }

        b.switchKeepScreenOn.setOnCheckedChangeListener(null)
        b.switchKeepScreenOn.isChecked = prefs.keepScreenOn
        b.switchKeepScreenOn.setOnCheckedChangeListener { _, checked -> prefs.keepScreenOn = checked }

        b.seekTtsRate.setOnSeekBarChangeListener(null)
        b.seekTtsRate.progress = ((prefs.ttsRate - 0.5f) * 100f).roundToInt().coerceIn(0, 90)
        b.valueTtsRate.text = rateLabel(prefs.ttsRate)
        b.seekTtsRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + progress / 100f
                b.valueTtsRate.text = rateLabel(rate)
                if (fromUser) prefs.ttsRate = rate
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.ttsRate = 0.5f + (seekBar?.progress ?: 42) / 100f
            }
        })
    }

    private fun rateLabel(rate: Float): String {
        val s = String.format(java.util.Locale.US, "%.2f", rate).replace('.', '٫')
        return "${TextUtils.faDigits(s)}×"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun chooseCount(
        title: String,
        current: Int,
        presets: IntArray,
        max: Int,
        onPick: (Int) -> Unit,
    ) {
        val values = (presets.filter { it in 0..max } + current)
            .distinct()
            .sorted()
            .toIntArray()
        val labels = (values.map { TextUtils.faNumber(it) } + "مقدار دلخواه…").toTypedArray()
        val checked = values.indexOf(current)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                if (which == values.size) {
                    promptCustomCount(title, current, max, onPick)
                } else {
                    onPick(values[which])
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun promptCustomCount(title: String, current: Int, max: Int, onPick: (Int) -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelectAllOnFocus(true)
            hint = "مثلاً ۲۵۰"
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("$title — مقدار دلخواه")
            .setMessage("عددی از ۰ تا ${TextUtils.faNumber(max)} وارد کن.")
            .setView(input)
            .setPositiveButton("ثبت", null)
            .setNegativeButton("انصراف", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val value = TextUtils.parseNumber(input.text.toString())
                if (value == null || value !in 0..max) {
                    input.error = "عددی از ۰ تا ${TextUtils.faNumber(max)} وارد کن."
                } else {
                    onPick(value)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun chooseTheme() {
        val labels = arrayOf("هماهنگ با سیستم", "روشن", "تاریک")
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
        )
        val checked = modes.indexOf(prefs.nightMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("پوسته")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.nightMode = modes[which]
                dialog.dismiss()
                requireActivity().recreate()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun exportCsv(uri: Uri) {
        val app = requireActivity().application as App
        val ctx = requireContext()
        Snackbar.make(binding.root, "در حال ساخت فایل CSV…", Snackbar.LENGTH_INDEFINITE).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ExportManager.exportCsv(ctx, app, uri) }
            }
            if (_binding == null) return@launch
            result.onSuccess { n ->
                Snackbar.make(
                    binding.root,
                    "${TextUtils.faNumber(n)} کارت در فایل CSV ذخیره شد",
                    Snackbar.LENGTH_LONG
                ).show()
            }.onFailure {
                Snackbar.make(binding.root, "خروجی CSV نشد: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun backup(uri: Uri) {
        val app = requireActivity().application as App
        val ctx = requireContext()
        Snackbar.make(binding.root, "در حال ساخت پشتیبان…", Snackbar.LENGTH_INDEFINITE).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ExportManager.backupZip(ctx, app, uri) }
            }
            if (_binding == null) return@launch
            result.onSuccess { n ->
                prefs.lastBackupAt = System.currentTimeMillis()
                bind()
                Snackbar.make(
                    binding.root,
                    "پشتیبان ${TextUtils.faNumber(n)} کارت ساخته شد",
                    Snackbar.LENGTH_LONG
                ).show()
            }.onFailure {
                Snackbar.make(binding.root, "پشتیبان‌گیری نشد: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun restore(uri: Uri) {
        val app = requireActivity().application as App
        val ctx = requireContext()
        Snackbar.make(binding.root, "در حال بازخوانی پشتیبان…", Snackbar.LENGTH_INDEFINITE).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ExportManager.restoreZip(ctx, app, uri) }
            }
            if (_binding == null) return@launch
            result.onSuccess { restored ->
                Snackbar.make(
                    binding.root,
                    "بازیابی شد: ${TextUtils.faNumber(restored.cards)} کارت و ${TextUtils.faNumber(restored.documents)} کتاب" +
                        if (restored.skipped > 0) " · ${TextUtils.faNumber(restored.skipped)} تکراری رد شد" else "",
                    Snackbar.LENGTH_LONG
                ).show()
            }.onFailure {
                Snackbar.make(binding.root, "بازیابی نشد: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
