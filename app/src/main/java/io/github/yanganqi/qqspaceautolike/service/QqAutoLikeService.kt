package io.github.yanganqi.qqspaceautolike.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
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
import kotlinx.coroutines.launch

class QqAutoLikeService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var configStore: ConfigStore
    private lateinit var notificationFactory: ServiceNotificationFactory
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile
    private var currentConfig: AppConfig = AppConfig()

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var lastObservedPackage: String? = null

    private var qqSessionConsumed = false
    private var automationJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        configStore = ConfigStore(this)
        notificationFactory = ServiceNotificationFactory(this)
        currentConfig = configStore.load()
        configListener = configStore.registerListener { config ->
            currentConfig = config
        }
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
        requestStop("service destroyed")
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    fun currentObservedPackage(): String? = lastObservedPackage

    private fun handlePackageTransition(packageName: String) {
        if (lastObservedPackage == QQ_PACKAGE_NAME && packageName != QQ_PACKAGE_NAME) {
            qqSessionConsumed = false
        }
        lastObservedPackage = packageName
    }

    private fun startAutomation(trigger: Trigger): Boolean {
        if (automationJob?.isActive == true) return false

        stopRequested = false
        if (trigger == Trigger.MANUAL && currentObservedPackage() != QQ_PACKAGE_NAME) {
            launchQq(this)
        }

        val configSnapshot = currentConfig
        automationJob = serviceScope.launch {
            notificationFactory.showRunning(getString(io.github.yanganqi.qqspaceautolike.R.string.notification_running_text))
            try {
                val summary = AutomationOrchestrator(
                    service = this@QqAutoLikeService,
                    config = configSnapshot,
                    stopRequested = ::isStopRequested,
                    onStatus = ::updateStatus,
                ).run()
                updateStatus("本轮结束：${summary.reason}；点赞 ${summary.likesPerformed} 条")
                delay(900)
            } catch (cancelled: CancellationException) {
                updateStatus("任务已停止")
                throw cancelled
            } catch (error: Throwable) {
                updateStatus("执行失败：${error.message ?: "未知错误"}")
                delay(1_200)
            } finally {
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

    private fun updateStatus(text: String) {
        notificationFactory.showRunning(text)
    }

    private fun requestStop(reason: String): Boolean {
        val job = automationJob ?: return false
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
