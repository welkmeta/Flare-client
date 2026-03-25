package flare.client.app.ui

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import flare.client.app.R
import flare.client.app.data.SettingsManager
import flare.client.app.databinding.ActivityMainBinding
import flare.client.app.databinding.DialogAppSelectionBinding
import flare.client.app.databinding.ItemAppSelectionBinding
import flare.client.app.ui.adapter.ProfileAdapter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settings: SettingsManager

    private lateinit var adapter: ProfileAdapter

    private val vpnPermLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    viewModel.connectOrDisconnect()
                } else {
                    showSnackbar("Разрешение VPN отклонено")
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsManager(this)

        binding.root.clipToPadding = false
        binding.root.clipChildren = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomNav.setPadding(
                binding.bottomNav.paddingLeft,
                binding.bottomNav.paddingTop,
                binding.bottomNav.paddingRight,
                systemBars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        applyBackgroundGradient()

        setupRecyclerView()
        setupButtons()
        setupBottomNav()
        setupSettings()
        setupJsonEditor()
        observeViewModel()
        observeNotifications()

        lifecycleScope.launch {
            viewModel.selectedProfileId.collect { id ->
                if (id != null) {
                    if (settings.isAutostartEnabled &&
                                    viewModel.connectionState.value ==
                                            MainViewModel.ConnectionState.DISCONNECTED
                    ) {
                        viewModel.connectOrDisconnect()
                    }
                    this@launch.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                }
            }
        }

        onBackPressedDispatcher.addCallback(
                this,
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (binding.layoutJsonEditor.visibility == View.VISIBLE) {
                            val name =
                                    viewModel.editingProfile.value?.name
                                            ?: viewModel.editingSubscription.value?.name
                            if (name != null) {
                                flare.client.app.ui.notification.AppNotificationManager
                                        .showNotification(
                                                flare.client.app.ui.notification.NotificationType
                                                        .SUCCESS,
                                                getString(
                                                        flare.client.app.R.string.json_edit_success,
                                                        name
                                                ),
                                                3
                                        )
                            }
                            viewModel.setEditingProfile(null)
                            viewModel.setEditingSubscription(null)
                        } else if (binding.layoutSettingsAdvancedContainer.root.visibility ==
                                        View.VISIBLE
                        ) {
                            binding.layoutSettingsAdvancedContainer.root.visibility = View.GONE
                            binding.layoutSettings.root.visibility = View.VISIBLE
                        } else if (binding.layoutSettingsPingContainer.root.visibility == View.VISIBLE) {
                            binding.layoutSettingsPingContainer.root.visibility = View.GONE
                            binding.layoutSettings.root.visibility = View.VISIBLE
                        } else if (findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)
                                        ?.visibility == View.VISIBLE
                        ) {
                            findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)
                                    ?.visibility = View.GONE
                            binding.layoutSettings.root.visibility = View.VISIBLE
                        } else if (findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)
                                        ?.visibility == View.VISIBLE
                        ) {
                            findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility =
                                    View.GONE
                            binding.layoutSettings.root.visibility = View.VISIBLE
                        } else if (findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
                                        ?.visibility == View.VISIBLE
                        ) {
                            findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
                                    ?.visibility = View.GONE
                            binding.layoutSettings.root.visibility = View.VISIBLE
                        } else if (binding.bottomNav.currentTab != 1) {
                            binding.bottomNav.selectTab(1, true)
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
        )
    }

    private fun setupRecyclerView() {
        adapter =
                ProfileAdapter(
                        onProfileClick = { profile -> viewModel.selectProfile(profile.id) },
                        onSubscriptionToggle = { sub ->
                            viewModel.toggleSubscriptionExpanded(sub.id)
                        },
                        onSubscriptionDelete = { subId -> viewModel.deleteSubscription(subId) },
                        onSubscriptionSpeedTest = { subId ->
                            viewModel.speedTestSubscription(subId)
                        },
                        onSubscriptionOptions = { subId ->
                            viewModel.showSubscriptionOptions(subId)
                        },
                        onEditProfileJson = { profile -> viewModel.setEditingProfile(profile) },
                        onEditSubscriptionJson = { sub -> viewModel.setEditingSubscription(sub) }
                )
        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnConnectContainer.setOnClickListener { viewModel.connectOrDisconnect() }

        val distance = 8000 * resources.displayMetrics.density
        binding.btnConnectContainer.cameraDistance = distance

        val maxTilt = 12f

        binding.btnConnectContainer.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate()
                            .scaleX(0.92f)
                            .scaleY(0.92f)
                            .setDuration(100)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()

                    val centerX = v.width / 2f
                    val centerY = v.height / 2f
                    val percentX = ((event.x - centerX) / centerX).coerceIn(-1.5f, 1.5f)
                    val percentY = ((event.y - centerY) / centerY).coerceIn(-1.5f, 1.5f)

                    v.rotationY = percentX * maxTilt
                    v.rotationX = -percentY * maxTilt
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val centerX = v.width / 2f
                    val centerY = v.height / 2f
                    val percentX = ((event.x - centerX) / centerX).coerceIn(-1.5f, 1.5f)
                    val percentY = ((event.y - centerY) / centerY).coerceIn(-1.5f, 1.5f)

                    v.rotationY = percentX * maxTilt
                    v.rotationX = -percentY * maxTilt
                    false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .rotationX(0f)
                            .rotationY(0f)
                            .setDuration(300)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                            .start()

                    false
                }
                else -> false
            }
        }

        binding.btnClipboard.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (text.isNullOrBlank()) {
                showSnackbar("Буфер обмена пуст")
            } else {
                viewModel.importFromClipboard(text)
            }
        }
    }

    private fun setupJsonEditor() {
        binding.btnJsonBack.setOnClickListener {
            val name =
                    viewModel.editingProfile.value?.name
                            ?: viewModel.editingSubscription.value?.name
            if (name != null) {
                flare.client.app.ui.notification.AppNotificationManager.showNotification(
                        flare.client.app.ui.notification.NotificationType.SUCCESS,
                        getString(flare.client.app.R.string.json_edit_success, name),
                        3
                )
            }
            viewModel.setEditingProfile(null)
            viewModel.setEditingSubscription(null)
        }

        binding.btnJsonCopy.setOnClickListener {
            val text = binding.etJsonContent.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("JSON Config", text))
            showSnackbar("Конфиг скопирован")
        }

        binding.etJsonContent.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                    ) {}
                    override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                    ) {
                        val profileId = viewModel.editingProfile.value?.id
                        val subId = viewModel.editingSubscription.value?.id
                        if (profileId != null) {
                            viewModel.updateProfileConfig(profileId, s.toString())
                        } else if (subId != null) {
                            viewModel.updateSubscriptionConfig(subId, s.toString())
                        }
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )
    }

    private fun setupBottomNav() {
        binding.bottomNav.onTabSelected = { index ->
            when (index) {
                0 -> {
                    binding.layoutHome.visibility = View.GONE
                    binding.tvServersPlaceholder.visibility = View.GONE
                    binding.layoutSettings.root.visibility = View.VISIBLE
                    binding.layoutSettingsAdvancedContainer.root.visibility = View.GONE
                    binding.layoutSettingsPingContainer.root.visibility = View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_settings_base_container)?.visibility = View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)?.visibility =
                            View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility = View.GONE
                }
                1 -> {
                    binding.layoutHome.visibility = View.VISIBLE
                    binding.layoutSettings.root.visibility = View.GONE
                    binding.layoutSettingsAdvancedContainer.root.visibility = View.GONE
                    binding.layoutSettingsPingContainer.root.visibility = View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_settings_base_container)?.visibility = View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)?.visibility =
                            View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility = View.GONE
                    binding.tvServersPlaceholder.visibility = View.GONE
                }
                2 -> {
                    binding.layoutHome.visibility = View.GONE
                    binding.layoutSettings.root.visibility = View.GONE
                    binding.layoutSettingsAdvancedContainer.root.visibility = View.GONE
                    binding.layoutSettingsPingContainer.root.visibility = View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_settings_base_container)?.visibility = View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)?.visibility =
                            View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility = View.GONE
                    binding.tvServersPlaceholder.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupSettings() {
        binding.layoutSettings.btnSettingsBase.setOnClickListener {
            binding.layoutSettings.root.visibility = View.GONE
            findViewById<View>(flare.client.app.R.id.layout_settings_base_container)?.visibility = View.VISIBLE
        }
        binding.layoutSettings.btnSettingsAdvanced.setOnClickListener {
            binding.layoutSettings.root.visibility = View.GONE
            binding.layoutSettingsAdvancedContainer.root.visibility = View.VISIBLE
        }
        binding.layoutSettings.btnSettingsTheme.setOnClickListener {
            binding.layoutSettings.root.visibility = View.GONE
            findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility = View.VISIBLE
        }
        binding.layoutSettings.btnSettingsLanguage.setOnClickListener {
            showSnackbar("Смена языка (в разработке)")
        }
        binding.layoutSettings.btnSettingsPing.setOnClickListener {
            binding.layoutSettings.root.visibility = View.GONE
            binding.layoutSettingsPingContainer.root.visibility = View.VISIBLE
        }
        binding.layoutSettings.btnSettingsSubscriptions.setOnClickListener {
            binding.layoutSettings.root.visibility = View.GONE
            findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)
                    ?.visibility = View.VISIBLE
        }

        setupAdvancedSettings()
        setupPingSettings()
        setupBaseSettings()
        setupSubscriptionsSettings()
        setupThemeSettings()
    }

    private fun setupSubscriptionsSettings() {
        val subView =
                findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)
                        ?: return
        
        subView.findViewById<View>(flare.client.app.R.id.btn_subscriptions_back)?.setOnClickListener {
            subView.visibility = View.GONE
            binding.layoutSettings.root.visibility = View.VISIBLE
        }

        val swAutoUpdate = subView.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_auto_update)
        val layoutAutoUpdateSub = subView.findViewById<View>(flare.client.app.R.id.layout_auto_update_sub)
        val btnToggleAutoUpdate = subView.findViewById<View>(flare.client.app.R.id.btn_toggle_auto_update)
        val blockAutoUpdate = subView.findViewById<android.view.ViewGroup>(flare.client.app.R.id.block_auto_update)
        val etUpdateInterval = subView.findViewById<android.widget.EditText>(flare.client.app.R.id.et_update_interval)

        swAutoUpdate?.isChecked = settings.isSubAutoUpdateEnabled
        layoutAutoUpdateSub?.visibility = if (settings.isSubAutoUpdateEnabled) View.VISIBLE else View.GONE
        btnToggleAutoUpdate?.setBackgroundResource(
                if (settings.isSubAutoUpdateEnabled) flare.client.app.R.drawable.bg_grouped_top
                else flare.client.app.R.drawable.bg_grouped_all
        )
        etUpdateInterval?.setText(settings.subAutoUpdateInterval)

        swAutoUpdate?.setOnCheckedChangeListener { _, isChecked ->
            settings.isSubAutoUpdateEnabled = isChecked
            if (blockAutoUpdate != null) {
                android.transition.TransitionManager.beginDelayedTransition(
                        blockAutoUpdate,
                        android.transition.AutoTransition().setDuration(200)
                )
            }
            layoutAutoUpdateSub?.visibility = if (isChecked) View.VISIBLE else View.GONE
            btnToggleAutoUpdate?.setBackgroundResource(
                    if (isChecked) flare.client.app.R.drawable.bg_grouped_top
                    else flare.client.app.R.drawable.bg_grouped_all
            )
        }

        etUpdateInterval?.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        settings.subAutoUpdateInterval = s.toString()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )
    }

    private fun setupBaseSettings() {
        val baseInclude = findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
        baseInclude?.findViewById<View>(flare.client.app.R.id.btn_base_back)?.setOnClickListener {
            baseInclude.visibility = View.GONE
            binding.layoutSettings.root.visibility = View.VISIBLE
        }

        val swSplit = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_split_tunneling)
        val layoutSub = baseInclude?.findViewById<View>(flare.client.app.R.id.layout_split_tunneling_sub)
        val btnToggle = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_toggle_split_tunneling)
        val blockSplit = baseInclude?.findViewById<android.view.ViewGroup>(flare.client.app.R.id.block_split_tunneling)

        if (swSplit != null && layoutSub != null && btnToggle != null && blockSplit != null) {
            swSplit.isChecked = settings.isSplitTunnelingEnabled
            layoutSub.visibility = if (settings.isSplitTunnelingEnabled) View.VISIBLE else View.GONE
            btnToggle.setBackgroundResource(
                    if (settings.isSplitTunnelingEnabled) flare.client.app.R.drawable.bg_grouped_top
                    else flare.client.app.R.drawable.bg_grouped_all
            )

            swSplit.setOnCheckedChangeListener { _, isChecked ->
                settings.isSplitTunnelingEnabled = isChecked
                showSettingsNotification()
                android.transition.TransitionManager.beginDelayedTransition(
                        blockSplit,
                        android.transition.AutoTransition().setDuration(200)
                )
                layoutSub.visibility = if (isChecked) View.VISIBLE else View.GONE
                btnToggle.setBackgroundResource(
                        if (isChecked) flare.client.app.R.drawable.bg_grouped_top
                        else flare.client.app.R.drawable.bg_grouped_all
                )
            }
        }

        val swAutostart = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_autostart)
        if (swAutostart != null) {
            swAutostart.isChecked = settings.isAutostartEnabled
            swAutostart.setOnCheckedChangeListener { _, isChecked ->
                settings.isAutostartEnabled = isChecked
            }
        }

        val swStatusNotif = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_status_notification)
        if (swStatusNotif != null) {
            swStatusNotif.isChecked = settings.isStatusNotificationEnabled
            swStatusNotif.setOnCheckedChangeListener { _, isChecked ->
                settings.isStatusNotificationEnabled = isChecked
            }
        }

        updateSplitTunnelingDesc()

        baseInclude?.findViewById<View>(flare.client.app.R.id.btn_change_apps)?.setOnClickListener {
            showAppSelectionDialog()
        }
    }

    private fun updateSplitTunnelingDesc() {
        val baseInclude = findViewById<View>(flare.client.app.R.id.layout_settings_base_container) ?: return
        val tvDesc = baseInclude.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_split_tunneling_desc)
        val count = settings.splitTunnelingApps.size
        tvDesc?.text =
                if (count > 0) {
                    getString(flare.client.app.R.string.selected_apps_count, count)
                } else {
                    "Выберите приложения, которые будут использовать VPN."
                }
    }

    private fun showAppSelectionDialog() {
        val dialog = android.app.Dialog(this)
        val dialogBinding = flare.client.app.databinding.DialogAppSelectionBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val selectedPackages = settings.splitTunnelingApps.toMutableSet()
        val allApps = packageManager.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                        .filter {
                            it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 ||
                                     it.packageName == packageName
                        }
                        .map { appInfo ->
                            AppListItem(
                                    packageName = appInfo.packageName,
                                    name = appInfo.loadLabel(packageManager).toString(),
                                    icon = appInfo.loadIcon(packageManager),
                                    isSelected = selectedPackages.contains(appInfo.packageName)
                            )
                        }
                        .sortedBy { it.name }

        var filteredApps = allApps
        val adapter = AppSelectionAdapter(filteredApps) { item ->
                    item.isSelected = !item.isSelected
                    if (item.isSelected) selectedPackages.add(item.packageName)
                    else selectedPackages.remove(item.packageName)
                }

        dialogBinding.rvApps.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvApps.adapter = adapter

        dialogBinding.etSearchApps.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        filteredApps = allApps.filter { it.name.contains(s.toString(), ignoreCase = true) }
                        adapter.updateList(filteredApps)
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        dialogBinding.btnCancelApps.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSaveApps.setOnClickListener {
            settings.splitTunnelingApps = selectedPackages
            updateSplitTunnelingDesc()
            showSettingsNotification()
            dialog.dismiss()
        }

        dialog.show()
    }

    data class AppListItem(
            val packageName: String,
            val name: String,
            val icon: android.graphics.drawable.Drawable,
            var isSelected: Boolean
    )

    inner class AppSelectionAdapter(
            private var items: List<AppListItem>,
            private val onToggle: (AppListItem) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<AppSelectionAdapter.VH>() {

        fun updateList(newList: List<AppListItem>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = flare.client.app.databinding.ItemAppSelectionBinding.inflate(
                            LayoutInflater.from(parent.context),
                            parent,
                            false
                    )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tvAppName.text = item.name
            holder.binding.ivAppIcon.setImageDrawable(item.icon)
            holder.binding.ivAppCheck.visibility = if (item.isSelected) View.VISIBLE else View.GONE
            holder.binding.layoutItemApp.alpha = if (item.isSelected) 1.0f else 0.7f

            holder.binding.root.setOnClickListener {
                onToggle(item)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = items.size
        inner class VH(val binding: flare.client.app.databinding.ItemAppSelectionBinding) :
                androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)
    }

    private fun setupAdvancedSettings() {
        val adv = binding.layoutSettingsAdvancedContainer

        adv.btnAdvancedBack.setOnClickListener {
            adv.root.visibility = View.GONE
            binding.layoutSettings.root.visibility = View.VISIBLE
        }

        adv.swFragmentation.isChecked = settings.isFragmentationEnabled
        adv.layoutFragmentationSub.visibility = if (settings.isFragmentationEnabled) View.VISIBLE else View.GONE
        adv.btnToggleFragmentation.setBackgroundResource(
                if (settings.isFragmentationEnabled) flare.client.app.R.drawable.bg_grouped_top
                else flare.client.app.R.drawable.bg_grouped_all
        )
        adv.tvPacketTypeValue.text = settings.packetType
        adv.tvStackTypeValue.text = this@MainActivity.getString(R.string.settings_label_stack, settings.tunStack)

        // Fragmentation defaults
        adv.etFragmentInterval.setText(settings.fragmentInterval)
        adv.etMtu.setText(settings.mtu)
        
        val etRemoteDns = adv.root.findViewById<android.widget.EditText>(flare.client.app.R.id.et_remote_dns_url)
        etRemoteDns?.setText(settings.remoteDnsUrl)

        val swFakeIp = adv.root.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_fake_ip)
        swFakeIp?.isChecked = settings.isFakeIpEnabled

        swFakeIp?.setOnCheckedChangeListener { _, isChecked ->
            settings.isFakeIpEnabled = isChecked
            showSettingsNotification()
        }

        val btnToggleFakeIp = adv.root.findViewById<android.view.View>(flare.client.app.R.id.btn_toggle_fake_ip)
        btnToggleFakeIp?.setOnClickListener {
            swFakeIp?.toggle()
        }

        adv.swFragmentation.setOnCheckedChangeListener { _, isChecked ->
            settings.isFragmentationEnabled = isChecked
            showSettingsNotification()
            android.transition.TransitionManager.beginDelayedTransition(
                    adv.blockFragmentation,
                    android.transition.AutoTransition().setDuration(200)
            )
            adv.layoutFragmentationSub.visibility = if (isChecked) View.VISIBLE else View.GONE
            adv.btnToggleFragmentation.setBackgroundResource(
                    if (isChecked) flare.client.app.R.drawable.bg_grouped_top
                    else flare.client.app.R.drawable.bg_grouped_all
            )
        }

        adv.btnPacketType.setOnClickListener { view ->
            showPopupMenu(view, listOf("tlshello", "1-3")) { selected ->
                adv.tvPacketTypeValue.text = selected
                settings.packetType = selected
                showSettingsNotification()
            }
        }

        adv.btnStackType.setOnClickListener { view ->
            showPopupMenu(view, listOf("system", "mixed", "gvisor")) { selected ->
                adv.tvStackTypeValue.text = this@MainActivity.getString(R.string.settings_label_stack, selected)
                settings.tunStack = selected
                showSettingsNotification()
            }
        }

        adv.etFragmentInterval.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        settings.fragmentInterval = s.toString()
                        showSettingsNotification()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        adv.etMtu.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        settings.mtu = s.toString()
                        showSettingsNotification()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        etRemoteDns?.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        settings.remoteDnsUrl = s.toString().trim()
                        showSettingsNotification()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        // Multiplex (Mux)
        adv.swMux.isChecked = settings.isMuxEnabled
        adv.layoutMuxSub.visibility = if (settings.isMuxEnabled) View.VISIBLE else View.GONE
        adv.btnToggleMux.setBackgroundResource(
                if (settings.isMuxEnabled) flare.client.app.R.drawable.bg_grouped_top
                else flare.client.app.R.drawable.bg_grouped_all
        )
        adv.tvMuxProtocolValue.text = settings.muxProtocol
        adv.tvMuxPaddingValue.text = if (settings.muxPadding) "Да" else "Нет"

        // Mux defaults
        adv.etMuxMaxStreams.setText(settings.muxMaxStreams)

        adv.swMux.setOnCheckedChangeListener { _, isChecked ->
            settings.isMuxEnabled = isChecked
            showSettingsNotification()
            android.transition.TransitionManager.beginDelayedTransition(
                    adv.blockMux,
                    android.transition.AutoTransition().setDuration(200)
            )
            adv.layoutMuxSub.visibility = if (isChecked) View.VISIBLE else View.GONE
            adv.btnToggleMux.setBackgroundResource(
                    if (isChecked) flare.client.app.R.drawable.bg_grouped_top
                    else flare.client.app.R.drawable.bg_grouped_all
            )
        }

        adv.btnMuxProtocol.setOnClickListener { view ->
            showPopupMenu(view, listOf("smux", "h2mux")) { selected ->
                adv.tvMuxProtocolValue.text = selected
                settings.muxProtocol = selected
                showSettingsNotification()
            }
        }

        adv.etMuxMaxStreams.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        settings.muxMaxStreams = s.toString()
                        showSettingsNotification()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val v = s?.toString()?.toIntOrNull() ?: return
                        if (v > 128) {
                            // Prevent infinite loop
                            adv.etMuxMaxStreams.removeTextChangedListener(this)
                            adv.etMuxMaxStreams.setText("128")
                            adv.etMuxMaxStreams.setSelection(3)
                            settings.muxMaxStreams = "128"
                            adv.etMuxMaxStreams.addTextChangedListener(this)
                        }
                    }
                }
        )

        adv.btnMuxPadding.setOnClickListener { view ->
            showPopupMenu(view, listOf("Да", "Нет")) { selected ->
                adv.tvMuxPaddingValue.text = selected
                settings.muxPadding = (selected == "Да")
                showSettingsNotification()
            }
        }
    }

    private fun setupPingSettings() {
        val png = binding.layoutSettingsPingContainer

        png.btnPingBack.setOnClickListener {
            png.root.visibility = View.GONE
            binding.layoutSettings.root.visibility = View.VISIBLE
        }

        updatePingTypeUI(settings.pingType)
        png.etTestUrl.setText(settings.pingTestUrl)
        png.tvPingStyleValue.text = settings.pingStyle

        png.btnPingTypeGet.setOnClickListener {
            settings.pingType = "via proxy GET"
            updatePingTypeUI(settings.pingType)
        }
        png.btnPingTypeHead.setOnClickListener {
            settings.pingType = "via proxy HEAD"
            updatePingTypeUI(settings.pingType)
        }
        png.btnPingTypeTcp.setOnClickListener {
            settings.pingType = "TCP"
            updatePingTypeUI(settings.pingType)
        }
        png.btnPingTypeIcmp.setOnClickListener {
            settings.pingType = "ICMP"
            updatePingTypeUI(settings.pingType)
        }

        png.etTestUrl.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        settings.pingTestUrl = s.toString()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        png.btnPingStyle.setOnClickListener { view ->
            showPopupMenu(view, listOf("Время", "Значок", "Время и значок")) { selected ->
                png.tvPingStyleValue.text = selected
                settings.pingStyle = selected
            }
        }
    }

    private fun updatePingTypeUI(type: String) {
        val png = binding.layoutSettingsPingContainer
        png.btnPingTypeGet.setBackgroundResource(flare.client.app.R.drawable.bg_grouped_all)
        png.btnPingTypeHead.setBackgroundResource(flare.client.app.R.drawable.bg_grouped_all)
        png.btnPingTypeTcp.setBackgroundResource(flare.client.app.R.drawable.bg_grouped_all)
        png.btnPingTypeIcmp.setBackgroundResource(flare.client.app.R.drawable.bg_grouped_all)
        png.ivPingTypeGetCheck.visibility = View.GONE
        png.ivPingTypeHeadCheck.visibility = View.GONE
        png.ivPingTypeTcpCheck.visibility = View.GONE
        png.ivPingTypeIcmpCheck.visibility = View.GONE

        when (type) {
            "via proxy GET" -> {
                png.btnPingTypeGet.setBackgroundResource(flare.client.app.R.drawable.bg_ping_type_selected)
                png.ivPingTypeGetCheck.visibility = View.VISIBLE
            }
            "via proxy HEAD" -> {
                png.btnPingTypeHead.setBackgroundResource(flare.client.app.R.drawable.bg_ping_type_selected)
                png.ivPingTypeHeadCheck.visibility = View.VISIBLE
            }
            "TCP" -> {
                png.btnPingTypeTcp.setBackgroundResource(flare.client.app.R.drawable.bg_ping_type_selected)
                png.ivPingTypeTcpCheck.visibility = View.VISIBLE
            }
            "ICMP" -> {
                png.btnPingTypeIcmp.setBackgroundResource(flare.client.app.R.drawable.bg_ping_type_selected)
                png.ivPingTypeIcmpCheck.visibility = View.VISIBLE
            }
        }
    }

    private fun setupThemeSettings() {
        val themeView = findViewById<View>(flare.client.app.R.id.layout_settings_theme_container) ?: return
        
        themeView.findViewById<View>(flare.client.app.R.id.btn_theme_back).setOnClickListener {
            themeView.visibility = View.GONE
            binding.layoutSettings.root.visibility = View.VISIBLE
        }

        updateThemeValueUI()

        themeView.findViewById<View>(flare.client.app.R.id.btn_theme_selection).setOnClickListener {
            // Theme mode forced to Night
        }

        val swBgGradient = themeView.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_bg_gradient)
        swBgGradient?.isChecked = settings.isBackgroundGradientEnabled
        swBgGradient?.setOnCheckedChangeListener { _, isChecked ->
            settings.isBackgroundGradientEnabled = isChecked
            applyBackgroundGradient()
        }
        themeView.findViewById<View>(flare.client.app.R.id.btn_toggle_bg_gradient)?.setOnClickListener {
            swBgGradient?.toggle()
        }
    }

    private fun applyBackgroundGradient() {
        if (settings.isBackgroundGradientEnabled) {
            binding.root.setBackgroundResource(flare.client.app.R.drawable.bg_home_gradient)
            binding.rootLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            binding.root.setBackgroundColor(ContextCompat.getColor(this, flare.client.app.R.color.bg_dark))
            binding.rootLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    private fun updateThemeValueUI() {
        val themeView = findViewById<View>(flare.client.app.R.id.layout_settings_theme_container) ?: return
        val tvValue = themeView.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_theme_value)
        val value = when (settings.themeMode) {
            1 -> getString(flare.client.app.R.string.theme_day)
            2 -> getString(flare.client.app.R.string.theme_night)
            else -> getString(flare.client.app.R.string.theme_auto)
        }
        tvValue?.text = value
    }

    private fun applyTheme() {
        val mode = when (settings.themeMode) {
            1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun showPopupMenu(view: View, options: List<String>, onSelected: (String) -> Unit) {
        val popup = androidx.appcompat.widget.PopupMenu(this, view, Gravity.END)
        val textColor = ContextCompat.getColor(this, flare.client.app.R.color.menu_text_color)
        options.forEach { text ->
            val spannable = SpannableString(text)
            spannable.setSpan(ForegroundColorSpan(textColor), 0, spannable.length, 0)
            popup.menu.add(spannable)
        }
        popup.setOnMenuItemClickListener { item ->
            onSelected(item.title.toString())
            true
        }
        popup.show()
    }

    private fun showSettingsNotification() {
        flare.client.app.ui.notification.AppNotificationManager.showNotification(
                flare.client.app.ui.notification.NotificationType.WARNING,
                getString(flare.client.app.R.string.settings_restart_tunnel_hint),
                3
        )
    }

    // ViewModel observation

    private var notificationAnimator: android.animation.AnimatorSet? = null

    private fun observeNotifications() {
        lifecycleScope.launch {
            flare.client.app.ui.notification.AppNotificationManager.notifications.collect { data ->
                if (!isFinishing && !isDestroyed) {
                    showInAppNotification(data)
                }
            }
        }
    }

    private fun showInAppNotification(data: flare.client.app.ui.notification.NotificationData) {
        val container = binding.root.findViewById<View>(flare.client.app.R.id.notification_include) ?: return
        val tvText = container.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_notification_text)
        val ivIcon = container.findViewById<android.widget.ImageView>(flare.client.app.R.id.iv_notification_icon)
        val vProgress = container.findViewById<View>(flare.client.app.R.id.v_notification_progress)

        tvText.text = data.text
        val iconRes = when (data.type) {
            flare.client.app.ui.notification.NotificationType.SUCCESS -> flare.client.app.R.drawable.ic_success
            flare.client.app.ui.notification.NotificationType.ERROR -> flare.client.app.R.drawable.ic_error
            flare.client.app.ui.notification.NotificationType.WARNING -> flare.client.app.R.drawable.ic_warning
        }
        ivIcon.setImageResource(iconRes)

        notificationAnimator?.cancel()
        notificationAnimator = android.animation.AnimatorSet()

        container.visibility = View.VISIBLE
        container.translationY = -500f
        vProgress.scaleX = 1f
        vProgress.pivotX = 0f

        val slideIn = android.animation.ObjectAnimator.ofFloat(container, View.TRANSLATION_Y, 0f)
        slideIn.duration = 400
        slideIn.interpolator = android.view.animation.OvershootInterpolator(1.2f)

        val progressAnim = android.animation.ObjectAnimator.ofFloat(vProgress, View.SCALE_X, 1f, 0f)
        progressAnim.duration = data.durationSec * 1000L
        progressAnim.interpolator = android.view.animation.LinearInterpolator()

        val slideOut = android.animation.ObjectAnimator.ofFloat(container, View.TRANSLATION_Y, -500f)
        slideOut.duration = 300
        slideOut.interpolator = android.view.animation.AccelerateInterpolator()

        notificationAnimator?.playSequentially(slideIn, progressAnim, slideOut)
        notificationAnimator?.start()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.connectionState.collect { state -> updateConnectButton(state) }
        }

        lifecycleScope.launch {
            viewModel.connectionTimerText.collect { text ->
                binding.tvTimer.text = text
                binding.tvTimer.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.displayItems.collect { items ->
                adapter.submitList(items)
                binding.rvProfiles.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.isAnySubscriptionExpanded.collect { isExpanded ->
                binding.layoutBottomActions.visibility = if (isExpanded) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.importEvent.collect { event ->
                when (event) {
                    is MainViewModel.ImportEvent.Success -> showSnackbar(event.message)
                    is MainViewModel.ImportEvent.Error -> showSnackbar(event.message)
                    is MainViewModel.ImportEvent.NeedPermission -> vpnPermLauncher.launch(event.intent)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.editingProfile.collect { profile ->
                if (profile != null) {
                    binding.layoutJsonEditor.visibility = View.VISIBLE
                    binding.tvJsonTitle.text = profile.name
                    if (binding.etJsonContent.text.toString() != profile.configJson) {
                        binding.etJsonContent.setText(profile.configJson)
                    }
                } else if (viewModel.editingSubscription.value == null) {
                    binding.layoutJsonEditor.visibility = View.GONE
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(binding.etJsonContent.windowToken, 0)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.editingSubscription.collect { sub ->
                if (sub != null) {
                    binding.layoutJsonEditor.visibility = View.VISIBLE
                    binding.tvJsonTitle.text = sub.name
                    val json = org.json.JSONObject().apply {
                                        put("name", sub.name)
                                        put("url", sub.url)
                                    }.toString(2).replace("\\/", "/")
                    if (binding.etJsonContent.text.toString() != json) {
                        binding.etJsonContent.setText(json)
                    }
                } else if (viewModel.editingProfile.value == null) {
                    binding.layoutJsonEditor.visibility = View.GONE
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(binding.etJsonContent.windowToken, 0)
                }
            }
        }
    }

    private fun updateConnectButton(state: MainViewModel.ConnectionState) {
        val active = state != MainViewModel.ConnectionState.DISCONNECTED
        binding.btnConnect.isActivated = active

        val glowTargetAlpha = if (active) 1f else 0f
        if (binding.vGlow.alpha != glowTargetAlpha) {
            binding.vGlow.animate().alpha(glowTargetAlpha).setDuration(400).start()
        }

        when (state) {
            MainViewModel.ConnectionState.DISCONNECTED -> {
                binding.ivConnectIcon.visibility = View.VISIBLE
                binding.ivStopIcon.visibility = View.GONE
                binding.ivConnectIcon.animate().cancel()
                binding.ivConnectIcon.rotation = 0f
            }
            MainViewModel.ConnectionState.CONNECTING -> {
                binding.ivConnectIcon.visibility = View.VISIBLE
                binding.ivStopIcon.visibility = View.GONE
                binding.ivConnectIcon.animate()
                        .rotationBy(360f)
                        .setDuration(1000)
                        .withEndAction {
                            if (viewModel.connectionState.value == MainViewModel.ConnectionState.CONNECTING)
                                    updateConnectButton(state)
                        }
                        .start()
            }
            MainViewModel.ConnectionState.CONNECTED -> {
                binding.ivConnectIcon.visibility = View.GONE
                binding.ivStopIcon.visibility = View.VISIBLE
            }
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
