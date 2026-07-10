package io.github.yanganqi.qqspaceautolike.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
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
    private lateinit var switchAutoRun: SwitchCompat
    private lateinit var switchSkipAds: SwitchCompat
    private lateinit var switchRandomDelay: SwitchCompat
    private lateinit var switchSinglePass: SwitchCompat
    private lateinit var switchStopOnOld: SwitchCompat

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
        switchAutoRun = findViewById(R.id.switchAutoRun)
        switchSkipAds = findViewById(R.id.switchSkipAds)
        switchRandomDelay = findViewById(R.id.switchRandomDelay)
        switchSinglePass = findViewById(R.id.switchSinglePass)
        switchStopOnOld = findViewById(R.id.switchStopOnOld)
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

        durationGroup.setOnCheckedChangeListener { _, _ ->
            if (!bindingUi) {
                persistCurrentUiState()
            }
        }

        listOf(
            switchAutoRun,
            switchSkipAds,
            switchRandomDelay,
            switchSinglePass,
            switchStopOnOld,
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
        switchSinglePass.isChecked = config.singlePassPerOpen
        switchStopOnOld.isChecked = config.stopOnOlderPosts
        durationGroup.check(
            when (config.runDuration) {
                RunDuration.MINUTES_5 -> R.id.duration5
                RunDuration.MINUTES_10 -> R.id.duration10
                RunDuration.MINUTES_15 -> R.id.duration15
                RunDuration.MINUTES_30 -> R.id.duration30
                RunDuration.UNLIMITED -> R.id.durationUnlimited
            },
        )
        bindingUi = false
    }

    private fun persistCurrentUiState() {
        configStore.save(
            AppConfig(
                autoRunOnQqOpen = switchAutoRun.isChecked,
                skipAds = switchSkipAds.isChecked,
                randomDelay = switchRandomDelay.isChecked,
                singlePassPerOpen = switchSinglePass.isChecked,
                stopOnOlderPosts = switchStopOnOld.isChecked,
                runDuration = selectedDuration(),
            ),
        )
    }

    private fun selectedDuration(): RunDuration {
        return when (durationGroup.checkedRadioButtonId) {
            R.id.duration5 -> RunDuration.MINUTES_5
            R.id.duration15 -> RunDuration.MINUTES_15
            R.id.duration30 -> RunDuration.MINUTES_30
            R.id.durationUnlimited -> RunDuration.UNLIMITED
            else -> RunDuration.MINUTES_10
        }
    }

    private fun refreshStatus() {
        val serviceEnabled = QqAutoLikeService.isServiceEnabled(this)
        val qqInstalled = isQqInstalled()
        val config = configStore.load()
        val runtimeStatus = runtimeStatusStore.load()

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
                runtimeStatus.isRunning || QqAutoLikeService.isAutomationRunning() -> R.string.status_runtime_running
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
