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
import androidx.core.content.res.ResourcesCompat
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eightbitlab.com.blurview.BlurTarget

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settings: SettingsManager

    private lateinit var adapter: ProfileAdapter
    private var selectedServerId: Int? = null

    private var isInitializingSettings = false
    private var currentTabIndex = 1

    private var gradientAnimator: ValueAnimator? = null

    private val vpnPermLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    viewModel.connectOrDisconnect()
                } else {
                    showSnackbar("Разрешение VPN отклонено")
                }
            }

    private val notificationPermLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    showTestNotification()
                } else {
                    showSnackbar("Разрешение на уведомления отклонено")
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
                    showSnackbar("Разрешение на уведомления отклонено")
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsManager(this)
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
                flare.client.app.R.id.layout_custom_servers_container,
                flare.client.app.R.id.toolbar_json
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
            val notificationView = findViewById<View>(flare.client.app.R.id.notification_include)
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
        setupBottomNav()
        setupSettings()
        setupJsonEditor()
        setupServers()
        observeViewModel()
        observeNotifications()
        restorePendingNavScreen()
        if (themeChangedJustNow && settings.pendingNavScreen.isEmpty()) {
            themeChangedJustNow = false
            flare.client.app.ui.notification.AppNotificationManager.showNotification(
                flare.client.app.ui.notification.NotificationType.SUCCESS,
                "Тема приложения была изменена автоматически!",
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
                        onSubscriptionUpdate = { sub -> viewModel.refreshSubscription(sub) }
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
                    showSnackbar("Название не может быть пустым")
                }
            }
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
                        .setDuration(450)
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
                        .setDuration(450)
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
                    findViewById<View>(flare.client.app.R.id.layout_settings_theme_container)
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
            val config = setupManager.setupXray(host, sshPort, user, pass, vpnPort, sni)
            if (config != null) {
                finalizeServerCreation(profileName, config)
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

    private fun finalizeServerCreation(profileName: String, config: String) {
        viewModel.addPrivateServer(profileName, config)
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
        val flareContentColor = if (flareSelected) ContextCompat.getColor(this, R.color.white)
                                else ContextCompat.getColor(this, R.color.text_primary)
        serversBinding.ivFlareIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (flareSelected) ContextCompat.getColor(this, R.color.white)
            else ContextCompat.getColor(this, R.color.accent)
        )
        serversBinding.tvFlareTitle.setTextColor(flareContentColor)
        serversBinding.tvFlareDesc.setTextColor(if (flareSelected) ContextCompat.getColor(this, R.color.white) else ContextCompat.getColor(this, R.color.text_secondary))
        serversBinding.tvFlareDesc.alpha = if (flareSelected) 0.8f else 1.0f

        serversBinding.btnCreateServer.setBackgroundResource(
            if (createSelected) R.drawable.bg_server_card_selected else R.drawable.bg_server_card
        )
        val createContentColor = if (createSelected) ContextCompat.getColor(this, R.color.white)
                                 else ContextCompat.getColor(this, R.color.text_primary)
        serversBinding.ivCreateIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (createSelected) ContextCompat.getColor(this, R.color.white)
            else ContextCompat.getColor(this, R.color.accent)
        )
        serversBinding.tvCreateTitle.setTextColor(createContentColor)
        serversBinding.tvCreateDesc.setTextColor(if (createSelected) ContextCompat.getColor(this, R.color.white) else ContextCompat.getColor(this, R.color.text_secondary))
        serversBinding.tvCreateDesc.alpha = if (createSelected) 0.8f else 1.0f
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

        isInitializingSettings = true
        setupAdvancedSettings()
        setupPingSettings()
        setupBaseSettings()
        setupSubscriptionsSettings()
        setupThemeSettings()
        isInitializingSettings = false
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

        updateSplitTunnelingDesc()

        baseInclude?.findViewById<View>(flare.client.app.R.id.btn_change_apps)?.setOnClickListener {
            showAppSelectionDialog()
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
                            "Минимальный интервал — 10 секунд",
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
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val allApps = packageManager.queryIntentActivities(intent, 0)
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

        if (allApps.size <= 1) {
            Snackbar.make(binding.root, "Список пуст. Проверьте разрешение на список приложений в настройках.", Snackbar.LENGTH_LONG)
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
                updateStackDesc(selected)
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

        adv.swMux.isChecked = settings.isMuxEnabled
        adv.layoutMuxSub.visibility = if (settings.isMuxEnabled) View.VISIBLE else View.GONE
        adv.btnToggleMux.setBackgroundResource(
                if (settings.isMuxEnabled) flare.client.app.R.drawable.bg_grouped_top
                else flare.client.app.R.drawable.bg_grouped_all
        )
        adv.tvMuxProtocolValue.text = settings.muxProtocol
        adv.tvMuxPaddingValue.text = if (settings.muxPadding) "Да" else "Нет"

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
            themeView.visibility = View.GONE
            binding.layoutSettings.root.visibility = View.VISIBLE
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
                if (newMode != settings.themeMode) {
                    settings.themeMode = newMode
                    settings.pendingNavScreen = "theme"
                    applyThemeWithAnimation(view)
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

                flare.client.app.ui.notification.AppNotificationManager.showNotification(
                    flare.client.app.ui.notification.NotificationType.SUCCESS,
                    "Тема приложения была изменена!",
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
                    binding.bottomNav.hide()
                    binding.layoutJsonEditor.visibility = View.VISIBLE
                    binding.tvJsonTitle.text = profile.name
                    binding.etJsonProfileName.setText(profile.name)
                    if (binding.etJsonContent.text.toString() != profile.configJson) {
                        binding.etJsonContent.setText(profile.configJson)
                    }
                } else if (viewModel.editingSubscription.value == null) {
                    binding.bottomNav.show()
                    binding.layoutJsonEditor.visibility = View.GONE
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
                    showSnackbar("Не удалось открыть настройки системы")
                }
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
    }
}
