package io.github.yanganqi.qqspaceautolike.automation

import io.github.yanganqi.qqspaceautolike.config.AppConfig
import io.github.yanganqi.qqspaceautolike.service.QqAutoLikeService

class LegacyUiAutomationOrchestrator(
    private val service: QqAutoLikeService,
    private val config: AppConfig,
    private val stopRequested: () -> Boolean,
    private val onStatus: (String) -> Unit,
) {

    private val randomDelay = RandomDelay(config.randomDelay)
    private val gestureHelper = GestureHelper(service, randomDelay)
    private val classifier = CardClassifier()
    private val navigator = UiNavigator(service, gestureHelper, randomDelay)
    private val scanner = FeedScanner(
        service = service,
        gestureHelper = gestureHelper,
        randomDelay = randomDelay,
        classifier = classifier,
        config = config,
        stopRequested = stopRequested,
    )

    suspend fun run(): ScanSummary {
        onStatus("等待手机 QQ 前台")
        if (!waitForQqForeground()) {
            return ScanSummary(0, 0, "未能进入手机 QQ 前台")
        }

        randomDelay.pause(450, 900)
        onStatus("正在进入 QQ 空间动态")
        if (!navigator.openQqSpaceFeed(
                rootProvider = { service.rootInActiveWindow },
                onStatus = onStatus,
            )
        ) {
            return ScanSummary(0, 0, "无法定位好友动态入口")
        }

        randomDelay.afterNavigation()
        onStatus("开始扫描点赞按钮")
        return scanner.scan(
            rootProvider = { service.rootInActiveWindow },
            onStatus = onStatus,
        )
    }

    private suspend fun waitForQqForeground(): Boolean {
        repeat(40) {
            if (stopRequested()) return false
            val packageName = service.rootInActiveWindow?.packageName?.toString()
                ?: service.currentObservedPackage()
            if (packageName == QqAutoLikeService.QQ_PACKAGE_NAME) {
                return true
            }
            randomDelay.shortWait()
        }
        return false
    }
}
