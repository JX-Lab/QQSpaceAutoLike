# QQSpaceAutoLike

一个按 GitHub 仓库方式组织的 Android 项目，用来在手机 QQ 打开时，通过 `AccessibilityService` 自动进入空间动态并执行一轮补赞。

这个仓库是根据你给的分享链接里的思路落下来的原生安卓版本，不走 `Auto.js` 脚本路线，而是用 `Kotlin + AccessibilityService` 做成可长期维护的工程。

## 当前状态

- 已完成标准 Android 仓库骨架
- 已完成设置页、配置持久化、通知栏停止按钮
- 已完成无障碍服务主链路
- 已完成基础导航、点赞扫描、广告过滤、随机等待、老动态停止逻辑
- 已补齐 GitHub Actions 自动构建 APK 与 Release 发版流程
- 当前环境还没有本地实际编译 APK

原因：

- 当前工作环境没有安装 `java`
- 当前工作环境没有安装 `Android SDK`
- 当前工作环境也没有 `gh` CLI 和 GitHub 登录态
- 因此我在 **2026-07-10** 只能完成代码、仓库结构与 GitHub 自动打包流程，不能在本机做 `assembleDebug`

## 功能设计

- 只在手机 QQ 进入前台时自动执行一轮
- 不需要 Root
- 不修改 QQ
- 不调用私有 QQ 接口
- 支持运行时长配置：`5 / 10 / 15 / 30 分钟 / 不限时`
- 支持跳过广告、推广、小世界等内容
- 支持随机等待与随机滑动
- 支持连续多次找不到新赞后自动结束
- 支持发现超过 3 天的动态后停止
- 支持通知栏手动停止

## 目录结构

```text
QQSpaceAutoLike/
├── .github/workflows/android.yml
├── .github/workflows/release.yml
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/io/github/yanganqi/qqspaceautolike/
│       │   ├── automation/
│       │   ├── config/
│       │   ├── service/
│       │   └── ui/
│       └── res/
├── gradle/
├── gradlew
├── gradlew.bat
└── README.md
```

## 自动化主流程

```text
打开 QQ
  ↓
无障碍服务收到 QQ 前台事件
  ↓
尝试进入 “动态”
  ↓
尝试进入 “好友动态 / 空间动态”
  ↓
扫描当前屏幕上的点赞按钮
  ↓
跳过广告 / 已点赞 / 重复节点
  ↓
随机等待
  ↓
下滑继续扫描
  ↓
达到时长 / 遇到旧动态 / 连续无新赞
  ↓
停止
```

## 关键实现点

- 不写死坐标定位入口，而是优先通过控件文本和可点击父节点定位
- 下滑优先尝试 `ACTION_SCROLL_FORWARD`，失败后再走手势滑动
- 点赞按钮用“文本/描述 + 可点击性 + 屏幕右半区 + 尺寸限制”做启发式过滤
- 广告过滤通过按钮周边控件树文本判断，而不是只看单个节点
- 老动态判断支持 `昨天 / 前天 / N天前 / 7月10日 / 2026-07-10` 这类格式

## 使用方式

1. 用 Android Studio 打开仓库根目录 `QQSpaceAutoLike`
2. 让 Gradle 同步依赖
3. 配置本机 `Android SDK` 与 `JDK 17`
4. 运行到安卓设备
5. 在应用内打开“无障碍设置”
6. 给通知权限
7. 打开手机 QQ，观察是否能进入动态并开始补赞

## 手机下载安装

这个项目现在按 Android `app` 处理，不是浏览器插件。

- 日常测试包：推送到 `main` 后，GitHub Actions 会自动构建 `app-debug.apk`
- 直接下载包：给仓库打 `v*` 标签后，GitHub Release 会自动挂出可下载安装的测试 APK
- 安装前需要在手机上允许“安装未知应用”

推荐发布测试包的命令：

```bash
git tag v0.1.0
git push origin main --tags
```

完成后可以在仓库的 `Releases` 页面直接下载 APK 到手机测试。

## 需要你自己验证的点

- 不同 QQ 版本里“动态 / 好友动态 / 空间动态”的具体文案可能不同
- 不同 ROM 对无障碍事件的节流策略不同
- 有些点赞按钮可能没有直接暴露文本，需要继续补 OCR 或 viewId 规则
- 广告关键词和误判规则后续还需要真机微调

## 建议的下一步

1. 真机抓一次 QQ 目标页面的无障碍节点树
2. 根据你自己手机上的 QQ 版本补充入口文案
3. 对点赞按钮、广告卡片、时间文案做针对性调试
4. 再考虑加入黑名单、特别关心、仅最近 24 小时动态等策略

## 推到 GitHub

目标仓库建议直接用：

```text
https://github.com/JX-Lab/QQSpaceAutoLike
```

当前环境没有 `gh` CLI，也没有可用的 GitHub 登录态，所以我不能直接替你在 GitHub 上创建仓库；但本地仓库已经整理成可直接推送的状态。你在 GitHub 先新建一个空仓库 `JX-Lab/QQSpaceAutoLike` 之后，只需要：

```bash
cd /mnt/disk1/yanganqi/QQSpaceAutoLike
git config user.name "JX-Lab"
git config user.email "JX-Lab@users.noreply.github.com"
git add .
git commit -m "Initial Android app scaffold for QQSpaceAutoLike"
git branch -M main
git remote add origin https://github.com/JX-Lab/QQSpaceAutoLike.git
git push -u origin main
```

如果你更想用 SSH，也可以把远端换成：

```bash
git remote add origin git@github.com:JX-Lab/QQSpaceAutoLike.git
```

首推完成后：

1. `Actions` 页面会自动开始构建测试 APK
2. 打 tag 后，`Releases` 页面会出现可直接下载到手机的安装包
