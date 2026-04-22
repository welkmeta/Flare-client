package flare.client.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import kotlin.math.cos
import kotlin.math.sin
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import flare.client.app.R
import flare.client.app.data.SettingsManager
import flare.client.app.databinding.ActivityMainBinding
import flare.client.app.databinding.DialogAppSelectionBinding
import flare.client.app.databinding.ItemAppSelectionBinding
import flare.client.app.ui.adapter.ProfileAdapter
import flare.client.app.data.model.DisplayItem
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import flare.client.app.ui.widget.SwipeToDeleteCallback
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eightbitlab.com.blurview.BlurTarget

class MainActivity : AppCompatActivity() {

    
    
    override fun attachBaseContext(newBase: Context) {
        val lang = newBase
            .getSharedPreferences("flare_settings", Context.MODE_PRIVATE)
            .getString("app_language", "auto") ?: "auto"
        val wrapped = if (lang == "en" || lang == "ru") {
            val locale = java.util.Locale(lang)
            java.util.Locale.setDefault(locale)
            val cfg = android.content.res.Configuration(newBase.resources.configuration)
            cfg.setLocale(locale)
            newBase.createConfigurationContext(cfg)
        } else {
            newBase
        }
        super.attachBaseContext(wrapped)
    }


    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settings: SettingsManager

    private lateinit var adapter: ProfileAdapter
    private var selectedServerId: Int? = null

    private var isInitializingSettings = false
    private var simpleEditor: flare.client.app.util.SimpleEditorManager? = null
    private var currentTabIndex = 1

    private var gradientAnimator: ValueAnimator? = null
    private var loadingDialog: Dialog? = null
    private var logJob: kotlinx.coroutines.Job? = null



    
    private var runtimeAccentColor: Int = COLOR_DEFAULT
    private var runtimeAccentEndColor: Int = COLOR_DEFAULT_END

    private val vpnPermLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    viewModel.connectOrDisconnect()
                } else {
                    showSnackbar(getString(R.string.vpn_error_permission_denied))
                }
            }

    private val notificationPermLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    showTestNotification()
                } else {
                    showSnackbar(getString(R.string.onboarding_notifications_error))
                    val baseInclude = findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
                    val swNotif = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_notifications)
                    swNotif?.isChecked = false
                    settings.isStatusNotificationEnabled = false
                }
            }

    private val onboardingNotificationPermLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    val b = binding.layoutOnboarding
                    updateOnboardingPermissionState(b.btnPermissionNotifications, b.ivNotifCheck, true)
                    checkOnboardingPermissions()
                } else {
                    showSnackbar(getString(R.string.onboarding_notifications_error))
                }
            }

    private val batteryPermLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val isIgnoring = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                    .isIgnoringBatteryOptimizations(packageName)
                if (isIgnoring) {
                    val b = binding.layoutOnboarding
                    updateOnboardingPermissionState(b.btnPermissionBattery, b.ivBatteryCheck, true)
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

        val currentUiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (lastUiMode != -1 && lastUiMode != currentUiMode) {
            themeChangedJustNow = true
            lastThemeChangeTime = System.currentTimeMillis()
        }
        lastUiMode = currentUiMode
        settings = SettingsManager(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkThemeTransition()

        binding.root.clipToPadding = false
        binding.root.clipChildren = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomNav.setPadding(
                binding.bottomNav.paddingLeft,
                binding.bottomNav.paddingTop,
                binding.bottomNav.paddingRight,
                systemBars.bottom
            )
            val density = resources.displayMetrics.density
            val topPaddedViews = listOf(
                flare.client.app.R.id.layout_settings,
                flare.client.app.R.id.layout_settings_advanced_container,
                flare.client.app.R.id.layout_settings_ping_container,
                flare.client.app.R.id.layout_settings_base_container,
                flare.client.app.R.id.layout_settings_subscriptions_container,
                flare.client.app.R.id.layout_settings_theme_container,
                flare.client.app.R.id.layout_settings_language_container,
                flare.client.app.R.id.layout_custom_servers_container,
                flare.client.app.R.id.toolbar_json,
                flare.client.app.R.id.toolbar_simple,
                flare.client.app.R.id.toolbar_journal
            )
            topPaddedViews.forEach { id ->
                findViewById<View>(id)?.let { view ->
                    view.setPadding(
                        view.paddingLeft,
                        systemBars.top,
                        view.paddingRight,
                        view.paddingBottom
                    )
                }
            }
            val notificationView = findViewById<View>(flare.client.app.R.id.notification_container)
            if (notificationView != null) {
                val params = notificationView.layoutParams as ViewGroup.MarginLayoutParams
                params.topMargin = systemBars.top + (16 * density).toInt()
                notificationView.layoutParams = params
            }

            insets
        }

        applyBackgroundGradient()

        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()

        if (!settings.isOnboardingCompleted) {
            setupOnboarding()
        } else {
            binding.layoutOnboarding.root.visibility = View.GONE
            initializeMainUI()
        }

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
                        if (binding.layoutOnboarding.root.visibility == View.VISIBLE) {
                            handleOnboardingBack()
                        } else if (binding.layoutJsonEditor.visibility == View.VISIBLE) {
                            viewModel.setEditingProfile(null)
                            viewModel.setEditingSubscription(null)
                        } else if (findViewById<View>(flare.client.app.R.id.layout_simple_editor)?.visibility == View.VISIBLE) {
                            viewModel.setEditingProfile(null)
                            viewModel.setEditingSubscription(null)
                        } else if (findViewById<View>(flare.client.app.R.id.layout_journal_container)?.visibility == View.VISIBLE) {
                            val v = findViewById<View>(flare.client.app.R.id.layout_journal_container)
                            val base = findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
                            if (v != null && base != null) {
                                flare.client.app.util.AnimUtils.navigateBack(v, base)
                                binding.bottomNav.show()
                                logJob?.cancel()
                            }
                        } else if (binding.layoutSettingsAdvancedContainer.root.visibility ==
                                        View.VISIBLE
                        ) {
                            flare.client.app.util.AnimUtils.navigateBack(
                                binding.layoutSettingsAdvancedContainer.root,
                                binding.layoutSettings.root
                            )
                        } else if (binding.layoutSettingsPingContainer.root.visibility == View.VISIBLE) {
                            flare.client.app.util.AnimUtils.navigateBack(
                                binding.layoutSettingsPingContainer.root,
                                binding.layoutSettings.root
                            )
                        } else if (findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)
                                        ?.visibility == View.VISIBLE
                        ) {
                            val v = findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)
                            if (v != null) flare.client.app.util.AnimUtils.navigateBack(v, binding.layoutSettings.root)
                        } else if (findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)
                                        ?.visibility == View.VISIBLE
                        ) {
                            val v = findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)
                            if (v != null) flare.client.app.util.AnimUtils.navigateBack(v, binding.layoutSettings.root)
                        } else if (findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
                                        ?.visibility == View.VISIBLE
                        ) {
                            val v = findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
                            if (v != null) flare.client.app.util.AnimUtils.navigateBack(v, binding.layoutSettings.root)
                        } else if (findViewById<View>(flare.client.app.R.id.layout_settings_language_container)
                                        ?.visibility == View.VISIBLE
                        ) {
                            val v = findViewById<View>(flare.client.app.R.id.layout_settings_language_container)
                            if (v != null) flare.client.app.util.AnimUtils.navigateBack(v, binding.layoutSettings.root)
                        } else if (binding.layoutCustomServersContainer.layoutSshConfig.visibility == View.VISIBLE ||
                                   binding.layoutCustomServersContainer.layoutProtocolSelection.visibility == View.VISIBLE ||
                                   binding.layoutCustomServersContainer.layoutSetupProgressContainer.visibility == View.VISIBLE ||
                                   binding.layoutCustomServersContainer.layoutSetupSuccessContainer.visibility == View.VISIBLE) {
                            handleWizardBack()
                        } else if (binding.bottomNav.isShrunk) {
                            binding.bottomNav.expandToTabs()
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

    private fun initializeMainUI() {
        setupRecyclerView()
        setupButtons()
        setupTimer()
        setupBottomNav()
        setupSettings()
        setupJsonEditor()
        setupSimpleEditor()
        setupServers()
        observeViewModel()
        observeNotifications()
        restorePendingNavScreen()
        
        if (settings.isCustomColorEnabled) {
            val (accent, accentEnd) = getColorsForKey(settings.accentColorKey)
            runtimeAccentColor = accent
            runtimeAccentEndColor = accentEnd
            if (::adapter.isInitialized) {
                adapter.accentColor = accent
            }
            applyAccentColorsToUI(accent, accentEnd)
        }
        if (themeChangedJustNow && settings.pendingNavScreen.isEmpty()) {
            themeChangedJustNow = false
            flare.client.app.ui.notification.AppNotificationManager.showNotification(
                flare.client.app.ui.notification.NotificationType.SUCCESS,
                getString(R.string.notif_theme_changed_auto),
                3
            )
        }
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
                        onEditSubscriptionJson = { sub -> viewModel.setEditingSubscription(sub) },
                        onSubscriptionUpdate = { sub -> viewModel.refreshSubscription(sub) },
                        onProfileLongClick = { profile -> 
                            val link = flare.client.app.util.ProfileExportHelper.exportLink(profile)
                            if (link != null) {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = android.content.ClipData.newPlainText("proxy_link", link)
                                clipboard.setPrimaryClip(clip)
                                showSnackbar(getString(R.string.success_link_copied))
                                
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, link)
                                }
                                startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share_link)))
                            } else {
                                showSnackbar(getString(R.string.error_link_generation))
                            }
                        },
                        onProfileDelete = { profile ->
                            viewModel.deleteProfile(profile.id)
                        }
                )
        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter

        val swipeHandler = SwipeToDeleteCallback(adapter) { position ->
            val item = adapter.currentList[position]
            if (item is DisplayItem.ProfileItem) {
                viewModel.deleteProfile(item.entity.id)
                flare.client.app.ui.notification.AppNotificationManager.showNotification(
                    flare.client.app.ui.notification.NotificationType.SUCCESS,
                    getString(R.string.profile_deleted_success, item.entity.name),
                    3
                )
            }
        }
        val itemTouchHelper = ItemTouchHelper(swipeHandler)
        itemTouchHelper.attachToRecyclerView(binding.rvProfiles)
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
                showSnackbar(getString(R.string.error_clipboard_empty))
            } else {
                viewModel.importFromClipboard(text)
            }
        }
    }

    private fun setupTimer() {
        try {
            val googleSansFlex = ResourcesCompat.getFont(this, R.font.google_sans_flex)
            binding.tvTimer.setTypeface(googleSansFlex)
            binding.tvTimer.setFontVariationSettings("'wght' 500, 'slnt' 0, 'wdth' 90, 'ROND' 100")
            binding.tvTimer.setTextSize(20f)
        } catch (e: Exception) {
            
        }
    }

    private fun setupJsonEditor() {
        binding.btnJsonBack.setOnClickListener {
            viewModel.setEditingProfile(null)
            viewModel.setEditingSubscription(null)
        }

        binding.btnJsonSave.setOnClickListener {
            val profile = viewModel.editingProfile.value
            if (profile != null) {
                val newName = binding.etJsonProfileName.text.toString().trim()
                val newJson = binding.etJsonContent.text.toString()
                if (newName.isNotEmpty()) {
                    viewModel.updateProfile(profile.id, newName, newJson)
                    flare.client.app.ui.notification.AppNotificationManager.showNotification(
                        flare.client.app.ui.notification.NotificationType.SUCCESS,
                        getString(flare.client.app.R.string.json_edit_success, newName),
                        3
                    )
                    viewModel.setEditingProfile(null)
                } else {
                    showSnackbar(getString(R.string.error_empty_name))
                }
            }
        }
    }

    private fun setupSimpleEditor() {
        val simpleView = binding.root.findViewById<View>(flare.client.app.R.id.layout_simple_editor)
        if (simpleView != null) {
            simpleEditor = flare.client.app.util.SimpleEditorManager(
                view = simpleView,
                onSave = { profile ->
                    viewModel.updateProfileFull(profile)
                    flare.client.app.ui.notification.AppNotificationManager.showNotification(
                        flare.client.app.ui.notification.NotificationType.SUCCESS,
                        getString(flare.client.app.R.string.json_edit_success, profile.name),
                        3
                    )
                    viewModel.setEditingProfile(null)
                },
                onClose = {
                    viewModel.setEditingProfile(null)
                }
            )
        }
    }

    private fun setupBottomNav() {
        binding.blurTarget.post {
            val blurTarget = binding.blurTarget as? BlurTarget
            if (blurTarget != null) {
                binding.bottomNav.setupBlur(blurTarget)
            }
        }
        binding.bottomNav.onTabSelected = { index ->
            if (index != currentTabIndex) {
                val oldView = getActiveTabView(currentTabIndex)
                val newView = when (index) {
                    0 -> binding.layoutSettings.root
                    1 -> binding.layoutHome
                    2 -> binding.layoutCustomServersContainer.root
                    else -> null
                }

                if (oldView != null && newView != null) {
                    val isForward = index > currentTabIndex
                    val width = binding.rootLayout.width.toFloat()

                    oldView.animate()
                        .translationX(if (isForward) -width else width)
                        .alpha(0f)
                        .setDuration(250)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction {
                            oldView.visibility = View.GONE
                            oldView.translationX = 0f
                            oldView.alpha = 1f
                        }
                        .start()

                    newView.visibility = View.VISIBLE
                    newView.alpha = 0f
                    newView.translationX = if (isForward) width else -width
                    newView.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(250)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                        .start()
                }

                when (index) {
                    0 -> {
                        if (oldView == null) {
                            binding.layoutHome.visibility = View.GONE
                            binding.layoutCustomServersContainer.root.visibility = View.GONE
                            binding.layoutSettings.root.visibility = View.VISIBLE
                        }
                        binding.layoutSettingsAdvancedContainer.root.visibility = View.GONE
                        binding.layoutSettingsPingContainer.root.visibility = View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_base_container)?.visibility = View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)?.visibility =
                                View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility = View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_language_container)?.visibility = View.GONE
                    }
                    1 -> {
                        if (oldView == null) {
                            binding.layoutHome.visibility = View.VISIBLE
                            binding.layoutSettings.root.visibility = View.GONE
                            binding.layoutCustomServersContainer.root.visibility = View.GONE
                        }
                        binding.layoutSettingsAdvancedContainer.root.visibility = View.GONE
                        binding.layoutSettingsPingContainer.root.visibility = View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_base_container)?.visibility = View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)?.visibility =
                                View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility = View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_language_container)?.visibility = View.GONE
                    }
                    2 -> {
                        if (oldView == null) {
                            binding.layoutHome.visibility = View.GONE
                            binding.layoutSettings.root.visibility = View.GONE
                            binding.layoutCustomServersContainer.root.visibility = View.VISIBLE
                        }
                        binding.layoutSettingsAdvancedContainer.root.visibility = View.GONE
                        binding.layoutSettingsPingContainer.root.visibility = View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_base_container)?.visibility = View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)?.visibility =
                                View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility = View.GONE
                        findViewById<View>(flare.client.app.R.id.layout_settings_language_container)?.visibility = View.GONE

                        if (selectedServerId != null) {
                            binding.bottomNav.shrinkToArrow()
                        }
                    }
                }
                currentTabIndex = index
            }

            if (index != 2) {
                binding.bottomNav.expandToTabs()
                binding.bottomNav.show()
            }
        }
    }

    private fun getActiveTabView(index: Int): View? {
        return when (index) {
            1 -> binding.layoutHome
            2 -> binding.layoutCustomServersContainer.root
            0 -> {
                val settingsViews = listOf(
                    binding.layoutSettings.root,
                    binding.layoutSettingsAdvancedContainer.root,
                    binding.layoutSettingsPingContainer.root,
                    findViewById<View>(flare.client.app.R.id.layout_settings_base_container),
                    findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container),
                    findViewById<View>(flare.client.app.R.id.layout_settings_theme_container),
                    findViewById<View>(flare.client.app.R.id.layout_settings_language_container)
                )
                settingsViews.firstOrNull { it?.visibility == View.VISIBLE }
            }
            else -> null
        }
    }

    private fun setupServers() {
        val serversBinding = binding.layoutCustomServersContainer
        serversBinding.btnFlareServers.setOnClickListener {
            toggleServerSelection(R.id.btn_flare_servers)
        }
        serversBinding.btnCreateServer.setOnClickListener {
            toggleServerSelection(R.id.btn_create_server)
        }

        binding.bottomNav.onArrowClick = {
            if (selectedServerId == R.id.btn_create_server) {
                showSshConfigAnimation(true)
            }
        }
        setupSshValidation()

        serversBinding.tvSetupStatus.setFactory {
            TextView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                typeface = try {
                    ResourcesCompat.getFont(this@MainActivity, R.font.geologica_medium)
                } catch (e: Exception) {
                    android.graphics.Typeface.DEFAULT_BOLD
                }
            }
        }
    }

    private fun setupSshValidation() {
        val serversBinding = binding.layoutCustomServersContainer
        val name = serversBinding.etSshProfileName
        val ips = serversBinding.etSshIp
        val users = serversBinding.etSshUser
        val pass = serversBinding.etSshPassword

        val fields = listOf(name, ips, users, pass)
        fields.forEach { et ->
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    checkAllSshFieldsValid()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            et.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (!hasFocus) {
                    updateFieldValidationUI(v as android.widget.EditText)
                }
            }
        }
    }

    private fun updateFieldValidationUI(et: android.widget.EditText) {
        val serversBinding = binding.layoutCustomServersContainer
        val isValid = when (et.id) {
            R.id.et_ssh_ip -> validateIp(et.text.toString())
            else -> et.text.toString().isNotBlank()
        }

        val checkIcon = when (et.id) {
            R.id.et_ssh_profile_name -> null
            R.id.et_ssh_ip -> serversBinding.ivCheckIp
            R.id.et_ssh_user -> serversBinding.ivCheckUser
            R.id.et_ssh_password -> serversBinding.ivCheckPassword
            else -> null
        }

        if (isValid && et.text.isNotEmpty()) {
            checkIcon?.visibility = View.VISIBLE
            et.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#1A30D158")
            )
            et.setBackgroundResource(R.drawable.bg_grouped_all)
        } else {
            checkIcon?.visibility = View.GONE
            et.backgroundTintList = null
        }
    }

    private fun validateIp(ip: String): Boolean {
        val regex = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]{1,5})?$")
        return regex.matches(ip)
    }

    private fun checkAllSshFieldsValid() {
        val serversBinding = binding.layoutCustomServersContainer
        val ipValid = validateIp(serversBinding.etSshIp.text.toString())
        val userValid = serversBinding.etSshUser.text.toString().isNotBlank()
        val passValid = serversBinding.etSshPassword.text.toString().isNotBlank()
        val nameValid = serversBinding.etSshProfileName.text.toString().isNotBlank()

        if (ipValid && userValid && passValid && nameValid) {
            binding.bottomNav.show()
        } else if (currentWizardStep == WizardStep.SSH_CONFIG || currentWizardStep == WizardStep.PROTOCOL || currentWizardStep == WizardStep.XRAY_CONFIG) {
            binding.bottomNav.hide()
        }
    }

    private enum class WizardStep {
        CARDS, SSH_CONFIG, PROTOCOL, XRAY_CONFIG, PROGRESS, SUCCESS
    }
    private var currentWizardStep = WizardStep.CARDS

    private fun handleWizardBack() {
        when (currentWizardStep) {
            WizardStep.SSH_CONFIG -> showWizardStep(WizardStep.CARDS)
            WizardStep.PROTOCOL -> showWizardStep(WizardStep.SSH_CONFIG)
            WizardStep.XRAY_CONFIG -> showWizardStep(WizardStep.PROTOCOL)
            WizardStep.PROGRESS -> {  }
            WizardStep.SUCCESS -> showWizardStep(WizardStep.CARDS)
            else -> {}
        }
    }

    private fun showWizardStep(step: WizardStep) {
        val b = binding.layoutCustomServersContainer
        val width = b.root.width.toFloat()
        val currentView = when (currentWizardStep) {
            WizardStep.CARDS -> b.layoutServerCards
            WizardStep.SSH_CONFIG -> b.layoutSshConfig
            WizardStep.PROTOCOL -> b.layoutProtocolSelection
            WizardStep.XRAY_CONFIG -> b.layoutXrayConfig
            WizardStep.PROGRESS -> b.layoutSetupProgressContainer
            WizardStep.SUCCESS -> b.layoutSetupSuccessContainer
        }

        val nextView = when (step) {
            WizardStep.CARDS -> b.layoutServerCards
            WizardStep.SSH_CONFIG -> b.layoutSshConfig
            WizardStep.PROTOCOL -> b.layoutProtocolSelection
            WizardStep.XRAY_CONFIG -> b.layoutXrayConfig
            WizardStep.PROGRESS -> b.layoutSetupProgressContainer
            WizardStep.SUCCESS -> b.layoutSetupSuccessContainer
        }

        if (currentWizardStep == step) return

        val isForward = step.ordinal > currentWizardStep.ordinal

        currentView.animate()
            .translationX(if (isForward) -width else width)
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction { currentView.visibility = View.GONE }
            .start()

        nextView.visibility = View.VISIBLE
        nextView.translationX = if (isForward) width else -width
        nextView.alpha = 0f
        nextView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()

        currentWizardStep = step

        if (step == WizardStep.CARDS) {
            binding.bottomNav.show()
            binding.bottomNav.expandToTabs()
            selectedServerId = null
            updateServerSelectionUI()
            resetSshValidationUI()
            binding.bottomNav.onArrowClick = {
                if (selectedServerId == R.id.btn_create_server) {
                    showSshConfigAnimation(true)
                }
            }
        } else if (step == WizardStep.SSH_CONFIG || step == WizardStep.PROTOCOL || step == WizardStep.XRAY_CONFIG) {
            binding.bottomNav.show()
            binding.bottomNav.shrinkToArrow()
            binding.bottomNav.onArrowClick = {
                if (step == WizardStep.SSH_CONFIG) showWizardStep(WizardStep.PROTOCOL)
                else if (step == WizardStep.PROTOCOL) showWizardStep(WizardStep.XRAY_CONFIG)
                else if (step == WizardStep.XRAY_CONFIG) startSshSetup()
            }
        } else {
            binding.bottomNav.hide()
        }
    }

    private fun startSshSetup() {
        val b = binding.layoutCustomServersContainer
        val profileName = b.etSshProfileName.text.toString().let { it.ifEmpty { b.etSshIp.text.toString() } }
        val host = b.etSshIp.text.toString()
        val sshPort = b.etSshPort.text.toString().toIntOrNull() ?: 22
        val vpnPort = b.etXrayPort.text.toString().toIntOrNull() ?: 443
        val sni = b.etXraySni.text.toString().let { it.ifEmpty { "google.com" } }
        val user = b.etSshUser.text.toString()
        val pass = b.etSshPassword.text.toString()

        showWizardStep(WizardStep.PROGRESS)

        val setupManager = flare.client.app.util.SshSetupManager(this)
        b.tvSetupStatus.setText(getString(R.string.servers_setup_title))

        var currentUiProgress = 0f
        var progressAnimator: ValueAnimator? = null

        lifecycleScope.launch {
            setupManager.progress.collect { progressValue ->
                val targetProgress = progressValue.toFloat()
                progressAnimator?.cancel()
                progressAnimator = ValueAnimator.ofFloat(currentUiProgress, targetProgress).apply {
                    duration = 1000
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animator ->
                        val value = animator.animatedValue as Float
                        currentUiProgress = value
                        b.pbSetupProgress.progress = value.toInt()
                        b.tvSetupPercent.setProgress(value.toInt(), true)
                    }
                    start()
                }

                if (progressValue == 100) {
                    val colorFrom = Color.WHITE
                    val colorTo = Color.parseColor("#30D158")
                    val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
                    colorAnimation.duration = 500
                    colorAnimation.addUpdateListener { animator ->
                        b.pbSetupProgress.progressTintList = ColorStateList.valueOf(animator.animatedValue as Int)
                    }
                    colorAnimation.start()
                }
            }
        }

        lifecycleScope.launch {
            setupManager.status.collect { status ->
                if (status.isNotEmpty()) {
                    b.tvSetupStatus.setText(status)
                }
            }
        }

        lifecycleScope.launch {
            val uri = setupManager.setupXray(host, sshPort, user, pass, vpnPort, sni)
            if (uri != null) {
                finalizeServerCreation(profileName, uri)
            } else {
                showWizardStep(WizardStep.SSH_CONFIG)
                flare.client.app.ui.notification.AppNotificationManager.showNotification(
                    flare.client.app.ui.notification.NotificationType.ERROR,
                    "Ошибка при настройке сервера",
                    3
                )
            }
        }
    }

    private fun finalizeServerCreation(profileName: String, uri: String) {
        viewModel.addPrivateServer(uri, profileName)
        showWizardStep(WizardStep.SUCCESS)
        val successAction = {
            showWizardStep(WizardStep.CARDS)
            binding.bottomNav.selectTab(1, true)
        }
        binding.layoutCustomServersContainer.layoutSetupSuccessContainer.setOnClickListener { successAction() }
        binding.layoutCustomServersContainer.btnGoHome.setOnClickListener { successAction() }
    }

    private fun showSshConfigAnimation(show: Boolean) {
        if (show) showWizardStep(WizardStep.SSH_CONFIG)
        else showWizardStep(WizardStep.CARDS)
    }

    private fun resetSshValidationUI() {
        val b = binding.layoutCustomServersContainer
        val fields = listOf(b.etSshProfileName, b.etSshIp, b.etSshUser, b.etSshPassword)
        val checks = listOf(b.ivCheckIp, b.ivCheckUser, b.ivCheckPassword)
        fields.forEach { it.text.clear(); it.backgroundTintList = null }
        checks.forEach { it.visibility = View.GONE }
    }

    private fun toggleServerSelection(id: Int) {
        if (selectedServerId == id) {
            selectedServerId = null
            binding.bottomNav.expandToTabs()
        } else {
            if (selectedServerId == null) {
                binding.bottomNav.shrinkToArrow()
            }
            selectedServerId = id
        }
        updateServerSelectionUI()
    }

    private fun updateServerSelectionUI() {
        val serversBinding = binding.layoutCustomServersContainer
        val flareSelected = selectedServerId == R.id.btn_flare_servers
        val createSelected = selectedServerId == R.id.btn_create_server
        serversBinding.btnFlareServers.setBackgroundResource(
            if (flareSelected) R.drawable.bg_server_card_selected else R.drawable.bg_server_card
        )
        if (flareSelected) {
            serversBinding.btnFlareServers.backgroundTintList = android.content.res.ColorStateList.valueOf(runtimeAccentColor)
        } else {
            serversBinding.btnFlareServers.backgroundTintList = null
        }
        val flareContentColor = if (flareSelected) ContextCompat.getColor(this, R.color.white)
                                else ContextCompat.getColor(this, R.color.text_primary)
        serversBinding.ivFlareIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (flareSelected) ContextCompat.getColor(this, R.color.white)
            else runtimeAccentColor
        )
        serversBinding.tvFlareTitle.setTextColor(flareContentColor)
        serversBinding.tvFlareDesc.setTextColor(if (flareSelected) ContextCompat.getColor(this, R.color.white) else ContextCompat.getColor(this, R.color.text_secondary))
        serversBinding.tvFlareDesc.alpha = if (flareSelected) 0.8f else 1.0f

        serversBinding.btnCreateServer.setBackgroundResource(
            if (createSelected) R.drawable.bg_server_card_selected else R.drawable.bg_server_card
        )
        if (createSelected) {
            serversBinding.btnCreateServer.backgroundTintList = android.content.res.ColorStateList.valueOf(runtimeAccentColor)
        } else {
            serversBinding.btnCreateServer.backgroundTintList = null
        }
        val createContentColor = if (createSelected) ContextCompat.getColor(this, R.color.white)
                                 else ContextCompat.getColor(this, R.color.text_primary)
        serversBinding.ivCreateIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (createSelected) ContextCompat.getColor(this, R.color.white)
            else runtimeAccentColor
        )
        serversBinding.tvCreateTitle.setTextColor(createContentColor)
        serversBinding.tvCreateDesc.setTextColor(if (createSelected) ContextCompat.getColor(this, R.color.white) else ContextCompat.getColor(this, R.color.text_secondary))
        serversBinding.tvCreateDesc.alpha = if (createSelected) 0.8f else 1.0f
    }

    private fun setupSettings() {
        binding.layoutSettings.btnSettingsBase.setOnClickListener {
            val target = findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
            if (target != null) {
                flare.client.app.util.AnimUtils.morphNavigate(it, binding.layoutSettings.root, target, binding.rootLayout)
            }
        }
        binding.layoutSettings.btnSettingsAdvanced.setOnClickListener {
            flare.client.app.util.AnimUtils.morphNavigate(
                it,
                binding.layoutSettings.root,
                binding.layoutSettingsAdvancedContainer.root,
                binding.rootLayout
            )
        }
        binding.layoutSettings.btnSettingsTheme.setOnClickListener {
            val target = findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)
            if (target != null) {
                flare.client.app.util.AnimUtils.morphNavigate(it, binding.layoutSettings.root, target, binding.rootLayout)
            }
        }
        binding.layoutSettings.btnSettingsLanguage.setOnClickListener {
            val target = findViewById<View>(flare.client.app.R.id.layout_settings_language_container)
            if (target != null) {
                flare.client.app.util.AnimUtils.morphNavigate(it, binding.layoutSettings.root, target, binding.rootLayout)
            }
        }
        binding.layoutSettings.btnSettingsPing.setOnClickListener {
            flare.client.app.util.AnimUtils.morphNavigate(
                it,
                binding.layoutSettings.root,
                binding.layoutSettingsPingContainer.root,
                binding.rootLayout
            )
        }
        binding.layoutSettings.btnSettingsSubscriptions.setOnClickListener {
            val target = findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)
            if (target != null) {
                flare.client.app.util.AnimUtils.morphNavigate(it, binding.layoutSettings.root, target, binding.rootLayout)
            }
        }

        isInitializingSettings = true
        setupAdvancedSettings()
        setupPingSettings()
        setupBaseSettings()
        setupSubscriptionsSettings()
        setupThemeSettings()
        setupLanguageSettings()
        isInitializingSettings = false
    }

    private fun setupLanguageSettings() {
        val langView = findViewById<View>(flare.client.app.R.id.layout_settings_language_container) ?: return
        langView.findViewById<View>(flare.client.app.R.id.btn_language_back)?.setOnClickListener {
            flare.client.app.util.AnimUtils.navigateBack(langView, binding.layoutSettings.root)
        }

        val tvValue = langView.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_language_value)
        
        fun updateLabel() {
            val current = settings.appLanguage
            tvValue?.text = when (current) {
                "en" -> "English"
                "ru" -> "Русский"
                else -> getString(R.string.language_auto)
            }
        }
        updateLabel()

        langView.findViewById<View>(flare.client.app.R.id.btn_app_language_selector)?.setOnClickListener { view ->
            val options = listOf(
                getString(flare.client.app.R.string.language_auto),
                "English",
                "Русский"
            )
            showPopupMenu(view, options) { selected ->
                val langCode = when (selected) {
                    "English" -> "en"
                    "Русский" -> "ru"
                    else -> "auto"
                }
                
                val currentEffectiveLang = if (resources.configuration.locales.isEmpty) "en" else resources.configuration.locales[0].language
                val isSettingSame = settings.appLanguage == langCode
                val isEffectivelySame = (langCode == "auto" && isSettingSame) || (langCode != "auto" && langCode == currentEffectiveLang)

                if (!isSettingSame) {
                    settings.appLanguage = langCode
                    updateLabel()

                    if (!isEffectivelySame) {
                        val appLocales: LocaleListCompat = when (langCode) {
                            "en" -> LocaleListCompat.forLanguageTags("en")
                            "ru" -> LocaleListCompat.forLanguageTags("ru")
                            else -> LocaleListCompat.getEmptyLocaleList()
                        }

                        val locale = when (langCode) {
                            "en" -> java.util.Locale("en")
                            "ru" -> java.util.Locale("ru")
                            else -> java.util.Locale.getDefault()
                        }
                        val config = android.content.res.Configuration(resources.configuration)
                        config.setLocale(locale)
                        val localizedContext = createConfigurationContext(config)

                        flare.client.app.ui.notification.AppNotificationManager.showNotification(
                            flare.client.app.ui.notification.NotificationType.WARNING,
                            localizedContext.getString(R.string.language_restart_hint),
                            10,
                            localizedContext.getString(R.string.btn_apply)
                        ) {
                            AppCompatDelegate.setApplicationLocales(appLocales)
                        }
                    }
                }
            }
        }
    }

    private fun setupSubscriptionsSettings() {
        val subView =
                findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)
                        ?: return
        subView.findViewById<View>(flare.client.app.R.id.btn_subscriptions_back)?.setOnClickListener {
            flare.client.app.util.AnimUtils.navigateBack(subView, binding.layoutSettings.root)
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
            viewModel.startAutoUpdateJob()
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
                        viewModel.startAutoUpdateJob()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val v = s?.toString()?.toLongOrNull() ?: return
                        if (v < 30) {
                            etUpdateInterval.removeTextChangedListener(this)
                            etUpdateInterval.setText("30")
                            etUpdateInterval.setSelection(2)
                            settings.subAutoUpdateInterval = "30"
                            viewModel.startAutoUpdateJob()
                            etUpdateInterval.addTextChangedListener(this)
                        }
                    }
                }
        )
    }

    private fun setupBaseSettings() {
        val baseInclude = findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
        baseInclude?.findViewById<View>(flare.client.app.R.id.btn_base_back)?.setOnClickListener {
            flare.client.app.util.AnimUtils.navigateBack(baseInclude, binding.layoutSettings.root)
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
                if (settings.isSplitTunnelingEnabled != isChecked) {
                    settings.isSplitTunnelingEnabled = isChecked
                    showSettingsNotification()
                }
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

        val swNotif = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_notifications)
        val btnToggleNotif = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_toggle_notifications)
        if (swNotif != null && btnToggleNotif != null) {
            swNotif.isChecked = settings.isStatusNotificationEnabled
            btnToggleNotif.setOnClickListener {
                swNotif.toggle()
            }

            swNotif.setOnCheckedChangeListener { _, isChecked ->
                settings.isStatusNotificationEnabled = isChecked
                if (isChecked) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                        this,
                                        android.Manifest.permission.POST_NOTIFICATIONS
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            showTestNotification()
                        }
                    } else {
                        showTestNotification()
                    }
                }
            }
        }

        val swBestProfileNotif = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_best_profile_notif)
        val btnToggleBestProfileNotif = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_toggle_best_profile_notif)
        if (swBestProfileNotif != null && btnToggleBestProfileNotif != null) {
            swBestProfileNotif.isChecked = settings.isBestProfileNotificationEnabled
            btnToggleBestProfileNotif.setOnClickListener {
                swBestProfileNotif.toggle()
            }

            swBestProfileNotif.setOnCheckedChangeListener { _, isChecked ->
                settings.isBestProfileNotificationEnabled = isChecked
            }
        }
        
        val swHwid = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_hwid)
        val btnToggleHwid = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_toggle_hwid)
        if (swHwid != null && btnToggleHwid != null) {
            swHwid.isChecked = settings.isHwidEnabled
            btnToggleHwid.setOnClickListener {
                swHwid.toggle()
            }
            swHwid.setOnCheckedChangeListener { _, isChecked ->
                settings.isHwidEnabled = isChecked
            }
        }

        val swCoreLog = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_core_log)
        val btnToggleCoreLog = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_toggle_core_log)
        val layoutCoreLogSub = baseInclude?.findViewById<View>(flare.client.app.R.id.layout_core_log_sub)
        val btnCoreLogLevel = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_core_log_level)
        val tvCoreLogLevelValue = baseInclude?.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_core_log_level_value)
        val blockLogging = baseInclude?.findViewById<android.view.ViewGroup>(flare.client.app.R.id.block_logging)

        if (swCoreLog != null && btnToggleCoreLog != null && layoutCoreLogSub != null && blockLogging != null) {
            swCoreLog.isChecked = settings.isCoreLogEnabled
            layoutCoreLogSub.visibility = if (settings.isCoreLogEnabled) View.VISIBLE else View.GONE
            btnToggleCoreLog.setBackgroundResource(
                if (settings.isCoreLogEnabled) flare.client.app.R.drawable.bg_grouped_top
                else flare.client.app.R.drawable.bg_grouped_all
            )
            tvCoreLogLevelValue?.text = settings.coreLogLevel

            btnToggleCoreLog.setOnClickListener {
                swCoreLog.toggle()
            }

            swCoreLog.setOnCheckedChangeListener { _, isChecked ->
                settings.isCoreLogEnabled = isChecked
                android.transition.TransitionManager.beginDelayedTransition(
                    blockLogging,
                    android.transition.AutoTransition().setDuration(200)
                )
                layoutCoreLogSub.visibility = if (isChecked) View.VISIBLE else View.GONE
                btnToggleCoreLog.setBackgroundResource(
                    if (isChecked) flare.client.app.R.drawable.bg_grouped_top
                    else flare.client.app.R.drawable.bg_grouped_all
                )
            }

            btnCoreLogLevel?.setOnClickListener { view ->
                val levels = listOf("trace", "debug", "info", "warn", "error", "fatal", "panic")
                val items = levels.mapIndexed { index, level ->
                    flare.client.app.util.GlassUtils.MenuItem(index, level) {
                        settings.coreLogLevel = level
                        tvCoreLogLevelValue?.text = level
                    }
                }
                flare.client.app.util.GlassUtils.showGlassMenu(view, items)
            }

            baseInclude.findViewById<View>(flare.client.app.R.id.btn_view_journal)?.setOnClickListener {
                setupJournal()
            }
        }

        updateSplitTunnelingDesc()

        baseInclude?.findViewById<View>(flare.client.app.R.id.btn_change_apps)?.setOnClickListener { btn ->
            val pb = baseInclude.findViewById<android.widget.ProgressBar>(flare.client.app.R.id.pb_change_apps_loading)
            val button = btn as? androidx.appcompat.widget.AppCompatButton ?: return@setOnClickListener

            pb?.visibility = View.VISIBLE
            val originalText = button.text
            button.text = ""
            button.isEnabled = false

            lifecycleScope.launch {
                val apps = withContext(Dispatchers.Default) {
                    val selectedPackages = settings.splitTunnelingApps.toMutableSet()
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    packageManager.queryIntentActivities(intent, 0)
                        .map { it.activityInfo.applicationInfo }
                        .distinctBy { it.packageName }
                        .filter {
                            it.packageName == packageName || (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0)
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
                }

                pb?.visibility = View.GONE
                button.text = originalText
                button.isEnabled = true

                showAppSelectionDialog(apps)
            }
        }

        val swBestProfile = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_best_profile)
        val layoutBestProfileSub = baseInclude?.findViewById<View>(flare.client.app.R.id.layout_best_profile_sub)
        val btnToggleBestProfile = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_toggle_best_profile)
        val blockBestProfile = baseInclude?.findViewById<android.view.ViewGroup>(flare.client.app.R.id.block_best_profile)
        val etBestProfileInterval = baseInclude?.findViewById<android.widget.EditText>(flare.client.app.R.id.et_best_profile_interval)
        val btnOnlyConnected = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_best_profile_only_connected)
        val tvOnlyConnectedValue = baseInclude?.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_best_profile_only_connected_value)

        if (swBestProfile != null && layoutBestProfileSub != null && btnToggleBestProfile != null && blockBestProfile != null) {
            swBestProfile.isChecked = settings.isBestProfileEnabled
            layoutBestProfileSub.visibility = if (settings.isBestProfileEnabled) View.VISIBLE else View.GONE
            btnToggleBestProfile.setBackgroundResource(
                if (settings.isBestProfileEnabled) flare.client.app.R.drawable.bg_grouped_top
                else flare.client.app.R.drawable.bg_grouped_all
            )
            etBestProfileInterval?.setText(settings.bestProfileInterval)
            tvOnlyConnectedValue?.text = if (settings.isBestProfileOnlyIfConnected) getString(R.string.option_yes) else getString(R.string.option_no)

            swBestProfile.setOnCheckedChangeListener { _, isChecked ->
                settings.isBestProfileEnabled = isChecked
                android.transition.TransitionManager.beginDelayedTransition(
                    blockBestProfile,
                    android.transition.AutoTransition().setDuration(200)
                )
                layoutBestProfileSub.visibility = if (isChecked) View.VISIBLE else View.GONE
                btnToggleBestProfile.setBackgroundResource(
                    if (isChecked) flare.client.app.R.drawable.bg_grouped_top
                    else flare.client.app.R.drawable.bg_grouped_all
                )
                viewModel.startBestProfileJob()
            }

            etBestProfileInterval?.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        settings.bestProfileInterval = s.toString()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val v = s?.toString()?.toLongOrNull() ?: return
                        if (v > 345600L) {
                            etBestProfileInterval.removeTextChangedListener(this)
                            etBestProfileInterval.setText("345600")
                            etBestProfileInterval.setSelection(etBestProfileInterval.text.length)
                            settings.bestProfileInterval = "345600"
                            etBestProfileInterval.addTextChangedListener(this)
                        }
                    }
                }
            )

            etBestProfileInterval?.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val v = etBestProfileInterval.text?.toString()?.toLongOrNull() ?: 0L
                    if (v < 10L) {
                        etBestProfileInterval.setText("10")
                        settings.bestProfileInterval = "10"
                        flare.client.app.ui.notification.AppNotificationManager.showNotification(
                            flare.client.app.ui.notification.NotificationType.WARNING,
                            getString(R.string.settings_ping_interval_min_warning),
                            2
                        )
                    }
                    viewModel.startBestProfileJob()
                }
            }

            btnOnlyConnected?.setOnClickListener { view ->
                val items = listOf(
                    flare.client.app.util.GlassUtils.MenuItem(0, getString(R.string.option_yes)) {
                        settings.isBestProfileOnlyIfConnected = true
                        tvOnlyConnectedValue?.text = getString(R.string.option_yes)
                        viewModel.startBestProfileJob()
                    },
                    flare.client.app.util.GlassUtils.MenuItem(1, getString(R.string.option_no)) {
                        settings.isBestProfileOnlyIfConnected = false
                        tvOnlyConnectedValue?.text = getString(R.string.option_no)
                        viewModel.startBestProfileJob()
                    }
                )
                flare.client.app.util.GlassUtils.showGlassMenu(view, items)
            }

            val swUpdateCheck = baseInclude?.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_update_check)
            val layoutUpdateSub = baseInclude?.findViewById<View>(flare.client.app.R.id.layout_update_check_sub)
            val btnToggleUpdate = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_toggle_update_check)
            val blockUpdate = baseInclude?.findViewById<android.view.ViewGroup>(flare.client.app.R.id.block_update_check)
            val btnFreq = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_update_frequency)
            val tvFreqValue = baseInclude?.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_update_frequency_value)

            if (swUpdateCheck != null && layoutUpdateSub != null && btnToggleUpdate != null && blockUpdate != null) {
                swUpdateCheck.isChecked = settings.isUpdateCheckEnabled
                layoutUpdateSub.visibility = if (settings.isUpdateCheckEnabled) View.VISIBLE else View.GONE
                btnToggleUpdate.setBackgroundResource(
                    if (settings.isUpdateCheckEnabled) flare.client.app.R.drawable.bg_grouped_top
                    else flare.client.app.R.drawable.bg_grouped_all
                )
                tvFreqValue?.text = getUpdateFreqDisplay(settings.updateCheckFrequency)

                swUpdateCheck.setOnCheckedChangeListener { _, isChecked ->
                    settings.isUpdateCheckEnabled = isChecked
                    android.transition.TransitionManager.beginDelayedTransition(
                        blockUpdate,
                        android.transition.AutoTransition().setDuration(200)
                    )
                    layoutUpdateSub.visibility = if (isChecked) View.VISIBLE else View.GONE
                    btnToggleUpdate.setBackgroundResource(
                        if (isChecked) flare.client.app.R.drawable.bg_grouped_top
                        else flare.client.app.R.drawable.bg_grouped_all
                    )
                    viewModel.startUpdateCheckJob()
                }

                btnFreq?.setOnClickListener { view ->
                    val options = listOf(
                        getString(R.string.update_freq_daily),
                        getString(R.string.update_freq_weekly),
                        getString(R.string.update_freq_monthly)
                    )
                    val keys = listOf("daily", "weekly", "monthly")
                    val items = options.mapIndexed { index, text ->
                        flare.client.app.util.GlassUtils.MenuItem(index, text) {
                            val key = keys.getOrElse(index) { "daily" }
                            settings.updateCheckFrequency = key
                            tvFreqValue?.text = text
                            viewModel.startUpdateCheckJob()
                        }
                    }
                    flare.client.app.util.GlassUtils.showGlassMenu(view, items)
                }
            }
        }
    }

    private fun updateSplitTunnelingDesc() {
        val baseInclude = findViewById<View>(flare.client.app.R.id.layout_settings_base_container) ?: return
        val tvDesc = baseInclude.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_split_tunneling_desc)
        val appsCount = settings.splitTunnelingApps.size
        val sitesCount = settings.splitTunnelingSites.size

        if (appsCount == 0 && sitesCount == 0) {
            tvDesc?.text = getString(flare.client.app.R.string.split_tunneling_desc_default)
            return
        }

        tvDesc?.text = buildString {
            append(getString(R.string.label_selected))
            append(" ")
            if (appsCount > 0) {
                append("$appsCount ${resources.getQuantityString(R.plurals.plural_apps, appsCount)}")
            }
            if (appsCount > 0 && sitesCount > 0) {
                append(getString(R.string.label_and))
            }
            if (sitesCount > 0) {
                append("$sitesCount ${resources.getQuantityString(R.plurals.plural_sites, sitesCount)}")
            }
        }
    }

    private fun pluralApps(n: Int): String {
        return resources.getQuantityString(R.plurals.plural_apps, n)
    }

    private fun pluralSites(n: Int): String {
        return resources.getQuantityString(R.plurals.plural_sites, n)
    }

    private fun showAppSelectionDialog(allApps: List<AppListItem>) {
        val dialog = android.app.Dialog(this)
        val dialogBinding = flare.client.app.databinding.DialogAppSelectionBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (settings.isCustomColorEnabled) {
            applyAccentToViewTree(dialogBinding.root, runtimeAccentColor)
        }

        val selectedPackages = settings.splitTunnelingApps.toMutableSet()
        val sitesSet = settings.splitTunnelingSites.toMutableSet()
        
        var currentTabIsApps = true
        var tempModeApps = settings.splitTunnelingModeApps
        var tempModeSites = settings.splitTunnelingModeSites

        fun updateModeUI() {
            val mode = if (currentTabIsApps) tempModeApps else tempModeSites
            val textRes = if (mode == "whitelist") R.string.split_mode_whitelist else R.string.split_mode_blacklist
            val tvModeValue = dialogBinding.root.findViewById<android.widget.TextView>(R.id.tv_mode_value)
            tvModeValue?.text = getString(textRes)
        }
        
        dialogBinding.root.findViewById<View>(R.id.btn_mode_selection)?.setOnClickListener { view ->
            val items = listOf(
                flare.client.app.util.GlassUtils.MenuItem(0, getString(R.string.split_mode_whitelist)) {
                    if (currentTabIsApps) tempModeApps = "whitelist" else tempModeSites = "whitelist"
                    updateModeUI()
                },
                flare.client.app.util.GlassUtils.MenuItem(1, getString(R.string.split_mode_blacklist)) {
                    if (currentTabIsApps) tempModeApps = "blacklist" else tempModeSites = "blacklist"
                    updateModeUI()
                }
            )
            flare.client.app.util.GlassUtils.showGlassMenu(view, items)
        }

        val tabContainer = dialogBinding.root.findViewById<View>(R.id.tab_container)
        val tabIndicator = dialogBinding.root.findViewById<flare.client.app.ui.widget.LiquidPillView>(R.id.tab_indicator)
        val tabApps = dialogBinding.root.findViewById<android.widget.ImageView>(R.id.tab_apps)
        val tabSites = dialogBinding.root.findViewById<android.widget.ImageView>(R.id.tab_sites)
        val layoutApps = dialogBinding.root.findViewById<View>(R.id.layout_apps)
        val layoutSites = dialogBinding.root.findViewById<View>(R.id.layout_sites)
        val etSites = dialogBinding.root.findViewById<android.widget.EditText>(R.id.et_sites)
        val tvDialogTitle = dialogBinding.root.findViewById<android.widget.TextView>(R.id.tv_dialog_title)
        
        etSites?.setText(sitesSet.joinToString("\n"))

        val dp = resources.displayMetrics.density
        var isDragging = false

        fun updateTabVisuals(toApps: Boolean) {
            val inactiveColor = androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
            val activeColor = androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.text_primary)
            tabApps?.imageTintList = android.content.res.ColorStateList.valueOf(if (toApps) activeColor else inactiveColor)
            tabSites?.imageTintList = android.content.res.ColorStateList.valueOf(if (!toApps) activeColor else inactiveColor)
        }

        fun switchTab(toApps: Boolean, animateIndicator: Boolean = true) {
            val containerWidth = tabContainer?.width?.toFloat() ?: 0f
            if (containerWidth > 0f) {
                val halfW = containerWidth / 2f
                val pad = 4 * dp
                val tL = if (toApps) pad else halfW + pad
                val tR = if (toApps) halfW - pad else containerWidth - pad

                if (animateIndicator) {
                    tabIndicator?.animateToBounds(tL, tR, 300)
                } else {
                    tabIndicator?.leftBound = tL
                    tabIndicator?.rightBound = tR
                    tabIndicator?.invalidate()
                }
            }
            
            if (currentTabIsApps == toApps && !isDragging) return
            
            updateTabVisuals(toApps)
            tvDialogTitle?.text = getString(if (toApps) R.string.dialog_apps_title else R.string.dialog_domens_title)

            val currentLayout = if (currentTabIsApps) layoutApps else layoutSites
            val nextLayout = if (toApps) layoutApps else layoutSites
            
            if (currentTabIsApps != toApps && currentLayout?.visibility == View.VISIBLE) {
                val parentWidth = (layoutApps?.parent as? View)?.width?.toFloat() ?: 500f
                currentLayout.animate()?.translationX(if (toApps) parentWidth else -parentWidth)?.alpha(0f)?.setDuration(200)?.withEndAction {
                    currentLayout.visibility = View.GONE
                }?.start()
                
                nextLayout?.visibility = View.VISIBLE
                nextLayout?.translationX = if (toApps) -parentWidth else parentWidth
                nextLayout?.alpha = 0f
                nextLayout?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(200)?.start()
            }
            
            currentTabIsApps = toApps
            updateModeUI()
        }

        var dragStartX = 0f
        var startBoundLeft = 0f
        var startBoundRight = 0f
        var isClick = false

        tabContainer?.setOnTouchListener { v, event ->
            val w = v.width.toFloat()
            val pad = 4 * dp
            val halfW = w / 2f
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val buf = 32 * dp
                    val isOnIndicator = tabIndicator != null && event.x >= tabIndicator.leftBound - buf && event.x <= tabIndicator.rightBound + buf
                    
                    isDragging = isOnIndicator
                    dragStartX = event.rawX
                    isClick = true
                    
                    if (isDragging) {
                        startBoundLeft = tabIndicator.leftBound
                        startBoundRight = tabIndicator.rightBound
                        tabIndicator.animateGlow(1.0f, 100)
                        tabIndicator.animateExpansion(5f, 250)
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    return@setOnTouchListener true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    if (Math.abs(dx) > 5 * dp) isClick = false
                    
                    if (isDragging) {
                        val stretch = (Math.abs(dx) * 0.05f).coerceAtMost(10 * dp)
                        if (dx > 0) {
                            tabIndicator?.leftBound = (startBoundLeft + dx).coerceAtLeast(pad)
                            tabIndicator?.rightBound = (startBoundRight + dx + stretch).coerceAtMost(w - pad)
                        } else {
                            tabIndicator?.leftBound = (startBoundLeft + dx - stretch).coerceAtLeast(pad)
                            tabIndicator?.rightBound = (startBoundRight + dx).coerceAtMost(w - pad)
                        }
                        return@setOnTouchListener true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        val center = ((tabIndicator?.leftBound ?: 0f) + (tabIndicator?.rightBound ?: 0f)) / 2f
                        val toApps = center < halfW
                        tabIndicator?.animateGlow(0f, 200)
                        tabIndicator?.animateExpansion(0f, 250)
                        switchTab(toApps, true)
                        return@setOnTouchListener true
                    } else if (isClick) {
                        
                        val toApps = event.x < halfW
                        switchTab(toApps, true)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        tabContainer?.viewTreeObserver?.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val h = tabIndicator?.height?.toFloat() ?: 0f
                val w = tabContainer.width.toFloat()
                if (h > 0f && w > 0f) {
                    tabContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    tabIndicator!!.isCentered = true
                    tabIndicator.pillHeight = h - (8 * dp)
                    if (settings.isCustomColorEnabled) {
                        tabIndicator.setAccentColors(runtimeAccentColor, runtimeAccentColor)
                    } else {
                        tabIndicator.updateShaders()
                    }
                    
                    
                    val halfW = w / 2f
                    val pad = 4 * dp
                    tabIndicator.leftBound  = if (currentTabIsApps) pad else halfW + pad
                    tabIndicator.rightBound = if (currentTabIsApps) halfW - pad else w - pad
                    tabIndicator.alpha = 1f
                    tabIndicator.invalidate()
                    updateTabVisuals(currentTabIsApps)
                    updateModeUI()
                }
            }
        })
        
        updateModeUI()

        if (allApps.size <= 1) {
            Snackbar.make(binding.root, getString(R.string.error_apps_list_empty), Snackbar.LENGTH_LONG)
                .setAction("Настройки") {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(i)
                }.show()
        }

        var filteredApps = allApps
        val adapter = AppSelectionAdapter(filteredApps) { item ->
                    item.isSelected = !item.isSelected
                    if (item.isSelected) selectedPackages.add(item.packageName)
                    else selectedPackages.remove(item.packageName)
                }

        dialogBinding.root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_apps)?.layoutManager = LinearLayoutManager(this)
        dialogBinding.root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_apps)?.adapter = adapter

        dialogBinding.root.findViewById<android.widget.EditText>(R.id.et_search_apps)?.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        filteredApps = allApps.filter { it.name.contains(s.toString(), ignoreCase = true) }
                        adapter.updateList(filteredApps)
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        dialogBinding.root.findViewById<View>(R.id.btn_cancel_apps)?.setOnClickListener { dialog.dismiss() }
        dialogBinding.root.findViewById<View>(R.id.btn_save_apps)?.setOnClickListener {
            val finalSites = etSites?.text?.toString()?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            
            val hasChanged = settings.splitTunnelingApps != selectedPackages ||
                            settings.splitTunnelingSites != finalSites ||
                            settings.splitTunnelingModeApps != tempModeApps ||
                            settings.splitTunnelingModeSites != tempModeSites

            settings.splitTunnelingApps = selectedPackages
            settings.splitTunnelingSites = finalSites
            settings.splitTunnelingModeApps = tempModeApps
            settings.splitTunnelingModeSites = tempModeSites
            
            updateSplitTunnelingDesc()
            if (hasChanged) showSettingsNotification()
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
            flare.client.app.util.AnimUtils.navigateBack(adv.root, binding.layoutSettings.root)
        }

        adv.swFragmentation.isChecked = settings.isFragmentationEnabled
        adv.layoutFragmentationSub.visibility = if (settings.isFragmentationEnabled) View.VISIBLE else View.GONE
        adv.btnToggleFragmentation.setBackgroundResource(
                if (settings.isFragmentationEnabled) flare.client.app.R.drawable.bg_grouped_top
                else flare.client.app.R.drawable.bg_grouped_all
        )
        val updateFallbackUI = {
            val isFallbackEnabled = settings.packetType != "disabled"
            adv.tvPacketTypeValue.text = if (isFallbackEnabled) getString(R.string.option_enable) else getString(R.string.option_disable)
            adv.root.findViewById<android.view.View>(flare.client.app.R.id.layout_fragment_interval)?.visibility = 
                if (isFallbackEnabled) android.view.View.VISIBLE else android.view.View.GONE
        }
        updateFallbackUI()

        adv.tvStackTypeValue.text = this@MainActivity.getString(R.string.settings_label_stack, settings.tunStack)
        val updateStackDesc = { stack: String ->
            adv.tvStackTypeDesc.text = when (stack) {
                "mixed" -> getString(R.string.mixedstack_desc)
                "gvisor" -> getString(R.string.gvisorstack_desc)
                else -> getString(R.string.systemstack_desc)
            }
        }
        updateStackDesc(settings.tunStack)

        adv.etFragmentInterval.setText(settings.fragmentInterval)
        adv.etMtu.setText(settings.mtu)
        val etRemoteDns = adv.root.findViewById<android.widget.EditText>(flare.client.app.R.id.et_remote_dns_url)
        etRemoteDns?.setText(settings.remoteDnsUrl)

        val swFakeIp = adv.root.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_fake_ip)
        swFakeIp?.isChecked = settings.isFakeIpEnabled

        swFakeIp?.setOnCheckedChangeListener { _, isChecked ->
            if (settings.isFakeIpEnabled != isChecked) {
                settings.isFakeIpEnabled = isChecked
                showSettingsNotification()
            }
        }

        val btnToggleFakeIp = adv.root.findViewById<android.view.View>(flare.client.app.R.id.btn_toggle_fake_ip)
        btnToggleFakeIp?.setOnClickListener {
            swFakeIp?.toggle()
        }

        val swTlsSpoof = adv.root.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_tls_spoof)
        swTlsSpoof?.isChecked = settings.isTlsSpoofEnabled

        swTlsSpoof?.setOnCheckedChangeListener { _, isChecked ->
            if (settings.isTlsSpoofEnabled != isChecked) {
                settings.isTlsSpoofEnabled = isChecked
                showSettingsNotification()
            }
        }

        val btnToggleTlsSpoof = adv.root.findViewById<android.view.View>(flare.client.app.R.id.btn_toggle_tls_spoof)
        btnToggleTlsSpoof?.setOnClickListener {
            swTlsSpoof?.toggle()
        }

        adv.swFragmentation.setOnCheckedChangeListener { _, isChecked ->
            if (settings.isFragmentationEnabled != isChecked) {
                settings.isFragmentationEnabled = isChecked
                showSettingsNotification()
            }
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
            val options = listOf(getString(R.string.option_enable), getString(R.string.option_disable))
            showPopupMenu(view, options) { selected ->
                val newValue = if (selected == getString(R.string.option_enable)) "enabled" else "disabled"
                if (settings.packetType != newValue) {
                    settings.packetType = newValue
                    updateFallbackUI()
                    showSettingsNotification()
                }
            }
        }

        adv.btnStackType.setOnClickListener { view ->
            showPopupMenu(view, listOf("system", "mixed", "gvisor")) { selected ->
                if (settings.tunStack != selected) {
                    adv.tvStackTypeValue.text = this@MainActivity.getString(R.string.settings_label_stack, selected)
                    settings.tunStack = selected
                    updateStackDesc(selected)
                    showSettingsNotification()
                }
            }
        }

        adv.etFragmentInterval.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val newValue = s.toString()
                        if (settings.fragmentInterval != newValue) {
                            settings.fragmentInterval = newValue
                            showSettingsNotification()
                        }
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        adv.etMtu.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val newValue = s.toString()
                        if (settings.mtu != newValue) {
                            settings.mtu = newValue
                            showSettingsNotification()
                        }
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        etRemoteDns?.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val newValue = s.toString().trim()
                        if (settings.remoteDnsUrl != newValue) {
                            settings.remoteDnsUrl = newValue
                            showSettingsNotification()
                        }
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
        )

        adv.swMux.isChecked = settings.isMuxEnabled
        adv.layoutMuxSub.visibility = if (settings.isMuxEnabled) View.VISIBLE else View.GONE
        adv.btnToggleMux.setBackgroundResource(
                if (settings.isMuxEnabled) flare.client.app.R.drawable.bg_grouped_top
                else flare.client.app.R.drawable.bg_grouped_all
        )
        adv.tvMuxProtocolValue.text = settings.muxProtocol
        adv.tvMuxPaddingValue.text = if (settings.muxPadding) getString(R.string.option_yes) else getString(R.string.option_no)

        adv.etMuxMaxStreams.setText(settings.muxMaxStreams)

        adv.swMux.setOnCheckedChangeListener { _, isChecked ->
            if (settings.isMuxEnabled != isChecked) {
                settings.isMuxEnabled = isChecked
                showSettingsNotification()
            }
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
                if (settings.muxProtocol != selected) {
                    adv.tvMuxProtocolValue.text = selected
                    settings.muxProtocol = selected
                    showSettingsNotification()
                }
            }
        }

        adv.etMuxMaxStreams.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val newValue = s.toString()
                        if (settings.muxMaxStreams != newValue) {
                            settings.muxMaxStreams = newValue
                            showSettingsNotification()
                        }
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val v = s?.toString()?.toIntOrNull() ?: return
                        if (v > 128) {
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
            val options = listOf(getString(R.string.option_yes), getString(R.string.option_no))
            showPopupMenu(view, options) { selected ->
                val newValue = (selected == getString(R.string.option_yes))
                if (settings.muxPadding != newValue) {
                    adv.tvMuxPaddingValue.text = selected
                    settings.muxPadding = newValue
                    showSettingsNotification()
                }
            }
        }
    }

    private fun setupPingSettings() {
        val png = binding.layoutSettingsPingContainer

        png.btnPingBack.setOnClickListener {
            flare.client.app.util.AnimUtils.navigateBack(png.root, binding.layoutSettings.root)
        }

        updatePingTypeUI(settings.pingType)
        png.etTestUrl.setText(settings.pingTestUrl)
        png.tvPingStyleValue.text = getPingStyleDisplay(settings.pingStyle)

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
            val options = listOf(
                getString(R.string.settings_ping_style_time),
                getString(R.string.settings_ping_style_icon),
                getString(R.string.settings_ping_style_both)
            )
            val keys = listOf("time", "icon", "both")
            val items = options.mapIndexed { index, text ->
                flare.client.app.util.GlassUtils.MenuItem(index, text) {
                    val key = keys.getOrElse(index) { "time" }
                    png.tvPingStyleValue.text = text
                    settings.pingStyle = key
                }
            }
            flare.client.app.util.GlassUtils.showGlassMenu(view, items)
        }
    }

    private fun updatePingTypeUI(type: String) {
        val png = binding.layoutSettingsPingContainer
        val normalColor = ContextCompat.getColor(this, R.color.text_primary)
        val selectedColor = ContextCompat.getColor(this, R.color.white)

        png.btnPingTypeGet.setBackgroundResource(R.drawable.bg_grouped_all)
        png.btnPingTypeHead.setBackgroundResource(R.drawable.bg_grouped_all)
        png.btnPingTypeTcp.setBackgroundResource(R.drawable.bg_grouped_all)
        png.btnPingTypeIcmp.setBackgroundResource(R.drawable.bg_grouped_all)
        png.ivPingTypeGetCheck.visibility = View.GONE
        png.ivPingTypeHeadCheck.visibility = View.GONE
        png.ivPingTypeTcpCheck.visibility = View.GONE
        png.ivPingTypeIcmpCheck.visibility = View.GONE
        png.tvPingTypeGet.setTextColor(normalColor)
        png.tvPingTypeHead.setTextColor(normalColor)
        png.tvPingTypeTcp.setTextColor(normalColor)
        png.tvPingTypeIcmp.setTextColor(normalColor)

        when (type) {
            "via proxy GET" -> {
                png.btnPingTypeGet.setBackgroundResource(R.drawable.bg_ping_type_selected)
                png.ivPingTypeGetCheck.visibility = View.VISIBLE
                png.tvPingTypeGet.setTextColor(selectedColor)
                png.tvPingTypeDesc.text = getString(R.string.viaproxy_desc)
            }
            "via proxy HEAD" -> {
                png.btnPingTypeHead.setBackgroundResource(R.drawable.bg_ping_type_selected)
                png.ivPingTypeHeadCheck.visibility = View.VISIBLE
                png.tvPingTypeHead.setTextColor(selectedColor)
                png.tvPingTypeDesc.text = getString(R.string.viaproxy_desc)
            }
            "TCP" -> {
                png.btnPingTypeTcp.setBackgroundResource(R.drawable.bg_ping_type_selected)
                png.ivPingTypeTcpCheck.visibility = View.VISIBLE
                png.tvPingTypeTcp.setTextColor(selectedColor)
                png.tvPingTypeDesc.text = getString(R.string.tcp_desc)
            }
            "ICMP" -> {
                png.btnPingTypeIcmp.setBackgroundResource(R.drawable.bg_ping_type_selected)
                png.ivPingTypeIcmpCheck.visibility = View.VISIBLE
                png.tvPingTypeIcmp.setTextColor(selectedColor)
                png.tvPingTypeDesc.text = getString(R.string.icmp_desc)
            }
        }
    }

    private fun setupThemeSettings() {
        val themeView = findViewById<View>(flare.client.app.R.id.layout_settings_theme_container) ?: return

        themeView.findViewById<View>(flare.client.app.R.id.btn_theme_back).setOnClickListener {
            flare.client.app.util.AnimUtils.navigateBack(themeView, binding.layoutSettings.root)
        }

        updateThemeValueUI()

        themeView.findViewById<View>(flare.client.app.R.id.btn_theme_selection).setOnClickListener { view ->
            val options = listOf(
                getString(flare.client.app.R.string.theme_auto),
                getString(flare.client.app.R.string.theme_day),
                getString(flare.client.app.R.string.theme_night)
            )
            showPopupMenu(view, options) { selected ->
                val newMode = when (selected) {
                    getString(flare.client.app.R.string.theme_day) -> 1
                    getString(flare.client.app.R.string.theme_night) -> 2
                    else -> 0
                }
                
                val currentIsNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val targetIsNight = when (newMode) {
                    1 -> false
                    2 -> true
                    else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                
                val isSettingSame = newMode == settings.themeMode
                val isEffectivelySame = (newMode == 0 && isSettingSame) || (newMode != 0 && currentIsNight == targetIsNight)

                if (!isSettingSame) {
                    settings.themeMode = newMode
                    updateThemeValueUI()

                    if (!isEffectivelySame) {
                        settings.pendingNavScreen = "theme"
                        applyThemeWithAnimation(view)
                    } else {
                        applyTheme()
                    }
                }
            }
        }

        val swBgGradient = themeView.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_bg_gradient)
        val swBgAnimation = themeView.findViewById<androidx.appcompat.widget.SwitchCompat>(flare.client.app.R.id.sw_bg_gradient_animation)
        val layoutSpeed = themeView.findViewById<View>(flare.client.app.R.id.layout_gradient_speed_container)
        val dividerSpeed = themeView.findViewById<View>(flare.client.app.R.id.divider_gradient_speed)
        val sliderSpeed = themeView.findViewById<com.google.android.material.slider.Slider>(flare.client.app.R.id.slider_gradient_speed)

        val updateAnimationUI = {
            val animEnabled = swBgAnimation?.isChecked == true
            val gradEnabled = swBgGradient?.isChecked == true
            val showSpeed = animEnabled && gradEnabled
            layoutSpeed?.visibility = if (showSpeed) View.VISIBLE else View.GONE
            dividerSpeed?.visibility = if (showSpeed) View.VISIBLE else View.GONE
            themeView.findViewById<View>(flare.client.app.R.id.btn_toggle_bg_gradient_animation)?.setBackgroundResource(
                if (showSpeed) flare.client.app.R.drawable.bg_grouped_middle else flare.client.app.R.drawable.bg_grouped_bottom
            )
        }

        swBgGradient?.isChecked = settings.isBackgroundGradientEnabled
        swBgGradient?.setOnCheckedChangeListener { _, isChecked ->
            settings.isBackgroundGradientEnabled = isChecked
            updateAnimationUI()
            applyBackgroundGradient()
        }
        themeView.findViewById<View>(flare.client.app.R.id.btn_toggle_bg_gradient)?.setOnClickListener {
            swBgGradient?.toggle()
        }

        swBgAnimation?.isChecked = settings.isGradientAnimationEnabled
        swBgAnimation?.setOnCheckedChangeListener { _, isChecked ->
            settings.isGradientAnimationEnabled = isChecked
            updateAnimationUI()
            if (isChecked) {
                applyBackgroundGradient()
                startGradientAnimation()
            } else {
                stopGradientAnimation()
            }
        }
        themeView.findViewById<View>(flare.client.app.R.id.btn_toggle_bg_gradient_animation)?.setOnClickListener {
            swBgAnimation?.toggle()
        }

        sliderSpeed?.value = settings.gradientAnimationSpeed
        sliderSpeed?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.gradientAnimationSpeed = value
                stopGradientAnimation()
                startGradientAnimation()
            }
        }

        updateAnimationUI()

        
        setupColorPickerSection(themeView)
    }

    private fun setupColorPickerSection(themeView: View) {
        val swCustomColor = themeView.findViewById<androidx.appcompat.widget.SwitchCompat>(
            flare.client.app.R.id.sw_custom_color
        )
        val layoutColorPicker = themeView.findViewById<View>(flare.client.app.R.id.layout_color_picker)
        val dividerColorPicker = themeView.findViewById<View>(flare.client.app.R.id.divider_color_picker)
        val btnToggle = themeView.findViewById<View>(flare.client.app.R.id.btn_toggle_custom_color)

        val btnMaterialYou = themeView.findViewById<View>(flare.client.app.R.id.btn_color_material_you)
        val btnGreen       = themeView.findViewById<View>(flare.client.app.R.id.btn_color_green)
        val btnPurple      = themeView.findViewById<View>(flare.client.app.R.id.btn_color_purple)
        val btnRed         = themeView.findViewById<View>(flare.client.app.R.id.btn_color_red)
        val btnPink        = themeView.findViewById<View>(flare.client.app.R.id.btn_color_pink)
        val btnOrange      = themeView.findViewById<View>(flare.client.app.R.id.btn_color_orange)

        val density = resources.displayMetrics.density

        
        fun refreshColorCircles(selectedKey: String) {
            val buttonsMap = mapOf(
                "material_you" to btnMaterialYou,
                "green"        to btnGreen,
                "purple"       to btnPurple,
                "red"          to btnRed,
                "pink"         to btnPink,
                "orange"       to btnOrange
            )
            buttonsMap.forEach { (key, btn) ->
                val color = getColorsForKey(key).first
                val isSelected = key == selectedKey
                val shape = android.graphics.drawable.GradientDrawable()
                if (isSelected) {
                    shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    shape.cornerRadius = 14 * density
                    
                    shape.setStroke((2.5 * density).toInt(), android.graphics.Color.WHITE)
                } else {
                    shape.shape = android.graphics.drawable.GradientDrawable.OVAL
                }
                shape.setColor(color)
                btn?.background = shape
            }
        }

        
        val enabled = settings.isCustomColorEnabled
        swCustomColor?.isChecked = enabled
        layoutColorPicker?.visibility = if (enabled) View.VISIBLE else View.GONE
        dividerColorPicker?.visibility = if (enabled) View.VISIBLE else View.GONE
        btnToggle?.setBackgroundResource(
            if (enabled) flare.client.app.R.drawable.bg_grouped_middle
            else flare.client.app.R.drawable.bg_grouped_bottom
        )

        
        refreshColorCircles(if (enabled) settings.accentColorKey else "")

        
        btnToggle?.setOnClickListener { swCustomColor?.toggle() }

        swCustomColor?.setOnCheckedChangeListener { _, isChecked ->
            settings.isCustomColorEnabled = isChecked
            android.transition.TransitionManager.beginDelayedTransition(
                themeView as android.view.ViewGroup,
                android.transition.AutoTransition().setDuration(250)
            )
            layoutColorPicker?.visibility = if (isChecked) View.VISIBLE else View.GONE
            dividerColorPicker?.visibility = if (isChecked) View.VISIBLE else View.GONE
            btnToggle?.setBackgroundResource(
                if (isChecked) flare.client.app.R.drawable.bg_grouped_middle
                else flare.client.app.R.drawable.bg_grouped_bottom
            )
            if (isChecked) {
                
                val saved = settings.accentColorKey
                val key = if (saved.isBlank() || saved == "default") "material_you" else saved
                settings.accentColorKey = key          
                refreshColorCircles(key)
                val (accent, accentEnd) = getColorsForKey(key)
                animateAccentChange(accent, accentEnd)
            } else {
                settings.accentColorKey = ""           
                refreshColorCircles("")
                animateAccentChange(COLOR_DEFAULT, COLOR_DEFAULT_END)
                binding.bottomNav.resetAccentColors()
                val baseInclude = findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
                baseInclude?.findViewById<View>(flare.client.app.R.id.btn_change_apps)
                    ?.backgroundTintList = null
            }
        }

        fun onColorSelected(key: String) {
            settings.accentColorKey = key
            refreshColorCircles(key)
            val (accent, accentEnd) = getColorsForKey(key)
            animateAccentChange(accent, accentEnd)
        }

        btnMaterialYou?.setOnClickListener { onColorSelected("material_you") }
        btnGreen?.setOnClickListener       { onColorSelected("green") }
        btnPurple?.setOnClickListener      { onColorSelected("purple") }
        btnRed?.setOnClickListener         { onColorSelected("red") }
        btnPink?.setOnClickListener        { onColorSelected("pink") }
        btnOrange?.setOnClickListener      { onColorSelected("orange") }
    }

    private fun applyThemeWithAnimation(triggerView: View) {
        val location = IntArray(2)
        triggerView.getLocationInWindow(location)
        revealX = location[0] + triggerView.width / 2
        revealY = location[1] + triggerView.height / 2
        themeBitmap = captureScreenshot()
        applyTheme()
        recreate()
    }

    private fun captureScreenshot(): Bitmap? {
        return try {
            val view = window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun checkThemeTransition() {
        val bitmap = themeBitmap ?: return
        themeBitmap = null

        val overlay = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val rootDecor = window.decorView as ViewGroup
        rootDecor.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        overlay.post {
            if (!overlay.isAttachedToWindow) return@post
            val finalRadius = Math.hypot(rootDecor.width.toDouble(), rootDecor.height.toDouble()).toFloat()
            val anim = ViewAnimationUtils.createCircularReveal(overlay, revealX, revealY, finalRadius, 0f)
            anim.duration = 450
            anim.interpolator = DecelerateInterpolator()
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rootDecor.removeView(overlay)
                    bitmap.recycle()
                }
            })
            anim.start()
        }
    }

    private fun applyBackgroundGradient() {
        if (settings.isBackgroundGradientEnabled) {
            binding.blurTarget.setBackgroundResource(flare.client.app.R.drawable.bg_home_gradient)
            binding.rootLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            if (settings.isGradientAnimationEnabled) {
                startGradientAnimation()
            } else {
                stopGradientAnimation()
            }
        } else {
            stopGradientAnimation()
            binding.blurTarget.setBackgroundColor(ContextCompat.getColor(this, flare.client.app.R.color.bg_dark))
            binding.rootLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    private fun startGradientAnimation() {
        if (gradientAnimator != null) return
        if (!settings.isBackgroundGradientEnabled || !settings.isGradientAnimationEnabled) return

        val drawable = binding.blurTarget.background as? LayerDrawable ?: return
        val baseDuration = 4000L
        val speedMultiplier = settings.gradientAnimationSpeed.coerceIn(0.1f, 3.0f)
        val calculatedDuration = (baseDuration / speedMultiplier).toLong()

        gradientAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = calculatedDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val time = progress * 2 * Math.PI.toFloat()
                for (i in 0 until drawable.numberOfLayers) {
                    val layer = drawable.getDrawable(i) as? GradientDrawable ?: continue
                    if (layer.gradientType != GradientDrawable.RADIAL_GRADIENT) continue
                    val phase = i * 1.2f
                    val speedX = 1.0f + (i * 0.15f)
                    val speedY = 0.7f + (i * 0.2f)
                    val offsetX = (sin(time * speedX + phase) * 0.3f).toFloat()
                    val offsetY = (cos(time * speedY + phase) * 0.3f).toFloat()
                    val basePositions = listOf(
                        0.2f to 0.2f,
                        0.8f to 0.8f,
                        0.9f to 0.1f,
                        0.1f to 0.9f,
                        0.5f to 0.4f
                    )
                    if (i - 1 in basePositions.indices) {
                        val (baseX, baseY) = basePositions[i - 1]
                        layer.setGradientCenter(baseX + offsetX, baseY + offsetY)
                    }
                }
            }
            start()
        }
    }

    private fun stopGradientAnimation() {
        gradientAnimator?.cancel()
        gradientAnimator = null
    }

    override fun onResume() {
        super.onResume()
        if (settings.isBackgroundGradientEnabled && settings.isGradientAnimationEnabled) {
            startGradientAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        stopGradientAnimation()
    }

    private fun restorePendingNavScreen() {
        val screen = settings.pendingNavScreen
        if (screen.isEmpty()) return
        settings.pendingNavScreen = ""
        themeChangedJustNow = false

        binding.bottomNav.selectTab(0, false)

        binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)

                binding.layoutHome.visibility = View.GONE
                binding.layoutSettings.root.visibility = View.GONE
                binding.layoutSettingsAdvancedContainer.root.visibility = View.GONE
                binding.layoutSettingsPingContainer.root.visibility = View.GONE
                findViewById<View>(flare.client.app.R.id.layout_settings_base_container)?.visibility = View.GONE
                findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)?.visibility = View.GONE
                findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility = View.GONE
                binding.layoutCustomServersContainer.root.visibility = View.GONE

                when (screen) {
                    "theme" -> {
                        findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)?.visibility = View.VISIBLE
                    }
                    "language" -> {
                        findViewById<View>(flare.client.app.R.id.layout_settings_language_container)?.visibility = View.VISIBLE
                    }
                    "advanced" -> {
                        binding.layoutSettingsAdvancedContainer.root.visibility = View.VISIBLE
                    }
                    "ping" -> {
                        binding.layoutSettingsPingContainer.root.visibility = View.VISIBLE
                    }
                    "base" -> {
                        findViewById<View>(flare.client.app.R.id.layout_settings_base_container)?.visibility = View.VISIBLE
                    }
                    "subscriptions" -> {
                        findViewById<View>(flare.client.app.R.id.layout_settings_subscriptions_container)?.visibility = View.VISIBLE
                    }
                    else -> {
                        binding.layoutSettings.root.visibility = View.VISIBLE
                    }
                }

                val notifMsg = if (screen == "language") {
                    getString(R.string.notif_language_changed_auto)
                } else {
                    getString(R.string.notif_theme_changed)
                }

                flare.client.app.ui.notification.AppNotificationManager.showNotification(
                    flare.client.app.ui.notification.NotificationType.SUCCESS,
                    notifMsg,
                    3
                )
                themeChangedJustNow = false
            }
        })
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

    

    
    private fun getColorsForKey(key: String): Pair<Int, Int> = when (key) {
        "material_you" -> getMaterialYouColors()
        "green"  -> Pair(COLOR_GREEN,  COLOR_GREEN_END)
        "purple" -> Pair(COLOR_PURPLE, COLOR_PURPLE_END)
        "red"    -> Pair(COLOR_RED,    COLOR_RED_END)
        "pink"   -> Pair(COLOR_PINK,   COLOR_PINK_END)
        "orange" -> Pair(COLOR_ORANGE, COLOR_ORANGE_END)
        else     -> Pair(COLOR_DEFAULT, COLOR_DEFAULT_END)
    }

    
    private fun getMaterialYouColors(): Pair<Int, Int> {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            
            val primary   = resources.getColor(android.R.color.system_accent1_500, theme)
            val secondary = resources.getColor(android.R.color.system_accent2_500, theme)
            Pair(primary, secondary)
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            
            try {
                val wm = android.app.WallpaperManager.getInstance(this)
                val wc = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
                val primary   = wc?.primaryColor?.toArgb()   ?: COLOR_DEFAULT
                val secondary = wc?.secondaryColor?.toArgb() ?: COLOR_DEFAULT_END
                Pair(primary, secondary)
            } catch (e: Exception) {
                Pair(COLOR_DEFAULT, COLOR_DEFAULT_END)
            }
        } else {
            
            Pair(COLOR_DEFAULT, COLOR_DEFAULT_END)
        }
    }

    
    private fun animateAccentChange(targetAccent: Int, targetAccentEnd: Int) {
        val fromAccent    = runtimeAccentColor
        val fromAccentEnd = runtimeAccentEndColor
        val evaluator = ArgbEvaluator()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                val blend    = evaluator.evaluate(f, fromAccent,    targetAccent)    as Int
                val blendEnd = evaluator.evaluate(f, fromAccentEnd, targetAccentEnd) as Int
                runtimeAccentColor    = blend
                runtimeAccentEndColor = blendEnd
                applyAccentColorsToUI(blend, blendEnd)
            }
            start()
        }
        runtimeAccentColor    = targetAccent
        runtimeAccentEndColor = targetAccentEnd
    }

    
    private fun applyAccentColorsToUI(accent: Int, accentEnd: Int) {
        val root = window.decorView
        applyAccentToViewTree(root, accent)

        if (::adapter.isInitialized) {
            adapter.accentColor = accent
            adapter.notifyDataSetChanged()
        }

        
        
        if (settings.isCustomColorEnabled) {
            binding.bottomNav.setAccentColors(accent, accentEnd)

            val baseInclude = findViewById<View>(flare.client.app.R.id.layout_settings_base_container)
            val btnChangeApps = baseInclude?.findViewById<View>(flare.client.app.R.id.btn_change_apps)
            btnChangeApps?.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        }
    }

    private fun applyAccentToViewTree(view: View, accent: Int) {
        if (view.tag == "accent_text" && view is android.widget.TextView) {
            view.setTextColor(accent)
        } else if (view.tag == "accent_tint" && view is android.widget.ImageView) {
            view.imageTintList = android.content.res.ColorStateList.valueOf(accent)
        } else if (view.tag == "accent_bg") {
            val bg = view.background?.mutate()
            if (bg is android.graphics.drawable.GradientDrawable) {
                bg.setStroke((1 * view.resources.displayMetrics.density).toInt(), accent)
                bg.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 13))
                view.background = bg
            }
        }

        when (view) {
            is androidx.appcompat.widget.SwitchCompat -> {
                val trackCsl = android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(
                        accent,
                        android.graphics.Color.argb(80, 128, 128, 128)
                    )
                )
                view.trackTintList = trackCsl
            }
            is com.google.android.material.slider.Slider -> {
                val csl = android.content.res.ColorStateList.valueOf(accent)
                view.thumbTintList = csl
                view.trackActiveTintList = csl
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    applyAccentToViewTree(view.getChildAt(i), accent)
                }
            }
        }
    }

    private fun showPopupMenu(view: View, options: List<String>, onSelected: (String) -> Unit) {
        val items = options.mapIndexed { index, text ->
            flare.client.app.util.GlassUtils.MenuItem(index, text) {
                onSelected(text)
            }
        }
        flare.client.app.util.GlassUtils.showGlassMenu(view, items)
    }

    private fun showSettingsNotification() {
        val isThemeRecentlyChanged = System.currentTimeMillis() - lastThemeChangeTime < 2000
        if (isInitializingSettings || themeChangedJustNow || isThemeRecentlyChanged) return
        flare.client.app.ui.notification.AppNotificationManager.showNotification(
                flare.client.app.ui.notification.NotificationType.WARNING,
                getString(flare.client.app.R.string.settings_restart_tunnel_hint),
                3
        )
    }

    private data class NotificationItem(
        val data: flare.client.app.ui.notification.NotificationData,
        val view: View,
        var animator: android.animation.AnimatorSet? = null
    )

    private val activeNotifications = mutableListOf<NotificationItem>()
    private var isNotificationsExpanded = false
    private var collapseJob: kotlinx.coroutines.Job? = null

    private fun showInAppNotification(data: flare.client.app.ui.notification.NotificationData) {
        val container = binding.root.findViewById<ViewGroup>(flare.client.app.R.id.notification_container) ?: return

        
        if (activeNotifications.size >= 3) {
            val oldest = activeNotifications.removeAt(0)
            removeInAppNotification(oldest, true)
        }

        val notifyView = layoutInflater.inflate(flare.client.app.R.layout.layout_notification, container, false)
        val tvText = notifyView.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_notification_text)
        val ivIcon = notifyView.findViewById<android.widget.ImageView>(flare.client.app.R.id.iv_notification_icon)
        val vProgress = notifyView.findViewById<View>(flare.client.app.R.id.v_notification_progress)
        val btnAction = notifyView.findViewById<android.widget.TextView>(flare.client.app.R.id.btn_notification_action)

        tvText.text = data.text
        val iconRes = when (data.type) {
            flare.client.app.ui.notification.NotificationType.SUCCESS -> flare.client.app.R.drawable.ic_success
            flare.client.app.ui.notification.NotificationType.ERROR -> flare.client.app.R.drawable.ic_error
            flare.client.app.ui.notification.NotificationType.WARNING -> flare.client.app.R.drawable.ic_warning
        }
        ivIcon.setImageResource(iconRes)

        if (data.actionText != null) {
            btnAction.visibility = View.VISIBLE
            btnAction.text = data.actionText
            btnAction.backgroundTintList = android.content.res.ColorStateList.valueOf(runtimeAccentColor)
            btnAction.setOnClickListener {
                data.onAction?.invoke()
                val item = activeNotifications.find { it.view == notifyView }
                if (item != null) {
                    activeNotifications.remove(item)
                    removeInAppNotification(item, false)
                }
            }
        } else {
            btnAction.visibility = View.GONE
        }

        notifyView.setOnClickListener {
            if (!isNotificationsExpanded && activeNotifications.size > 1) {
                isNotificationsExpanded = true
                updateNotificationsStack(true)
                resetCollapseTimer()
            }
        }

        val item = NotificationItem(data, notifyView)
        activeNotifications.add(item)
        
        val density = resources.displayMetrics.density
        notifyView.alpha = 0f
        notifyView.translationY = -120f * density
        
        container.addView(notifyView)

        
        val progressAnim = android.animation.ObjectAnimator.ofFloat(vProgress, View.SCALE_X, 1f, 0f)
        progressAnim.duration = data.durationSec * 1000L
        progressAnim.interpolator = android.view.animation.LinearInterpolator()
        
        val animator = android.animation.AnimatorSet()
        animator.play(progressAnim)
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (activeNotifications.contains(item)) {
                    activeNotifications.remove(item)
                    removeInAppNotification(item, false)
                }
            }
        })
        item.animator = animator
        animator.start()

        updateNotificationsStack(isNotificationsExpanded)
        resetCollapseTimer()
    }

    private fun removeInAppNotification(item: NotificationItem, immediate: Boolean) {
        val container = binding.root.findViewById<ViewGroup>(flare.client.app.R.id.notification_container) ?: return
        item.animator?.cancel()
        
        if (immediate) {
            container.removeView(item.view)
            updateNotificationsStack(isNotificationsExpanded)
        } else {
            item.view.animate()
                .alpha(0f)
                .translationY(-100f)
                .setDuration(300)
                .withEndAction {
                    container.removeView(item.view)
                    updateNotificationsStack(isNotificationsExpanded)
                }
                .start()
        }
    }

    private fun updateNotificationsStack(expanded: Boolean) {
        val density = resources.displayMetrics.density
        
        val count = activeNotifications.size
        activeNotifications.forEachIndexed { index, item ->
            val posFromTop = count - 1 - index 
            
            val targetY: Float
            val targetScale: Float
            val targetAlpha: Float
            val targetZ: Float

            if (expanded) {
                targetY = posFromTop * (72 * density) 
                targetScale = 1.0f
                targetAlpha = 1.0f
                targetZ = 10f + (count - posFromTop).toFloat()
            } else {
                
                targetY = posFromTop * (10 * density) 
                targetScale = 1.0f - (posFromTop * 0.05f)
                targetAlpha = 1.0f - (posFromTop * 0.2f)
                targetZ = 10f + (count - posFromTop).toFloat()
            }

            item.view.animate()
                .translationY(targetY)
                .scaleX(targetScale)
                .scaleY(targetScale)
                .alpha(targetAlpha)
                .setDuration(400)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.0f))
                .start()
            
            item.view.z = targetZ
        }
    }

    private fun resetCollapseTimer() {
        collapseJob?.cancel()
        collapseJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(3000)
            if (isNotificationsExpanded) {
                isNotificationsExpanded = false
                updateNotificationsStack(false)
            }
        }
    }

    private fun observeNotifications() {
        lifecycleScope.launch {
            flare.client.app.ui.notification.AppNotificationManager.notifications.collect { data ->
                if (!isFinishing && !isDestroyed) {
                    showInAppNotification(data)
                }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.connectionState.collect { state -> updateConnectButton(state) }
        }

        lifecycleScope.launch {
            viewModel.connectionTimerText.collect { text ->
                binding.tvTimer.setTime(text)
                binding.tvTimer.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.displayItems.collect { items ->
                adapter.submitList(items)
                binding.rvProfiles.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                binding.tvAddHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.tvAddProfiles.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.isAnySubscriptionExpanded.collect { isExpanded ->
                binding.layoutBottomActions.visibility = if (isExpanded) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.importEvent.collect { event ->
                val btnContainer = binding.root.findViewById<android.widget.FrameLayout>(flare.client.app.R.id.btn_clipboard)
                val tvText = binding.root.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_clipboard_text)
                val pbLoading = binding.root.findViewById<android.widget.ProgressBar>(flare.client.app.R.id.pb_clipboard_loading)
                when (event) {
                    is MainViewModel.ImportEvent.Loading -> {
                        btnContainer?.isEnabled = false
                        btnContainer?.alpha = 0.5f
                        tvText?.visibility = View.INVISIBLE
                        pbLoading?.visibility = View.VISIBLE
                    }
                    is MainViewModel.ImportEvent.Success -> {
                        btnContainer?.isEnabled = true
                        btnContainer?.alpha = 1.0f
                        tvText?.visibility = View.VISIBLE
                        pbLoading?.visibility = View.GONE
                        flare.client.app.ui.notification.AppNotificationManager.showNotification(
                            flare.client.app.ui.notification.NotificationType.SUCCESS,
                            event.message,
                            3
                        )
                    }
                    is MainViewModel.ImportEvent.Error -> {
                        btnContainer?.isEnabled = true
                        btnContainer?.alpha = 1.0f
                        tvText?.visibility = View.VISIBLE
                        pbLoading?.visibility = View.GONE
                        flare.client.app.ui.notification.AppNotificationManager.showNotification(
                            flare.client.app.ui.notification.NotificationType.ERROR,
                            event.message,
                            3
                        )
                    }
                    is MainViewModel.ImportEvent.NeedPermission -> {
                        btnContainer?.isEnabled = true
                        btnContainer?.alpha = 1.0f
                        tvText?.visibility = View.VISIBLE
                        pbLoading?.visibility = View.GONE
                        vpnPermLauncher.launch(event.intent)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.editingProfile.collect { profile ->
                if (profile != null) {
                    if (!profile.uri.startsWith("internal://json")) {
                        binding.bottomNav.hide()
                        binding.layoutJsonEditor.visibility = View.GONE
                        val simpleView = findViewById<View>(flare.client.app.R.id.layout_simple_editor)
                        simpleView?.visibility = View.VISIBLE
                        simpleEditor?.bind(profile)
                    } else {
                        binding.bottomNav.hide()
                        findViewById<View>(flare.client.app.R.id.layout_simple_editor)?.visibility = View.GONE
                        binding.layoutJsonEditor.visibility = View.VISIBLE
                        binding.tvJsonTitle.text = profile.name
                        binding.etJsonProfileName.setText(profile.name)
                        if (binding.etJsonContent.text.toString() != profile.configJson) {
                            binding.etJsonContent.setText(profile.configJson)
                        }
                    }
                } else if (viewModel.editingSubscription.value == null) {
                    binding.bottomNav.show()
                    binding.layoutJsonEditor.visibility = View.GONE
                    findViewById<View>(flare.client.app.R.id.layout_simple_editor)?.visibility = View.GONE
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(binding.etJsonContent.windowToken, 0)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.editingSubscription.collect { sub ->
                if (sub != null) {
                    showEditSubscriptionDialog(sub)
                }
            }
        }
    }

    private fun showEditSubscriptionDialog(sub: flare.client.app.data.model.SubscriptionEntity) {
        val dialog = Dialog(this)
        val dialogBinding = flare.client.app.databinding.DialogEditSubscriptionBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val params = window.attributes
            params.width = (resources.displayMetrics.widthPixels * 0.9).toInt()
            window.attributes = params
        }

        if (settings.isCustomColorEnabled) {
            applyAccentToViewTree(dialogBinding.root, runtimeAccentColor)
        }

        dialogBinding.etSubName.setText(sub.name)
        dialogBinding.etSubUrl.setText(sub.url)

        val hasWeb = sub.webPageUrl.isNotBlank()
        val hasSupport = sub.supportUrl.isNotBlank()

        if (hasWeb || hasSupport) {
            dialogBinding.layoutSupport.visibility = View.VISIBLE
            dialogBinding.ivSupportWeb.visibility = if (hasWeb) View.VISIBLE else View.GONE
            dialogBinding.ivSupportTg.visibility = if (hasSupport) View.VISIBLE else View.GONE

            dialogBinding.ivSupportWeb.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sub.webPageUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    showSnackbar("Не удалось открыть ссылку")
                }
            }

            dialogBinding.ivSupportTg.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sub.supportUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    showSnackbar("Не удалось открыть Telegram")
                }
            }
        } else {
            dialogBinding.layoutSupport.visibility = View.GONE
        }

        dialogBinding.btnCancel.setOnClickListener {
            viewModel.setEditingSubscription(null)
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            val newName = dialogBinding.etSubName.text.toString().trim()
            val newUrl = dialogBinding.etSubUrl.text.toString().trim()
            if (newName.isNotEmpty() && newUrl.isNotEmpty()) {
                viewModel.updateSubscription(sub.id, newName, newUrl)
                flare.client.app.ui.notification.AppNotificationManager.showNotification(
                    flare.client.app.ui.notification.NotificationType.SUCCESS,
                    getString(flare.client.app.R.string.json_edit_success, newName),
                    3
                )
                viewModel.setEditingSubscription(null)
                dialog.dismiss()
            }
        }

        dialog.setOnCancelListener {
            viewModel.setEditingSubscription(null)
        }

        dialog.show()
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

    private fun showTestNotification() {
        flare.client.app.ui.notification.AppNotificationManager.showNotification(
            flare.client.app.ui.notification.NotificationType.SUCCESS,
            "Уведомления включены",
            3
        )
    }

    private enum class OnboardingStep {
        WELCOME, PERMISSIONS, FRAGMENTATION, MUX, SUCCESS
    }
    private var currentOnboardingStep = OnboardingStep.WELCOME

    private fun setupOnboarding() {
        val b = binding.layoutOnboarding
        b.root.visibility = View.VISIBLE
        binding.bottomNav.hide(false)

        b.btnWelcomeYes.setOnClickListener { showOnboardingStep(OnboardingStep.PERMISSIONS) }
        b.btnWelcomeNo.setOnClickListener { finishOnboarding() }

        b.btnPermissionNotifications.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                onboardingNotificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        b.btnPermissionBattery.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        b.btnFragYes.setOnClickListener {
            settings.isFragmentationEnabled = true
            showOnboardingStep(OnboardingStep.MUX)
        }
        b.btnFragNo.setOnClickListener {
            settings.isFragmentationEnabled = false
            showOnboardingStep(OnboardingStep.MUX)
        }

        b.btnMuxYes.setOnClickListener {
            settings.isMuxEnabled = true
            showOnboardingStep(OnboardingStep.SUCCESS)
        }
        b.btnMuxNo.setOnClickListener {
            settings.isMuxEnabled = false
            showOnboardingStep(OnboardingStep.SUCCESS)
        }

        b.btnOnboardingFinish.setOnClickListener { finishOnboarding() }
        binding.bottomNav.onArrowClick = {
            if (currentOnboardingStep == OnboardingStep.PERMISSIONS) {
                showOnboardingStep(OnboardingStep.FRAGMENTATION)
            }
        }
    }

    private fun checkOnboardingPermissions() {
        val hasNotif = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        if (hasNotif) {
            binding.bottomNav.show()
            binding.bottomNav.shrinkToArrow()
        }
    }

    private fun checkAndShowInitialPermissionsState() {
        val b = binding.layoutOnboarding
        val hasNotif = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        updateOnboardingPermissionState(b.btnPermissionNotifications, b.ivNotifCheck, hasNotif)

        val isIgnoring = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        updateOnboardingPermissionState(b.btnPermissionBattery, b.ivBatteryCheck, isIgnoring)
    }

    private fun updateOnboardingPermissionState(view: View, checkIcon: View, isGranted: Boolean) {
        if (isGranted) {
            checkIcon.visibility = View.VISIBLE
            view.setBackgroundResource(R.drawable.bg_grouped_all)
            view.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1A30D158"))
        } else {
            checkIcon.visibility = View.GONE
            view.setBackgroundResource(R.drawable.bg_server_card)
            view.backgroundTintList = null
        }
    }

    private fun showOnboardingStep(step: OnboardingStep) {
        val b = binding.layoutOnboarding
        val width = b.root.width.toFloat()
        val currentView = when (currentOnboardingStep) {
            OnboardingStep.WELCOME -> b.layoutStepWelcome
            OnboardingStep.PERMISSIONS -> b.layoutStepPermissions
            OnboardingStep.FRAGMENTATION -> b.layoutStepFragmentation
            OnboardingStep.MUX -> b.layoutStepMux
            OnboardingStep.SUCCESS -> b.layoutStepSuccess
        }

        val nextView = when (step) {
            OnboardingStep.WELCOME -> b.layoutStepWelcome
            OnboardingStep.PERMISSIONS -> b.layoutStepPermissions
            OnboardingStep.FRAGMENTATION -> b.layoutStepFragmentation
            OnboardingStep.MUX -> b.layoutStepMux
            OnboardingStep.SUCCESS -> b.layoutStepSuccess
        }

        if (currentOnboardingStep == step) return

        val isForward = step.ordinal > currentOnboardingStep.ordinal

        if (step == OnboardingStep.PERMISSIONS) {
            checkAndShowInitialPermissionsState()
        }

        currentView.animate()
            .translationX(if (isForward) -width else width)
            .alpha(0f)
            .setDuration(450)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction { currentView.visibility = View.GONE }
            .start()

        nextView.visibility = View.VISIBLE
        nextView.translationX = if (isForward) width else -width
        nextView.alpha = 0f
        nextView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(450)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()

        currentOnboardingStep = step
        if (step == OnboardingStep.PERMISSIONS) {
            checkOnboardingPermissions()
        } else if (step == OnboardingStep.SUCCESS) {
            binding.bottomNav.hide()
        } else if (step != OnboardingStep.WELCOME) {
            binding.bottomNav.show()
            binding.bottomNav.shrinkToArrow()
            binding.bottomNav.onArrowClick = {
                when(currentOnboardingStep) {
                    OnboardingStep.FRAGMENTATION -> showOnboardingStep(OnboardingStep.MUX)
                    OnboardingStep.MUX -> showOnboardingStep(OnboardingStep.SUCCESS)
                    OnboardingStep.PERMISSIONS -> showOnboardingStep(OnboardingStep.FRAGMENTATION)
                    else -> {}
                }
            }
        } else {
            binding.bottomNav.hide()
        }
    }

    private fun handleOnboardingBack() {
        when (currentOnboardingStep) {
            OnboardingStep.PERMISSIONS -> showOnboardingStep(OnboardingStep.WELCOME)
            OnboardingStep.FRAGMENTATION -> showOnboardingStep(OnboardingStep.PERMISSIONS)
            OnboardingStep.MUX -> showOnboardingStep(OnboardingStep.FRAGMENTATION)
            OnboardingStep.WELCOME -> {
                finish()
            }
            else -> {}
        }
    }

    private fun finishOnboarding() {
        settings.isOnboardingCompleted = true
        binding.layoutOnboarding.root.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                binding.layoutOnboarding.root.visibility = View.GONE
                initializeMainUI()
                binding.bottomNav.show()
                binding.bottomNav.expandToTabs()
            }.start()
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val b = binding.layoutOnboarding
            updateOnboardingPermissionState(b.btnPermissionBattery, b.ivBatteryCheck, true)
            return
        }

        val intent = Intent()
        try {
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            batteryPermLauncher.launch(intent)
        } catch (e: Exception) {
            try {
                intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                intent.data = null
                batteryPermLauncher.launch(intent)
            } catch (e2: Exception) {
                try {
                    intent.action = Settings.ACTION_SETTINGS
                    batteryPermLauncher.launch(intent)
                } catch (e3: Exception) {
                    showSnackbar(getString(R.string.error_open_settings))
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (settings.themeMode == 0) {
            val newUiMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (lastUiMode != newUiMode) {
                lastUiMode = newUiMode
                themeChangedJustNow = true
                lastThemeChangeTime = System.currentTimeMillis()
                recreate()
            }
        }
    }

    companion object {
        private var themeBitmap: Bitmap? = null
        private var revealX: Int = 0
        private var revealY: Int = 0
        private var lastUiMode: Int = -1
        private var themeChangedJustNow: Boolean = false
        private var lastThemeChangeTime: Long = 0

        
        const val COLOR_DEFAULT     = 0xFF5B8CFF.toInt()
        const val COLOR_DEFAULT_END = 0xFFA066FF.toInt()
        const val COLOR_GREEN       = 0xFF34C759.toInt()
        const val COLOR_GREEN_END   = 0xFF25A244.toInt()
        const val COLOR_PURPLE      = 0xFF9B59B6.toInt()
        const val COLOR_PURPLE_END  = 0xFFBF8FFF.toInt()
        const val COLOR_RED         = 0xFFFF453A.toInt()
        const val COLOR_RED_END     = 0xFFFF6B6B.toInt()
        const val COLOR_PINK        = 0xFFFF375F.toInt()
        const val COLOR_PINK_END    = 0xFFFF6FA1.toInt()
        const val COLOR_ORANGE      = 0xFFFF9F0A.toInt()
        const val COLOR_ORANGE_END  = 0xFFFFB340.toInt()
    }
    private fun getUpdateFreqDisplay(key: String): String {
        return when (key) {
            "daily", getString(R.string.update_freq_daily), "Daily" -> getString(R.string.update_freq_daily)
            "weekly", getString(R.string.update_freq_weekly), "Weekly" -> getString(R.string.update_freq_weekly)
            "monthly", getString(R.string.update_freq_monthly), "Monthly" -> getString(R.string.update_freq_monthly)
            else -> key
        }
    }

    private fun getPingStyleDisplay(key: String): String {
        return when (key) {
            "time", getString(R.string.settings_ping_style_time), "Time" -> getString(R.string.settings_ping_style_time)
            "icon", getString(R.string.settings_ping_style_icon), "Icon" -> getString(R.string.settings_ping_style_icon)
            "both", getString(R.string.settings_ping_style_both), "Time & Icon" -> getString(R.string.settings_ping_style_both)
            else -> key
        }
    }
    private fun setupJournal() {
        val journalContainer = findViewById<View>(flare.client.app.R.id.layout_journal_container) ?: return
        val baseSettings = findViewById<View>(flare.client.app.R.id.layout_settings_base_container) ?: return
        val tvContent = journalContainer.findViewById<android.widget.TextView>(flare.client.app.R.id.tv_journal_content)
        val btnBack = journalContainer.findViewById<View>(flare.client.app.R.id.btn_journal_back)
        val btnClear = journalContainer.findViewById<View>(flare.client.app.R.id.btn_journal_clear)
        val svJournal = journalContainer.findViewById<androidx.core.widget.NestedScrollView>(flare.client.app.R.id.sv_journal)

        flare.client.app.util.AnimUtils.navigateForward(baseSettings, journalContainer)
        binding.bottomNav.hide()

        
        val MAX_LOG_LINES = 250
        val lineBuffer = ArrayDeque<String>(MAX_LOG_LINES + 1)
        val displayBuilder = android.text.SpannableStringBuilder()
        var fileReadOffset = 0L
        var isWaiting = true

        fun resetJournalState() {
            lineBuffer.clear()
            displayBuilder.clear()
            fileReadOffset = 0L
            isWaiting = true
        }

        btnBack?.setOnClickListener {
            flare.client.app.util.AnimUtils.navigateBack(journalContainer, baseSettings)
            binding.bottomNav.show()
            logJob?.cancel()
        }

        btnClear?.setOnClickListener {
            try {
                val logFile = java.io.File(filesDir, "sing-box.log")
                if (logFile.exists()) {
                    logFile.writeText("")
                }
                resetJournalState()
                tvContent?.text = ""
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        logJob?.cancel()
        logJob = lifecycleScope.launch(Dispatchers.IO) {
            val logFile = java.io.File(filesDir, "sing-box.log")

            while (isActive) {
                if (!logFile.exists()) {
                    delay(1500)
                    continue
                }

                try {
                    val fileLength = logFile.length()

                    
                    if (fileLength < fileReadOffset) {
                        resetJournalState()
                    }

                    
                    if (fileLength > fileReadOffset) {
                        val newLines = mutableListOf<String>()
                        java.io.RandomAccessFile(logFile, "r").use { raf ->
                            raf.seek(fileReadOffset)
                            val reader = java.io.BufferedReader(
                                java.io.InputStreamReader(
                                    java.io.FileInputStream(raf.fd),
                                    Charsets.UTF_8
                                )
                            )
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val raw = line!!
                                if (raw.isNotBlank()) {
                                    newLines.add(
                                        flare.client.app.util.LogDecoder.decode(this@MainActivity, raw)
                                    )
                                }
                            }
                            fileReadOffset = fileLength
                        }

                        if (newLines.isNotEmpty()) {
                            isWaiting = false

                            
                            var needFullRebuild = false
                            for (line in newLines) {
                                if (lineBuffer.size >= MAX_LOG_LINES) {
                                    lineBuffer.removeFirst()
                                    needFullRebuild = true
                                }
                                lineBuffer.addLast(line)
                            }

                            val appendText: CharSequence
                            if (needFullRebuild) {
                                
                                displayBuilder.clear()
                                displayBuilder.append(lineBuffer.joinToString("\n"))
                                appendText = displayBuilder
                            } else {
                                
                                val suffix = buildString {
                                    for (line in newLines) {
                                        if (displayBuilder.isNotEmpty()) append('\n')
                                        append(line)
                                    }
                                }
                                displayBuilder.append(suffix)
                                appendText = displayBuilder
                            }

                            withContext(Dispatchers.Main) {
                                tvContent?.alpha = 1.0f
                                tvContent?.text = appendText
                                svJournal?.post {
                                    svJournal.fullScroll(View.FOCUS_DOWN)
                                }
                            }
                        }
                    }

                    
                    if (isWaiting && lineBuffer.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            tvContent?.text = getString(flare.client.app.R.string.journal_waiting_logs)
                            tvContent?.alpha = 0.5f
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }

                delay(1500)
            }
        }
    }
}
