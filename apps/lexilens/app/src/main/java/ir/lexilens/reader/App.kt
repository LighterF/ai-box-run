package ir.lexilens.reader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import ir.lexilens.reader.data.AppDb
import ir.lexilens.reader.data.Prefs
import ir.lexilens.reader.tts.TtsSpeaker

class App : Application() {
    lateinit var db: AppDb
        private set
    lateinit var tts: TtsSpeaker
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        db = AppDb(this)
        tts = TtsSpeaker(this)
        val prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
    }
}
