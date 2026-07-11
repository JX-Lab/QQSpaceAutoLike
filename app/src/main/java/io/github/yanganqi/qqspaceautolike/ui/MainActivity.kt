package io.github.yanganqi.qqspaceautolike.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.tabs.TabLayout
import io.github.yanganqi.qqspaceautolike.R
import io.github.yanganqi.qqspaceautolike.config.AppConfig
import io.github.yanganqi.qqspaceautolike.config.AutomationMode
import io.github.yanganqi.qqspaceautolike.config.ConfigStore
import io.github.yanganqi.qqspaceautolike.config.RunDuration
import io.github.yanganqi.qqspaceautolike.service.QqAutoLikeService
import io.github.yanganqi.qqspaceautolike.service.RuntimeStatusStore

class MainActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var runtimeStatusStore: RuntimeStatusStore

    private lateinit var textServiceStatus: TextView
    private lateinit var textQqStatus: TextView
    private lateinit var textRuntimeStatus: TextView
    private lateinit var textRuntimeDetail: TextView
    private lateinit var tabMode: TabLayout
    private lateinit var pageLegacy: LinearLayout
    private lateinit var pageQzone: LinearLayout

    private lateinit var durationGroupLegacy: RadioGroup
    private lateinit var inputCustomDurationLegacy: EditText
    private lateinit var switchAutoRunLegacy: SwitchCompat
    private lateinit var switchSkipAdsLegacy: SwitchCompat
    private lateinit var switchRandomDelayLegacy: SwitchCompat
    private lateinit var switchSinglePassLegacy: SwitchCompat
    private lateinit var switchStopOnOldLegacy: SwitchCompat

    private lateinit var durationGroupQzone: RadioGroup
    private lateinit var inputCustomDurationQzone: EditText
    private lateinit var inputMyQq: EditText
    private lateinit var inputQzoneCookie: EditText
    private lateinit var inputPollInterval: EditText
    private lateinit var inputMinLikeAge: EditText
    private lateinit var inputMaxLikesPerSession: EditText
    private lateinit var switchAutoRunQzone: SwitchCompat
    private lateinit var switchSkipAdsQzone: SwitchCompat
    private lateinit var switchRandomDelayQzone: SwitchCompat

    private lateinit var btnStopRun: Button

    private var runtimeStatusListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var bindingUi = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast(R.string.toast_notifications_missing)
            }
        }

    private val cookieCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val cookie = data.getStringExtra(CookieCaptureActivity.EXTRA_COOKIE).orEmpty()
            val myQq = data.getStringExtra(CookieCaptureActivity.EXTRA_MY_QQ).orEmpty()
            bindingUi = true
            if (myQq.isNotBlank()) {
                inputMyQq.setText(myQq)
            }
            inputQzoneCookie.setText(cookie)
            bindingUi = false
            persistCurrentUiState()
            refreshStatus()
            Toast.makeText(this, R.string.cookie_capture_success, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configStore = ConfigStore(this)
        runtimeStatusStore = RuntimeStatusStore(this)
        bindViews()
        bindActions()
        loadConfigIntoUi()
    }

    override fun onStart() {
        super.onStart()
        runtimeStatusListener = runtimeStatusStore.registerListener {
            runOnUiThread { refreshStatus() }
        }
    }

    override fun onResume() {
        super.onResume()
        loadConfigIntoUi()
        refreshStatus()
    }

    override fun onStop() {
        runtimeStatusListener?.let(runtimeStatusStore::unregisterListener)
        runtimeStatusListener = null
        super.onStop()
    }

    private fun bindViews() {
        textServiceStatus = findViewById(R.id.textServiceStatus)
        textQqStatus = findViewById(R.id.textQqStatus)
        textRuntimeStatus = findViewById(R.id.textRuntimeStatus)
        textRuntimeDetail = findViewById(R.id.textRuntimeDetail)
        tabMode = findViewById(R.id.tabMode)
        pageLegacy = findViewById(R.id.pageLegacy)
        pageQzone = findViewById(R.id.pageQzone)

        durationGroupLegacy = findViewById(R.id.groupDurationLegacy)
        inputCustomDurationLegacy = findViewById(R.id.inputCustomDurationLegacy)
        switchAutoRunLegacy = findViewById(R.id.switchAutoRunLegacy)
        switchSkipAdsLegacy = findViewById(R.id.switchSkipAdsLegacy)
        switchRandomDelayLegacy = findViewById(R.id.switchRandomDelayLegacy)
        switchSinglePassLegacy = findViewById(R.id.switchSinglePassLegacy)
        switchStopOnOldLegacy = findViewById(R.id.switchStopOnOldLegacy)

        durationGroupQzone = findViewById(R.id.groupDurationQzone)
        inputCustomDurationQzone = findViewById(R.id.inputCustomDurationQzone)
        inputMyQq = findViewById(R.id.inputMyQq)
        inputQzoneCookie = findViewById(R.id.inputQzoneCookie)
        inputPollInterval = findViewById(R.id.inputPollInterval)
        inputMinLikeAge = findViewById(R.id.inputMinLikeAge)
        inputMaxLikesPerSession = findViewById(R.id.inputMaxLikesPerSession)
        switchAutoRunQzone = findViewById(R.id.switchAutoRunQzone)
        switchSkipAdsQzone = findViewById(R.id.switchSkipAdsQzone)
        switchRandomDelayQzone = findViewById(R.id.switchRandomDelayQzone)

        btnStopRun = findViewById(R.id.btnStopRun)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOpenQq).setOnClickListener {
            if (!QqAutoLikeService.launchQq(this)) {
                toast(R.string.toast_qq_open_failed)
            }
        }

        findViewById<Button>(R.id.btnCaptureCookie).setOnClickListener {
            cookieCaptureLauncher.launch(Intent(this, CookieCaptureActivity::class.java))
        }

        findViewById<Button>(R.id.btnRunNow).setOnClickListener {
            ensureNotificationPermissionIfNeeded()

            if (!QqAutoLikeService.isServiceEnabled(this)) {
                toast(R.string.toast_accessibility_missing)
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }

            if (!isQqInstalled()) {
                toast(R.string.toast_qq_missing)
                return@setOnClickListener
            }

            if (QqAutoLikeService.requestManualRun()) {
                toast(R.string.toast_run_started)
            } else {
                toast(R.string.toast_run_failed)
            }
            refreshStatus()
        }

        btnStopRun.setOnClickListener {
            if (QqAutoLikeService.requestStop()) {
                toast(R.string.toast_run_stopped)
            } else {
                toast(R.string.toast_no_running_task)
            }
            refreshStatus()
        }

        tabMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (bindingUi) return
                loadConfigIntoUi(modeFromTabPosition(tab.position))
                persistCurrentUiState()
                refreshStatus()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        durationGroupLegacy.setOnCheckedChangeListener { _, _ ->
            if (!bindingUi) {
                syncCustomDurationVisibility()
                persistCurrentUiState()
            }
        }
        durationGroupQzone.setOnCheckedChangeListener { _, _ ->
            if (!bindingUi) {
                syncCustomDurationVisibility()
                persistCurrentUiState()
            }
        }

        inputCustomDurationLegacy.doAfterTextChanged {
            if (!bindingUi && selectedLegacyDuration() == RunDuration.CUSTOM) {
                persistCurrentUiState()
            }
        }
        inputCustomDurationQzone.doAfterTextChanged {
            if (!bindingUi && selectedQzoneDuration() == RunDuration.CUSTOM) {
                persistCurrentUiState()
            }
        }

        listOf(
            inputMyQq,
            inputQzoneCookie,
            inputPollInterval,
            inputMinLikeAge,
            inputMaxLikesPerSession,
        ).forEach { input ->
            input.doAfterTextChanged {
                if (!bindingUi) {
                    persistCurrentUiState()
                }
            }
        }

        listOf(
            switchAutoRunLegacy,
            switchSkipAdsLegacy,
            switchRandomDelayLegacy,
            switchSinglePassLegacy,
            switchStopOnOldLegacy,
            switchAutoRunQzone,
            switchSkipAdsQzone,
            switchRandomDelayQzone,
        ).forEach { switchView ->
            switchView.setOnCheckedChangeListener { _, _ ->
                if (!bindingUi) {
                    persistCurrentUiState()
                    refreshStatus()
                }
            }
        }
    }

    private fun loadConfigIntoUi(forceMode: AutomationMode? = null) {
        val config = configStore.load()
        val mode = forceMode ?: config.mode
        bindingUi = true

        selectTab(mode)
        showModePage(mode)

        bindLegacyConfig(config)
        bindQzoneConfig(config)
        syncCustomDurationVisibility()

        bindingUi = false
    }

    private fun bindLegacyConfig(config: AppConfig) {
        switchAutoRunLegacy.isChecked = config.autoRunOnQqOpen
        switchSkipAdsLegacy.isChecked = config.skipAds
        switchRandomDelayLegacy.isChecked = config.randomDelay
        switchSinglePassLegacy.isChecked = config.singlePassPerOpen
        switchStopOnOldLegacy.isChecked = config.stopOnOlderPosts
        inputCustomDurationLegacy.setText(config.customRunMinutes.toString())
        durationGroupLegacy.check(
            when (config.runDuration) {
                RunDuration.MINUTES_5 -> R.id.durationLegacy5
                RunDuration.MINUTES_10 -> R.id.durationLegacy10
                RunDuration.CUSTOM -> R.id.durationLegacyCustom
            },
        )
    }

    private fun bindQzoneConfig(config: AppConfig) {
        switchAutoRunQzone.isChecked = config.autoRunOnQqOpen
        switchSkipAdsQzone.isChecked = config.skipAds
        switchRandomDelayQzone.isChecked = config.randomDelay
        inputCustomDurationQzone.setText(config.customRunMinutes.toString())
        durationGroupQzone.check(
            when (config.runDuration) {
                RunDuration.MINUTES_5 -> R.id.durationQzone5
                RunDuration.MINUTES_10 -> R.id.durationQzone10
                RunDuration.CUSTOM -> R.id.durationQzoneCustom
            },
        )
        inputMyQq.setText(config.myQq)
        inputQzoneCookie.setText(config.qzoneCookie)
        inputPollInterval.setText(config.pollIntervalMinutes.toString())
        inputMinLikeAge.setText(config.minLikeAgeMinutes.toString())
        inputMaxLikesPerSession.setText(config.maxLikesPerSession.toString())
    }

    private fun persistCurrentUiState() {
        val selectedMode = selectedMode()
        configStore.save(
            AppConfig(
                mode = selectedMode,
                autoRunOnQqOpen = when (selectedMode) {
                    AutomationMode.LEGACY_UI -> switchAutoRunLegacy.isChecked
                    AutomationMode.QZONE_QUEUE -> switchAutoRunQzone.isChecked
                },
                skipAds = when (selectedMode) {
                    AutomationMode.LEGACY_UI -> switchSkipAdsLegacy.isChecked
                    AutomationMode.QZONE_QUEUE -> switchSkipAdsQzone.isChecked
                },
                randomDelay = when (selectedMode) {
                    AutomationMode.LEGACY_UI -> switchRandomDelayLegacy.isChecked
                    AutomationMode.QZONE_QUEUE -> switchRandomDelayQzone.isChecked
                },
                singlePassPerOpen = switchSinglePassLegacy.isChecked,
                stopOnOlderPosts = switchStopOnOldLegacy.isChecked,
                runDuration = when (selectedMode) {
                    AutomationMode.LEGACY_UI -> selectedLegacyDuration()
                    AutomationMode.QZONE_QUEUE -> selectedQzoneDuration()
                },
                customRunMinutes = when (selectedMode) {
                    AutomationMode.LEGACY_UI -> selectedLegacyCustomRunMinutes()
                    AutomationMode.QZONE_QUEUE -> selectedQzoneCustomRunMinutes()
                },
                myQq = inputMyQq.text?.toString().orEmpty(),
                qzoneCookie = inputQzoneCookie.text?.toString().orEmpty(),
                pollIntervalMinutes = selectedPollIntervalMinutes(),
                minLikeAgeMinutes = selectedMinLikeAgeMinutes(),
                maxLikesPerSession = selectedMaxLikesPerSession(),
            ),
        )
    }

    private fun selectedLegacyDuration(): RunDuration {
        return when (durationGroupLegacy.checkedRadioButtonId) {
            R.id.durationLegacy5 -> RunDuration.MINUTES_5
            R.id.durationLegacyCustom -> RunDuration.CUSTOM
            else -> RunDuration.MINUTES_10
        }
    }

    private fun selectedQzoneDuration(): RunDuration {
        return when (durationGroupQzone.checkedRadioButtonId) {
            R.id.durationQzone5 -> RunDuration.MINUTES_5
            R.id.durationQzoneCustom -> RunDuration.CUSTOM
            else -> RunDuration.MINUTES_10
        }
    }

    private fun selectedLegacyCustomRunMinutes(): Int {
        return inputCustomDurationLegacy.text?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(AppConfig.MIN_CUSTOM_RUN_MINUTES, AppConfig.MAX_CUSTOM_RUN_MINUTES)
            ?: AppConfig.DEFAULT_CUSTOM_RUN_MINUTES
    }

    private fun selectedQzoneCustomRunMinutes(): Int {
        return inputCustomDurationQzone.text?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(AppConfig.MIN_CUSTOM_RUN_MINUTES, AppConfig.MAX_CUSTOM_RUN_MINUTES)
            ?: AppConfig.DEFAULT_CUSTOM_RUN_MINUTES
    }

    private fun selectedPollIntervalMinutes(): Int {
        return inputPollInterval.text?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(AppConfig.MIN_POLL_INTERVAL_MINUTES, AppConfig.MAX_POLL_INTERVAL_MINUTES)
            ?: AppConfig.DEFAULT_POLL_INTERVAL_MINUTES
    }

    private fun selectedMinLikeAgeMinutes(): Int {
        return inputMinLikeAge.text?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(AppConfig.MIN_MIN_LIKE_AGE_MINUTES, AppConfig.MAX_MIN_LIKE_AGE_MINUTES)
            ?: AppConfig.DEFAULT_MIN_LIKE_AGE_MINUTES
    }

    private fun selectedMaxLikesPerSession(): Int {
        return inputMaxLikesPerSession.text?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(AppConfig.MIN_MAX_LIKES_PER_SESSION, AppConfig.MAX_MAX_LIKES_PER_SESSION)
            ?: AppConfig.DEFAULT_MAX_LIKES_PER_SESSION
    }

    private fun syncCustomDurationVisibility() {
        inputCustomDurationLegacy.isVisible = selectedLegacyDuration() == RunDuration.CUSTOM
        inputCustomDurationQzone.isVisible = selectedQzoneDuration() == RunDuration.CUSTOM
    }

    private fun refreshStatus() {
        val serviceEnabled = QqAutoLikeService.isServiceEnabled(this)
        val qqInstalled = isQqInstalled()
        val config = configStore.load()
        val runtimeStatus = runtimeStatusStore.load()
        val isRunning = runtimeStatus.isRunning || QqAutoLikeService.isAutomationRunning()

        textServiceStatus.setText(
            if (serviceEnabled) {
                R.string.status_service_enabled
            } else {
                R.string.status_service_disabled
            },
        )

        textQqStatus.setText(
            if (qqInstalled) {
                R.string.status_qq_installed
            } else {
                R.string.status_qq_missing
            },
        )

        textRuntimeStatus.setText(
            when {
                isRunning -> R.string.status_runtime_running
                config.autoRunOnQqOpen && serviceEnabled && config.mode == AutomationMode.QZONE_QUEUE ->
                    R.string.status_runtime_waiting_qzone
                config.autoRunOnQqOpen && serviceEnabled ->
                    R.string.status_runtime_waiting_legacy
                else -> R.string.status_runtime_idle
            },
        )

        val detailText = runtimeStatus.message?.let { message ->
            if (runtimeStatus.isRunning || QqAutoLikeService.isAutomationRunning()) {
                getString(R.string.status_runtime_progress, message)
            } else {
                getString(R.string.status_runtime_last_result, message)
            }
        }.orEmpty()
        textRuntimeDetail.text = detailText
        textRuntimeDetail.isVisible = detailText.isNotBlank()
        btnStopRun.isEnabled = isRunning
    }

    private fun ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun selectedMode(): AutomationMode {
        return modeFromTabPosition(tabMode.selectedTabPosition)
    }

    private fun modeFromTabPosition(position: Int): AutomationMode {
        return if (position == 0) AutomationMode.LEGACY_UI else AutomationMode.QZONE_QUEUE
    }

    private fun selectTab(mode: AutomationMode) {
        val target = if (mode == AutomationMode.LEGACY_UI) 0 else 1
        if (tabMode.selectedTabPosition != target) {
            tabMode.getTabAt(target)?.select()
        }
    }

    private fun showModePage(mode: AutomationMode) {
        pageLegacy.isVisible = mode == AutomationMode.LEGACY_UI
        pageQzone.isVisible = mode == AutomationMode.QZONE_QUEUE
    }

    private fun isQqInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(QqAutoLikeService.QQ_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }
}
