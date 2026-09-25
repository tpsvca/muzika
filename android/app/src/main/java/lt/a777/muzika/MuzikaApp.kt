package lt.a777.muzika

import android.app.Application
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Store
import lt.a777.muzika.sources.Innertube
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
        // Resolves the visitor id off the main thread, once, so no search
        // ever blocks on it.
        Innertube.warmUp()
    }

    companion object {
        lateinit var instance: MuzikaApp
            private set
    }
}
