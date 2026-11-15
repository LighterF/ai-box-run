package ir.lexilens.reader.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lexilens", Context.MODE_PRIVATE)

    var newPerDay: Int
        get() = sp.getInt("new_per_day", 20).coerceIn(0, MAX_NEW_PER_DAY)
        set(value) = sp.edit().putInt("new_per_day", value.coerceIn(0, MAX_NEW_PER_DAY)).apply()

    var reviewsPerDay: Int
        get() = sp.getInt("reviews_per_day", 200).coerceIn(0, MAX_REVIEWS_PER_DAY)
        set(value) = sp.edit().putInt("reviews_per_day", value.coerceIn(0, MAX_REVIEWS_PER_DAY)).apply()

    var autoPlay: Boolean
        get() = sp.getBoolean("auto_play", true)
        set(value) = sp.edit().putBoolean("auto_play", value).apply()

    var fitWidth: Boolean
        get() = sp.getBoolean("fit_width", false)
        set(value) = sp.edit().putBoolean("fit_width", value).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(value) = sp.edit().putBoolean("keep_screen_on", value).apply()

    var ttsRate: Float
        get() = sp.getFloat("tts_rate", 0.92f).coerceIn(0.5f, 1.4f)
        set(value) = sp.edit().putFloat("tts_rate", value.coerceIn(0.5f, 1.4f)).apply()

    var nightMode: Int
        get() = sp.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = sp.edit().putInt("night_mode", value).apply()

    var lastBackupAt: Long
        get() = sp.getLong("last_backup_at", 0L)
        set(value) = sp.edit().putLong("last_backup_at", value).apply()

    var reflowEnabled: Boolean
        get() = sp.getBoolean("reflow_enabled", false)
        set(value) = sp.edit().putBoolean("reflow_enabled", value).apply()

    var reflowTextPercent: Int
        get() = sp.getInt("reflow_text_percent", 100).coerceIn(65, 260)
        set(value) = sp.edit().putInt("reflow_text_percent", value.coerceIn(65, 260)).apply()

    var reflowBackground: Int
        get() = sp.getInt("reflow_background", DEFAULT_REFLOW_BACKGROUND)
        set(value) = sp.edit().putInt("reflow_background", value).apply()

    var reflowTextColor: Int
        get() = sp.getInt("reflow_text_color", DEFAULT_REFLOW_TEXT)
        set(value) = sp.edit().putInt("reflow_text_color", value).apply()

    /** "pdf" keeps the file's own face; other ids live in [ir.lexilens.reader.reader.ReflowFonts]. */
    var reflowFontId: String
        get() = sp.getString("reflow_font_id", DEFAULT_REFLOW_FONT).orEmpty()
            .ifBlank { DEFAULT_REFLOW_FONT }
        set(value) = sp.edit().putString("reflow_font_id", value).apply()

    companion object {
        const val MAX_NEW_PER_DAY = 10_000
        const val MAX_REVIEWS_PER_DAY = 100_000
        const val DEFAULT_REFLOW_BACKGROUND = 0xFFF6F4ED.toInt()
        const val DEFAULT_REFLOW_TEXT = 0xFF14231E.toInt()
        const val DEFAULT_REFLOW_FONT = "pdf"
    }
}
