package lt.a777.muzika

import android.app.Application
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Store
import lt.a777.muzika.sources.NpeDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

class MuzikaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        NewPipe.init(NpeDownloader, Localization("en", "US"))
        Prefs.open(this)
        Store.open(this)
    }

    companion object {
        lateinit var instance: MuzikaApp
            private set
    }
}
