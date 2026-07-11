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
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import io.github.yanganqi.qqspaceautolike.R
import io.github.yanganqi.qqspaceautolike.config.AppConfig
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
    private lateinit var durationGroup: RadioGroup
    private lateinit var inputCustomDuration: EditText
    private lateinit var inputMyQq: EditText
    private lateinit var inputQzoneCookie: EditText
    private lateinit var inputPollInterval: EditText
    private lateinit var inputMinLikeAge: EditText
    private lateinit var inputMaxLikesPerSession: EditText
    private lateinit var btnStopRun: Button
    private lateinit var switchAutoRun: SwitchCompat
    private lateinit var switchSkipAds: SwitchCompat
    private lateinit var switchRandomDelay: SwitchCompat

    private var runtimeStatusListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var bindingUi = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast(R.string.toast_notifications_missing)
            }
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
        durationGroup = findViewById(R.id.groupDuration)
        inputCustomDuration = findViewById(R.id.inputCustomDuration)
        inputMyQq = findViewById(R.id.inputMyQq)
        inputQzoneCookie = findViewById(R.id.inputQzoneCookie)
        inputPollInterval = findViewById(R.id.inputPollInterval)
        inputMinLikeAge = findViewById(R.id.inputMinLikeAge)
        inputMaxLikesPerSession = findViewById(R.id.inputMaxLikesPerSession)
        btnStopRun = findViewById(R.id.btnStopRun)
        switchAutoRun = findViewById(R.id.switchAutoRun)
        switchSkipAds = findViewById(R.id.switchSkipAds)
        switchRandomDelay = findViewById(R.id.switchRandomDelay)
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

        durationGroup.setOnCheckedChangeListener { _, _ ->
            if (!bindingUi) {
                syncCustomDurationVisibility()
                persistCurrentUiState()
            }
        }

        inputCustomDuration.doAfterTextChanged {
            if (!bindingUi && selectedDuration() == RunDuration.CUSTOM) {
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
            switchAutoRun,
            switchSkipAds,
            switchRandomDelay,
        ).forEach { switchView ->
            switchView.setOnCheckedChangeListener { _, _ ->
                if (!bindingUi) {
                    persistCurrentUiState()
                    refreshStatus()
                }
            }
        }
    }

    private fun loadConfigIntoUi() {
        val config = configStore.load()
        bindingUi = true
        switchAutoRun.isChecked = config.autoRunOnQqOpen
        switchSkipAds.isChecked = config.skipAds
        switchRandomDelay.isChecked = config.randomDelay
        inputCustomDuration.setText(config.customRunMinutes.toString())
        inputMyQq.setText(config.myQq)
        inputQzoneCookie.setText(config.qzoneCookie)
        inputPollInterval.setText(config.pollIntervalMinutes.toString())
        inputMinLikeAge.setText(config.minLikeAgeMinutes.toString())
        inputMaxLikesPerSession.setText(config.maxLikesPerSession.toString())
        durationGroup.check(
            when (config.runDuration) {
                RunDuration.MINUTES_5 -> R.id.duration5
                RunDuration.MINUTES_10 -> R.id.duration10
                RunDuration.CUSTOM -> R.id.durationCustom
            },
        )
        syncCustomDurationVisibility()
        bindingUi = false
    }

    private fun persistCurrentUiState() {
        configStore.save(
            AppConfig(
                autoRunOnQqOpen = switchAutoRun.isChecked,
                skipAds = switchSkipAds.isChecked,
                randomDelay = switchRandomDelay.isChecked,
                myQq = inputMyQq.text?.toString().orEmpty(),
                qzoneCookie = inputQzoneCookie.text?.toString().orEmpty(),
                pollIntervalMinutes = selectedPollIntervalMinutes(),
                minLikeAgeMinutes = selectedMinLikeAgeMinutes(),
                maxLikesPerSession = selectedMaxLikesPerSession(),
                runDuration = selectedDuration(),
                customRunMinutes = selectedCustomRunMinutes(),
            ),
        )
    }

    private fun selectedDuration(): RunDuration {
        return when (durationGroup.checkedRadioButtonId) {
            R.id.duration5 -> RunDuration.MINUTES_5
            R.id.durationCustom -> RunDuration.CUSTOM
            else -> RunDuration.MINUTES_10
        }
    }

    private fun selectedCustomRunMinutes(): Int {
        return inputCustomDuration.text?.toString()
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
        inputCustomDuration.isVisible = selectedDuration() == RunDuration.CUSTOM
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
                config.autoRunOnQqOpen && serviceEnabled -> R.string.status_runtime_waiting
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
