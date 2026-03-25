package flare.client.app.data

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("flare_settings", Context.MODE_PRIVATE)

    var isFragmentationEnabled: Boolean
        get() = prefs.getBoolean("frag_enabled", false)
        set(value) = prefs.edit().putBoolean("frag_enabled", value).apply()

    var packetType: String
        get() = prefs.getString("frag_packet_type", "tlshello") ?: "tlshello"
        set(value) = prefs.edit().putString("frag_packet_type", value).apply()



    var fragmentInterval: String
        get() = prefs.getString("frag_interval", "10") ?: "10"
        set(value) = prefs.edit().putString("frag_interval", value).apply()







    var pingType: String
        get() = prefs.getString("ping_type", "via proxy GET") ?: "via proxy GET"
        set(value) = prefs.edit().putString("ping_type", value).apply()

    var pingTestUrl: String
        get() = prefs.getString("ping_test_url", "https://www.google.com/generate_204") ?: "https://www.google.com/generate_204"
        set(value) = prefs.edit().putString("ping_test_url", value).apply()

    var pingStyle: String
        get() = prefs.getString("ping_style", "Время") ?: "Время"
        set(value) = prefs.edit().putString("ping_style", value).apply()

    var mtu: String
        get() = prefs.getString("mtu", "1500") ?: "1500"
        set(value) = prefs.edit().putString("mtu", value).apply()

    var isSplitTunnelingEnabled: Boolean
        get() = prefs.getBoolean("split_tunneling_enabled", false)
        set(value) = prefs.edit().putBoolean("split_tunneling_enabled", value).apply()

    var splitTunnelingApps: Set<String>
        get() = prefs.getStringSet("split_tunneling_apps", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("split_tunneling_apps", value).apply()

    var tunStack: String
        get() = prefs.getString("tun_stack", "mixed") ?: "mixed"
        set(value) = prefs.edit().putString("tun_stack", value).apply()

    var isAutostartEnabled: Boolean
        get() = prefs.getBoolean("autostart_enabled", false)
        set(value) = prefs.edit().putBoolean("autostart_enabled", value).apply()

    var isSubAutoUpdateEnabled: Boolean
        get() = prefs.getBoolean("sub_auto_update_enabled", false)
        set(value) = prefs.edit().putBoolean("sub_auto_update_enabled", value).apply()

    var subAutoUpdateInterval: String
        get() = prefs.getString("sub_auto_update_interval", "3600") ?: "3600"
        set(value) = prefs.edit().putString("sub_auto_update_interval", value).apply()

    var lastSubUpdateTime: Long
        get() = prefs.getLong("last_sub_update_time", 0L)
        set(value) = prefs.edit().putLong("last_sub_update_time", value).apply()

    var isMuxEnabled: Boolean
        get() = prefs.getBoolean("mux_enabled", false)
        set(value) = prefs.edit().putBoolean("mux_enabled", value).apply()

    var muxProtocol: String
        get() = prefs.getString("mux_protocol", "smux") ?: "smux"
        set(value) = prefs.edit().putString("mux_protocol", value).apply()

    var muxMaxStreams: String
        get() = prefs.getString("mux_max_streams", "8") ?: "8"
        set(value) = prefs.edit().putString("mux_max_streams", value).apply()

    var muxPadding: Boolean
        get() = prefs.getBoolean("mux_padding", false)
        set(value) = prefs.edit().putBoolean("mux_padding", value).apply()

    var remoteDnsUrl: String
        get() = prefs.getString("remote_dns_url", "") ?: ""
        set(value) = prefs.edit().putString("remote_dns_url", value).apply()

    var isFakeIpEnabled: Boolean
        get() = prefs.getBoolean("fake_ip_enabled", false)
        set(value) = prefs.edit().putBoolean("fake_ip_enabled", value).apply()

    var themeMode: Int
        get() = 2 // Always Night
        set(value) {}

    var isBackgroundGradientEnabled: Boolean
        get() = prefs.getBoolean("bg_gradient_enabled", false)
        set(value) = prefs.edit().putBoolean("bg_gradient_enabled", value).apply()

    var isStatusNotificationEnabled: Boolean
        get() = prefs.getBoolean("status_notification_enabled", false)
        set(value) = prefs.edit().putBoolean("status_notification_enabled", value).apply()
}

