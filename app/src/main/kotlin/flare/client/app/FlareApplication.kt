package flare.client.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import flare.client.app.data.SettingsManager

class FlareApplication : Application() {
    override fun onCreate() {
        val settings = SettingsManager(this)
        val mode = when (settings.themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        super.onCreate()
    }
}
