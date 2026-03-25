package flare.client.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import flare.client.app.data.db.AppDatabase
import flare.client.app.data.model.DisplayItem
import flare.client.app.data.model.ProfileEntity
import flare.client.app.data.model.SubscriptionEntity
import flare.client.app.data.model.PingState
import flare.client.app.data.parser.ClipboardParser
import flare.client.app.data.repository.ProfileRepository
import flare.client.app.data.SettingsManager
import flare.client.app.service.FlareVpnService
import flare.client.app.R
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repository = ProfileRepository(db.profileDao(), db.subscriptionDao())

    private val _connectionTimerText = MutableStateFlow("")
    val connectionTimerText: StateFlow<String> = _connectionTimerText.asStateFlow()

    private var timerJob: kotlinx.coroutines.Job? = null
    private val expandedSubs = MutableStateFlow<Set<Long>>(emptySet())

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())

    private val _selectedProfileId = MutableStateFlow<Long?>(null)
    val selectedProfileId: StateFlow<Long?> = _selectedProfileId.asStateFlow()

    private val _editingProfile = MutableStateFlow<ProfileEntity?>(null)
    val editingProfile: StateFlow<ProfileEntity?> = _editingProfile.asStateFlow()

    private val _editingSubscription = MutableStateFlow<SubscriptionEntity?>(null)
    val editingSubscription: StateFlow<SubscriptionEntity?> = _editingSubscription.asStateFlow()

    private val _importEvent = MutableSharedFlow<ImportEvent>()
    val importEvent: SharedFlow<ImportEvent> = _importEvent.asSharedFlow()

    val isAnySubscriptionExpanded: StateFlow<Boolean> = expandedSubs
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    sealed class ImportEvent {
        data class Success(val message: String) : ImportEvent()
        data class Error(val message: String) : ImportEvent()
        data class NeedPermission(val intent: Intent) : ImportEvent()
    }

    val displayItems: StateFlow<List<DisplayItem>> = combine(
        repository.getAllSubscriptions(),
        repository.getAllProfiles(),
        expandedSubs,
        _selectedProfileId,
        _pingStates
    ) { subs, allProfiles, expanded, selId, pings ->
        val profilesBySub = allProfiles.groupBy { it.subscriptionId }
        val standalone = allProfiles.filter { it.subscriptionId == null }
        buildDisplayList(subs, standalone, profilesBySub, expanded, selId, pings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == FlareVpnService.BROADCAST_STATE) {
                val connected = intent.getBooleanExtra(FlareVpnService.EXTRA_CONNECTED, false)
                val hasError = intent.getBooleanExtra(FlareVpnService.EXTRA_ERROR, false)
                _connectionState.value = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
                if (connected) {
                    startTimer()
                } else {
                    stopTimer()
                    if (hasError) flare.client.app.ui.notification.AppNotificationManager.showNotification(flare.client.app.ui.notification.NotificationType.ERROR, context.getString(R.string.vpn_error_tunnel_creation), 4)
                }
            }
        }
    }

    init {
        val app = getApplication<Application>()
        app.registerReceiver(vpnReceiver, IntentFilter(FlareVpnService.BROADCAST_STATE), Context.RECEIVER_NOT_EXPORTED)
        
        // Sync state if VPN is already running
        if (flare.client.app.singbox.SingBoxManager.isRunning) {
            _connectionState.value = ConnectionState.CONNECTED
            startTimer()
        }

        viewModelScope.launch { _selectedProfileId.value = repository.getSelectedProfile()?.id }
        startAutoUpdateJob()
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(vpnReceiver)
    }

    fun toggleSubscriptionExpanded(subId: Long) = expandedSubs.update { if (subId in it) it - subId else it + subId }
    fun selectProfile(profileId: Long) { viewModelScope.launch { repository.selectProfile(profileId); _selectedProfileId.value = profileId; if (_connectionState.value != ConnectionState.DISCONNECTED) { stopVpn(); startVpn() } } }
    fun deleteSubscription(subId: Long) {
        val subName = displayItems.value.filterIsInstance<DisplayItem.SubscriptionItem>().find { it.entity.id == subId }?.entity?.name ?: "Неизвестно"
        viewModelScope.launch {
            repository.deleteSubscriptionById(subId)
            if (repository.getSelectedProfile() == null) _selectedProfileId.value = null
            flare.client.app.ui.notification.AppNotificationManager.showNotification(
                flare.client.app.ui.notification.NotificationType.SUCCESS,
                getApplication<Application>().getString(R.string.sub_deleted_success, subName),
                3
            )
        }
    }
    fun speedTestSubscription(subId: Long) {
        viewModelScope.launch {
            val profiles = repository.getAllProfiles().first().filter { it.subscriptionId == subId }
            if (profiles.isEmpty()) return@launch
            speedTestProfile(profiles)
        }
    }

    fun speedTestProfile(profiles: List<ProfileEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentPings = _pingStates.value.toMutableMap()
            profiles.forEach { profile ->
                currentPings[profile.id] = PingState.Loading
            }
            _pingStates.value = currentPings

            val app = getApplication<Application>()
            val settings = SettingsManager(app)
            val isProxy = settings.pingType.startsWith("via")

            if (!isProxy) {
                val method = if (settings.pingType == "TCP") "TCP" else "ICMP"
                profiles.forEach { profile ->
                    launch {
                        val result = flare.client.app.util.PingHelper.pingDirect(profile, method)
                        _pingStates.update { it.toMutableMap().apply { 
                            this[profile.id] = PingState.Result(result, result < 0) 
                        } }
                    }
                }
            } else {
                val httpMethod = if (settings.pingType == "via proxy GET") "GET" else "HEAD"
                flare.client.app.util.PingHelper.pingProxyBatch(
                    context = app,
                    profiles = profiles,
                    testUrl = settings.pingTestUrl,
                    httpMethod = httpMethod
                ) { id, latency ->
                    _pingStates.update { it.toMutableMap().apply { 
                        this[id] = PingState.Result(latency, latency < 0) 
                    } }
                }
            }
        }
    }
    fun showSubscriptionOptions(subId: Long) {}
    fun setEditingProfile(p: ProfileEntity?) { _editingProfile.value = p; _editingSubscription.value = null }
    fun setEditingSubscription(s: SubscriptionEntity?) { _editingSubscription.value = s; _editingProfile.value = null }
    fun updateProfileConfig(id: Long, json: String) { viewModelScope.launch(Dispatchers.IO) { repository.updateProfileConfig(id, json) } }
    fun updateSubscriptionConfig(id: Long, json: String) { /* implementation skipped */ }
    fun connectOrDisconnect() = if (_connectionState.value != ConnectionState.DISCONNECTED) stopVpn() else startVpn()
    fun importFromClipboard(text: String) { viewModelScope.launch(Dispatchers.IO) { when (val result = ClipboardParser.parse(text)) { is ClipboardParser.ParseResult.SingleProfile -> repository.insertProfile(result.profile); is ClipboardParser.ParseResult.Subscription -> repository.insertSubscriptionWithProfiles(result.subscription, result.profiles); is ClipboardParser.ParseResult.Error -> _importEvent.emit(ImportEvent.Error(result.message)); else -> {} } } }

    private fun startVpn() {
        viewModelScope.launch {
            val profile = repository.getSelectedProfile() ?: return@launch
            val app = getApplication<Application>()
            
            val settings = SettingsManager(app)
            val configWithSettings = patchMtu(profile.configJson, settings.mtu, settings.tunStack)

            val vpnIntent = VpnService.prepare(app)
            if (vpnIntent != null) { _importEvent.emit(ImportEvent.NeedPermission(vpnIntent)); return@launch }
            _connectionState.value = ConnectionState.CONNECTING
            _connectionState.value = ConnectionState.CONNECTING
            androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, FlareVpnService::class.java).apply { 
                action = FlareVpnService.ACTION_START
                putExtra(FlareVpnService.EXTRA_CONFIG, configWithSettings)
                putExtra(FlareVpnService.EXTRA_PROFILE_NAME, profile.name)
            })
        }
    }

    private fun stopVpn() { val app = getApplication<Application>(); app.startService(Intent(app, FlareVpnService::class.java).apply { action = FlareVpnService.ACTION_STOP }) }
    private fun startTimer() { 
        timerJob?.cancel()
        timerJob = viewModelScope.launch { 
            val baseTime = flare.client.app.singbox.SingBoxManager.startTime
            val start = if (baseTime > 0) baseTime else System.currentTimeMillis()
            while (true) { 
                _connectionTimerText.value = formatDuration(System.currentTimeMillis() - start)
                delay(1000) 
            } 
        } 
    }
    private fun stopTimer() { timerJob?.cancel(); timerJob = null; _connectionTimerText.value = "" }
    private fun formatDuration(ms: Long): String = String.format("%02d:%02d:%02d", ms/(3600000), (ms/60000)%60, (ms/1000)%60)

    private fun buildDisplayList(subs: List<SubscriptionEntity>, standalone: List<ProfileEntity>, profilesBySub: Map<Long?, List<ProfileEntity>>, expanded: Set<Long>, selId: Long?, pings: Map<Long, PingState>): List<DisplayItem> {
        val items = mutableListOf<DisplayItem>()
        subs.forEach { sub ->
            val subProfiles = profilesBySub[sub.id] ?: emptyList()
            val isExpanded = sub.id in expanded
            items += DisplayItem.SubscriptionItem(sub, subProfiles, isExpanded, if (isExpanded) DisplayItem.CornerType.TOP else DisplayItem.CornerType.ALL)
            if (isExpanded) subProfiles.forEachIndexed { i, p -> items += DisplayItem.ProfileItem(p, p.id == selId, pings[p.id] ?: PingState.None, if (i == subProfiles.size - 1) DisplayItem.CornerType.BOTTOM else DisplayItem.CornerType.NONE) }
        }
        standalone.forEach { p -> items += DisplayItem.ProfileItem(p, p.id == selId, pings[p.id] ?: PingState.None, DisplayItem.CornerType.ALL) }
        return items
    }

    private fun startAutoUpdateJob() {
        val settings = SettingsManager(getApplication())
        viewModelScope.launch {
            while (true) {
                if (settings.isSubAutoUpdateEnabled) {
                    val interval = settings.subAutoUpdateInterval.toLongOrNull() ?: 3600L
                    if (System.currentTimeMillis() - settings.lastSubUpdateTime >= interval * 1000L) refreshAllSubscriptions()
                }
                delay(60000)
            }
        }
    }

    suspend fun refreshAllSubscriptions() {
        val subs = repository.getAllSubscriptions().first()
        var successCount = 0
        subs.forEach { sub ->
            try {
                val result = ClipboardParser.parse(sub.url)
                if (result is ClipboardParser.ParseResult.Subscription) {
                    repository.deleteProfilesBySubscription(sub.id)
                    db.profileDao().insertAll(result.profiles.map { it.copy(subscriptionId = sub.id) })
                    successCount++
                }
            } catch (e: Exception) { Log.e("MainViewModel", "Failed to refresh ${sub.name}", e) }
        }
        if (successCount > 0) {
            val settings = SettingsManager(getApplication())
            settings.lastSubUpdateTime = System.currentTimeMillis()
            flare.client.app.ui.notification.AppNotificationManager.showNotification(flare.client.app.ui.notification.NotificationType.SUCCESS, getApplication<Application>().getString(flare.client.app.R.string.sub_update_success, successCount), 4)
        }
    }

    private fun patchMtu(json: String, newMtu: String, tunStack: String): String {
        return try {
            val obj = JSONObject(json)
            val inbounds = obj.optJSONArray("inbounds")
            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inbound = inbounds.optJSONObject(i)
                    if (inbound?.optString("type") == "tun") {
                        inbound.put("mtu", newMtu.toIntOrNull() ?: 1500)
                        inbound.put("stack", tunStack)
                    }
                }
            }
            obj.toString().replace("\\/", "/")
        } catch (e: Exception) {
            json
        }
    }
}
