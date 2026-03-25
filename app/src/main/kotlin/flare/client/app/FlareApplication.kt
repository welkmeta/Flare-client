package flare.client.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class FlareApplication : Application() {
    override fun onCreate() {
        // Apply night mode BEFORE any Activity is created to prevent white flash
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate()
    }
}
