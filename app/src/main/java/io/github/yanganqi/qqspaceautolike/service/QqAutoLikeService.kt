package io.github.yanganqi.qqspaceautolike.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import io.github.yanganqi.qqspaceautolike.R
import io.github.yanganqi.qqspaceautolike.automation.AutomationOrchestrator
import io.github.yanganqi.qqspaceautolike.config.AppConfig
import io.github.yanganqi.qqspaceautolike.config.ConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class QqAutoLikeService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var configStore: ConfigStore
    private lateinit var notificationFactory: ServiceNotificationFactory
    private lateinit var runtimeStatusStore: RuntimeStatusStore
    private lateinit var stopOverlayController: StopOverlayController
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile
    private var currentConfig: AppConfig = AppConfig()

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var lastObservedPackage: String? = null

    private var qqSessionConsumed = false
    private var automationJob: Job? = null
    private var feedPollingJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        configStore = ConfigStore(this)
        notificationFactory = ServiceNotificationFactory(this)
        runtimeStatusStore = RuntimeStatusStore(this)
        stopOverlayController = StopOverlayController(this) {
            requestStop("overlay stop button")
        }
        currentConfig = configStore.load()
        configListener = configStore.registerListener { config ->
            currentConfig = config
        }
        val runtimeStatus = runtimeStatusStore.load()
        if (runtimeStatus.isRunning) {
            runtimeStatusStore.setFinished(runtimeStatus.message ?: "服务已重连")
        }
        startBackgroundPollingLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        handlePackageTransition(packageName)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            packageName == QQ_PACKAGE_NAME &&
            currentConfig.autoRunOnQqOpen &&
            !qqSessionConsumed &&
            automationJob == null
        ) {
            qqSessionConsumed = true
            startAutomation(Trigger.AUTO_ON_QQ_OPEN)
        }
    }

    override fun onInterrupt() {
        requestStop("service interrupted")
    }

    override fun onDestroy() {
        if (::configStore.isInitialized) {
            configListener?.let(configStore::unregisterListener)
        }
        configListener = null
        feedPollingJob?.cancel()
        feedPollingJob = null
        if (::stopOverlayController.isInitialized) {
            stopOverlayController.hide()
        }
        requestStop("service destroyed")
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    fun currentObservedPackage(): String? = lastObservedPackage

    private fun handlePackageTransition(packageName: String) {
        if (lastObservedPackage == QQ_PACKAGE_NAME &&
            packageName != QQ_PACKAGE_NAME &&
            packageName != this.packageName
        ) {
            qqSessionConsumed = false
        }
        lastObservedPackage = packageName
    }

    private fun startBackgroundPollingLoop() {
        if (feedPollingJob?.isActive == true) return
        feedPollingJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                val configSnapshot = currentConfig
                if (configSnapshot.autoRunOnQqOpen && automationJob == null) {
                    val statusText = runCatching {
                        AutomationOrchestrator(
                            service = this@QqAutoLikeService,
                            config = configSnapshot,
                            stopRequested = { false },
                            onStatus = {},
                        ).pollPendingQueue()
                    }.getOrElse { error ->
                        "后台侦测失败：${error.message ?: "未知错误"}"
                    }
                    if (automationJob == null) {
                        runtimeStatusStore.setFinished(statusText)
                    }
                }

                val delayMs = configSnapshot.pollIntervalMinutes
                    .coerceIn(AppConfig.MIN_POLL_INTERVAL_MINUTES, AppConfig.MAX_POLL_INTERVAL_MINUTES) *
                    60_000L
                delay(delayMs)
            }
        }
    }

    private fun startAutomation(trigger: Trigger): Boolean {
        if (automationJob?.isActive == true) return false

        stopRequested = false
        if (trigger == Trigger.MANUAL && currentObservedPackage() != QQ_PACKAGE_NAME) {
            updateRunningStatus(getString(R.string.status_launching_qq))
            if (!launchQq(this)) {
                finishStatus("无法打开手机 QQ")
                return false
            }
        }

        val configSnapshot = currentConfig
        automationJob = serviceScope.launch {
            updateRunningStatus(getString(R.string.notification_running_text))
            stopOverlayController.show()
            try {
                val summary = AutomationOrchestrator(
                    service = this@QqAutoLikeService,
                    config = configSnapshot,
                    stopRequested = ::isStopRequested,
                    onStatus = ::updateRunningStatus,
                ).run()
                finishStatus("本轮结束：${summary.reason}；补赞 ${summary.likesPerformed} 条")
                delay(900)
            } catch (cancelled: CancellationException) {
                finishStatus("任务已停止")
                throw cancelled
            } catch (error: Throwable) {
                finishStatus("执行失败：${error.message ?: "未知错误"}")
                delay(1_200)
            } finally {
                stopOverlayController.hide()
                notificationFactory.cancel()
                stopRequested = false
                automationJob = null
            }
        }
        return true
    }

    private fun isStopRequested(): Boolean {
        return stopRequested
    }

    private fun updateRunningStatus(text: String) {
        notificationFactory.showRunning(text)
        runtimeStatusStore.setRunning(text)
    }

    private fun finishStatus(text: String) {
        notificationFactory.showRunning(text)
        runtimeStatusStore.setFinished(text)
    }

    private fun requestStop(reason: String): Boolean {
        val job = automationJob ?: return false
        if (reason == "external request" || reason == "overlay stop button") {
            qqSessionConsumed = true
        }
        stopRequested = true
        job.cancel(CancellationException(reason))
        return true
    }

    private enum class Trigger {
        AUTO_ON_QQ_OPEN,
        MANUAL,
    }

    companion object {
        const val QQ_PACKAGE_NAME = "com.tencent.mobileqq"

        @Volatile
        private var instance: QqAutoLikeService? = null

        fun isServiceEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    info.resolveInfo.serviceInfo.packageName == context.packageName &&
                        info.resolveInfo.serviceInfo.name == QqAutoLikeService::class.java.name
                }
        }

        fun isAutomationRunning(): Boolean {
            return instance?.automationJob?.isActive == true
        }

        fun requestManualRun(): Boolean {
            val service = instance ?: return false
            service.qqSessionConsumed = true
            return service.startAutomation(Trigger.MANUAL)
        }

        fun requestStop(): Boolean {
            return instance?.requestStop("external request") == true
        }

        fun launchQq(context: Context): Boolean {
            val intent = context.packageManager.getLaunchIntentForPackage(QQ_PACKAGE_NAME) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}
