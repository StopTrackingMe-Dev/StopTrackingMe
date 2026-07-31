# 净链分享助手：项目目标与开发计划

> 本文档用于指导 Codex 在 Android Studio 项目中分阶段开发。  
> 项目暂不复用 GKD、Assists、Auto.js 等第三方自动化框架代码，以避免许可证、耦合和长期维护问题。  
> 核心能力基于 Android 官方 `AccessibilityService`、剪贴板、网络请求和系统分享 API 自行实现。

---

## 1. 项目概述

本项目是一款 Android 链接隐私与分享辅助工具。

它主要解决以下问题：

1. 很多 App 优先调用微信等内部分享能力，不优先展示系统分享面板。
2. 用户若想去除分享链接中的追踪参数，通常需要：
   - 点击“复制链接”；
   - 返回桌面；
   - 打开净链工具；
   - 粘贴；
   - 清理；
   - 再次分享。
3. 上述流程过长，普通用户难以持续使用。
4. 本项目希望通过用户主动触发的无障碍辅助操作，自动点击来源 App 已经公开展示的“复制链接”，然后本地清理链接并重新分享。

目标交互：

```text
用户在来源 App 打开分享面板
→ 点击“净化分享”
→ 本应用通过无障碍服务查找并点击“复制链接”
→ 获取本次新产生的剪贴板 URL
→ 可选展开短链接
→ 删除非必要追踪参数
→ 展示清理结果
→ 用户选择微信、QQ 或系统分享
→ 用户自行选择联系人并发送
```

---

## 2. 项目目标

### 2.1 核心目标

实现一个可运行的 Android App，支持：

- 普通主界面；
- 无障碍服务开启引导；
- 读取当前前台页面的无障碍节点；
- 按包名和规则识别来源 App 的分享面板；
- 在用户明确触发后查找“复制链接”节点；
- 执行一次受控点击；
- 监听本次点击产生的剪贴板变化；
- 提取 HTTP/HTTPS URL；
- 删除明确的追踪参数；
- 可选解析短链接跳转；
- 展示原始地址、最终地址和被删除参数；
- 通过 Android 系统分享面板进行二次分享；
- 在自动化失败时提供手动粘贴入口。

### 2.2 首个 MVP 目标

首个 MVP 只需要完成：

```text
测试目标 App
→ 打开模拟分享面板
→ 用户点击“净化分享”
→ 无障碍服务点击“复制链接”
→ 剪贴板产生 URL
→ 删除 utm_* 等参数
→ 显示清理结果
→ 调用系统分享面板
```

首个 MVP 不要求适配所有真实 App。

### 2.3 非目标

首期明确不实现：

- 自动选择微信联系人；
- 自动点击“发送”；
- 自动发布朋友圈；
- 自动发送消息；
- Hook、注入或修改第三方 App；
- 调用第三方 App 的隐藏接口；
- 逆向私有分享协议；
- 绕过登录、付费、授权或安全验证；
- 后台持续读取剪贴板；
- 后台持续截图或记录屏幕；
- 默认删除联盟佣金、邀请、签名或访问授权参数；
- 完整复刻来源 App 的微信专属卡片；
- iOS 版本；
- 对所有 App 的通用自动适配。

---

## 3. 技术原则

### 3.1 原生实现

优先使用 Android 官方能力：

- `AccessibilityService`
- `AccessibilityEvent`
- `AccessibilityNodeInfo`
- `AccessibilityWindowInfo`
- `ClipboardManager`
- `Intent.ACTION_SEND`
- `android.net.Uri`
- Kotlin Coroutines
- OkHttp 或 Android 官方网络能力
- Jetpack Compose
- Room 或 DataStore（仅在确有持久化需求时）

不直接复制或链接以下项目代码：

- GKD
- Assists
- Auto.js
- URLCheck
- Léon

可以参考其产品思路，但不能复制受许可证约束的实现代码。

### 3.2 用户主动触发

无障碍自动化必须由用户明确触发。

推荐入口：

```text
来源 App 分享页面中的临时无障碍悬浮按钮
```

或：

```text
系统无障碍按钮
```

禁止在用户未触发时自动：

- 点击复制；
- 读取剪贴板；
- 打开微信；
- 执行分享；
- 选择联系人；
- 发送内容。

### 3.3 一次触发、一次动作

一次“净化分享”任务应当是短生命周期状态机：

```text
IDLE
→ USER_TRIGGERED
→ FIND_COPY_ACTION
→ CLICK_COPY_ACTION
→ WAIT_FOR_CLIPBOARD
→ EXTRACT_URL
→ RESOLVE_SHORT_LINK
→ CLEAN_URL
→ SHOW_RESULT
→ SHARE
→ DONE
```

任一步骤失败或超时后应停止，不得无限搜索或连续随机点击。

---

## 4. 推荐项目配置

```text
Template: Phone and Tablet / Empty Activity
Language: Kotlin
UI: Jetpack Compose
Minimum SDK: API 26
Build system: Gradle Kotlin DSL
```

建议配置：

```kotlin
android {
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
    }
}
```

项目应保留普通 `MainActivity`，不要使用纯 `No Activity` 项目。

---

## 5. 建议项目结构

```text
app/
├── src/main/java/.../
│   ├── MainActivity.kt
│   │
│   ├── accessibility/
│   │   ├── CleanShareAccessibilityService.kt
│   │   ├── AccessibilityEventFilter.kt
│   │   ├── AccessibilityNodeSnapshot.kt
│   │   ├── NodeTreeDumper.kt
│   │   ├── CopyActionFinder.kt
│   │   ├── CopyActionExecutor.kt
│   │   ├── AutomationStateMachine.kt
│   │   └── AccessibilityStatusRepository.kt
│   │
│   ├── rules/
│   │   ├── CopyLinkRule.kt
│   │   ├── RuleRepository.kt
│   │   ├── RuleMatcher.kt
│   │   └── BuiltInRules.kt
│   │
│   ├── clipboard/
│   │   ├── ClipboardCapture.kt
│   │   ├── ClipboardChangeObserver.kt
│   │   └── UrlExtractor.kt
│   │
│   ├── links/
│   │   ├── LinkCleaner.kt
│   │   ├── CleanResult.kt
│   │   ├── TrackingParameterPolicy.kt
│   │   ├── ShortLinkResolver.kt
│   │   ├── RedirectSafetyPolicy.kt
│   │   └── UrlRiskClassifier.kt
│   │
│   ├── sharing/
│   │   ├── ShareDispatcher.kt
│   │   └── ShareTarget.kt
│   │
│   ├── ui/
│   │   ├── HomeScreen.kt
│   │   ├── ResultScreen.kt
│   │   ├── SettingsScreen.kt
│   │   ├── DebugScreen.kt
│   │   └── components/
│   │
│   ├── logging/
│   │   ├── AppLogger.kt
│   │   └── RedactedLogFormatter.kt
│   │
│   └── model/
│
├── src/main/res/xml/
│   └── accessibility_service_config.xml
│
├── src/test/
│   └── LinkCleanerTest.kt
│
└── src/androidTest/
    ├── AccessibilityFlowTest.kt
    └── ShareFlowTest.kt

demo-target-app/
├── FakeShareActivity.kt
└── FakeSharePanel.kt
```

---

## 6. 规则模型

第一版不需要复杂 DSL，使用简单、可审查的数据结构。

示例：

```kotlin
data class CopyLinkRule(
    val packageName: String,
    val minVersionCode: Long? = null,
    val maxVersionCode: Long? = null,
    val requiredTexts: Set<String> = emptySet(),
    val excludedTexts: Set<String> = emptySet(),
    val candidateTexts: Set<String> = setOf(
        "复制链接",
        "复制网址",
        "拷贝链接",
        "Copy link"
    ),
    val candidateResourceIds: Set<String> = emptySet(),
    val candidateContentDescriptions: Set<String> = emptySet(),
    val parentClickableDepth: Int = 2,
    val timeoutMs: Long = 1500
)
```

运行时匹配优先级：

```text
1. 包名匹配
2. 页面必要文本匹配
3. 排除页面文本检查
4. resource-id 精确匹配
5. text 精确匹配
6. contentDescription 精确匹配
7. 向上查找可点击父节点
8. 层级与同级节点关系
9. 坐标仅作最终兜底，不作为默认方案
```

候选节点评分参考：

```text
resource-id 完全匹配                    +100
text 完全等于“复制链接”                 +80
contentDescription 完全匹配             +70
节点本身 clickable                     +30
一到两层父节点 clickable                +20
位于已识别分享面板中                    +20
只匹配“复制”                            +5
位于聊天输入、登录、支付、编辑器页面     -100
文本含“发送”“确认”“支付”“授权”         -200
```

---

## 7. 链接清理策略

### 7.1 默认删除

第一版只默认删除高度明确的通用营销追踪参数：

```text
utm_source
utm_medium
utm_campaign
utm_term
utm_content
fbclid
gclid
dclid
mc_cid
mc_eid
```

同时支持通配规则：

```text
utm_*
```

### 7.2 默认保留

以下参数默认保留：

```text
affiliate
affiliate_id
invite
invite_code
referral
creator
creator_id
commission
coupon
token
auth
signature
sign
expires
expiry
key
access_key
session
```

### 7.3 未知参数

未知参数一律默认保留。

### 7.4 清理原则

禁止简单删除 URL 中 `?` 之后的所有内容。

必须保留：

- 协议；
- 域名；
- 端口；
- 路径；
- 非追踪查询参数；
- URL fragment；
- 重复但有效的参数；
- 必要编码。

数据模型：

```kotlin
data class CleanResult(
    val originalUrl: String,
    val resolvedUrl: String,
    val cleanedUrl: String,
    val removedParameters: List<String>,
    val preservedParameters: List<String>,
    val warnings: List<String>,
    val changed: Boolean
)
```

---

## 8. 短链接解析

短链接解析应当独立于参数清理。

处理顺序：

```text
原始 URL
→ 判断是否需要展开
→ ShortLinkResolver
→ 最终 URL
→ LinkCleaner
→ 净化 URL
```

第一版规则：

- 只处理 `http` 和 `https`；
- 最多跟随 5 次重定向；
- 每次记录来源和目标；
- 不携带浏览器 Cookie；
- 不执行 JavaScript；
- 不加载图片、视频和页面资源；
- 禁止访问：
  - localhost；
  - 127.0.0.0/8；
  - 私有 IPv4；
  - 链路本地地址；
  - 内网 IPv6；
  - `file://`；
  - `content://`；
- 设置连接和读取超时；
- 域名变化时提示用户；
- 解析失败时保留原链接。

测试示例：

```text
https://b23.tv/example
→ HTTP 302
→ https://www.bilibili.com/video/BV...?spm_id_from=...
→ 删除明确追踪参数
```

---

## 9. 无障碍服务安全边界

代码层面必须阻止以下操作：

- 点击“发送”；
- 点击“确认”；
- 点击“支付”；
- 点击“登录”；
- 点击“授权”；
- 点击联系人；
- 点击“发布”；
- 进入银行卡、支付、身份验证等敏感 App 自动化；
- 在未匹配明确规则时随机点击；
- 使用固定坐标执行默认点击；
- 在后台持续导出节点；
- 上传完整节点树；
- 上传剪贴板内容；
- 自动发送消息。

允许执行的操作范围：

```text
1. 用户明确触发
2. 在已支持 App 的已识别分享页面
3. 查找公开可见的“复制链接”操作
4. 执行一次点击
5. 等待一次剪贴板变化
6. 获得 URL 后停止节点操作
```

自动化终点：

```text
打开系统分享页面或展示清理结果
```

最终联系人选择和发送必须由用户亲自完成。

---

## 10. 隐私设计

默认原则：

- 链接处理尽量在设备本地完成；
- 不保存剪贴板历史；
- 不保存完整屏幕节点历史；
- 原始链接仅在内存中短期存在；
- 日志中对链接参数值脱敏；
- 短链解析默认可关闭；
- 不上传链接；
- 不上传屏幕截图；
- 不上传目标 App 节点树；
- 调试快照仅在开发构建中启用；
- 发布构建关闭详细节点日志。

建议隐私披露：

```text
快捷模式仅在用户主动点击“净化分享”后读取当前分享界面的无障碍节点，
查找并点击来源 App 已经提供的“复制链接”按钮，并在短时间内读取该次操作
产生的剪贴板 URL。链接默认仅在设备本地处理，不保存、不上传。
应用不会自动选择联系人、发送消息、点击支付或确认操作。
```

---

## 11. 开发阶段与验收标准

## 阶段 0：项目基线

任务：

- 确认项目可编译；
- 初始化 Git；
- 建立基础包结构；
- 添加统一日志工具；
- 建立 Debug 和 Release 行为差异。

验收：

```text
./gradlew assembleDebug
```

成功，无编译错误。

---

## 阶段 1：无障碍服务基础

任务：

- 创建 `CleanShareAccessibilityService`；
- Manifest 正确声明；
- 添加服务配置 XML；
- 首页显示服务状态；
- 首页可以打开系统无障碍设置；
- 服务能接收窗口变化事件；
- 只输出节点信息，不点击。

验收：

- 模拟器能够开启服务；
- Logcat 能看到当前包名；
- 能看到测试 App 节点文本；
- 不执行任何自动点击。

---

## 阶段 2：节点快照与调试工具

任务：

- 遍历 `rootInActiveWindow`；
- 输出节点：
  - text；
  - contentDescription；
  - resource-id；
  - className；
  - clickable；
  - enabled；
  - bounds；
  - 父子层级；
- 提供开发模式节点快照导出；
- 日志脱敏。

验收：

- 能导出测试 App 分享面板节点树；
- 能明确找到“复制链接”节点或其父节点；
- 发布构建不暴露详细节点日志。

---

## 阶段 3：自建测试目标 App

创建 `demo-target-app`，模拟第三方 App。

测试页面包含：

```text
微信
朋友圈
QQ
复制链接
复制文案
更多
发送
确认
```

要求：

- “复制链接”可配置为节点本身可点击；
- 可配置为父节点可点击；
- 点击后写入测试 URL；
- 支持模拟延迟写入剪贴板；
- 支持复制无效文本；
- 支持按钮文本变化。

验收：

- 主 App 可以读取其节点树；
- 不会误点“复制文案”“发送”“确认”。

---

## 阶段 4：独立 LinkCleaner

任务：

- 提取文本中的第一个 HTTP/HTTPS URL；
- 删除明确追踪参数；
- 保留未知参数；
- 保留 fragment；
- 支持重复参数；
- 编写单元测试。

验收：

```text
./gradlew test
```

所有测试通过。

必须覆盖：

- 无参数 URL；
- 只有追踪参数；
- 追踪参数与业务参数混合；
- 重复参数；
- 编码参数；
- fragment；
- 非 URL 文本；
- 多 URL 文本；
- 大小写差异；
- 空参数值。

---

## 阶段 5：受控点击

任务：

- 实现 `CopyActionFinder`；
- 实现规则匹配；
- 仅在用户主动触发后执行；
- 仅点击一次；
- 最多等待 1500ms；
- 节点不可点击时向上查找可点击父节点；
- 禁止危险文本节点；
- 暂不读取剪贴板。

验收：

- 在 `demo-target-app` 中准确点击“复制链接”；
- 不误点“复制文案”；
- 不误点“发送”“确认”；
- 未找到时安全退出；
- 超时后不继续点击。

---

## 阶段 6：剪贴板闭环

任务：

- 用户触发前记录剪贴板摘要；
- 执行点击；
- 监听剪贴板变化；
- 最多等待 2 秒；
- 只接受新产生的 HTTP/HTTPS URL；
- 将 URL 交给 `LinkCleaner`；
- 不保留剪贴板历史。

验收：

```text
点击复制链接
→ 剪贴板变化
→ 成功提取 URL
→ 清理完成
→ UI 展示结果
```

失败场景也必须正确：

- 剪贴板没有变化；
- 复制的是普通文字；
- URL 无效；
- 超时；
- 用户取消。

---

## 阶段 7：短链展开

任务：

- 实现安全重定向解析；
- 限制跳转次数；
- 阻止私网地址；
- 添加超时；
- 不执行 JavaScript；
- 解析失败回退原链接；
- 单元测试和集成测试。

验收：

- 测试服务器 301/302/307/308 均可处理；
- 重定向循环能够中止；
- 私网目标被阻止；
- 超过跳转次数能够中止；
- 最终 URL 再进入清理模块。

---

## 阶段 8：结果页面和系统分享

结果页面展示：

- 原始链接；
- 展开后的链接；
- 净化链接；
- 删除了哪些参数；
- 保留了哪些风险参数；
- 警告信息。

提供按钮：

```text
分享净化链接
复制净化链接
使用原始链接
查看详细变化
```

二次分享使用：

```kotlin
Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, cleanedUrl)
}
```

验收：

- 系统分享面板能正常打开；
- 用户可以选择微信或其他 App；
- 本应用不自动选择联系人和发送。

---

## 阶段 9：真实 App 单点适配

每次只适配一个真实 App。

流程：

```text
1. 在真机安装目标 App
2. 用户手动进入分享面板
3. 导出节点树和截图
4. Codex 分析候选节点
5. 生成规则草案
6. 人工审查
7. 只读测试
8. 执行一次点击测试
9. 验证剪贴板 URL
10. 保存规则
```

每个 App 至少测试：

- 不同内容类型；
- 登录和未登录；
- 深色和浅色模式；
- 字体大小变化；
- 是否安装微信；
- 分享面板第一页和第二页；
- 不同 App 版本；
- 不同厂商设备。

---

## 12. 测试策略

### 12.1 单元测试

运行环境：

```text
本地 JVM
```

覆盖：

- URL 提取；
- 参数删除；
- 风险参数保留；
- 短链安全策略；
- 规则评分；
- 状态机转换。

### 12.2 模拟器测试

使用 Android Studio Device Manager：

```text
Pixel 系列
Android 版本至少覆盖 API 26、当前主流版本和最新版本
优先使用带 Google Play 的系统镜像
```

模拟器验证：

- 无障碍服务开启；
- 节点读取；
- 测试目标 App；
- 自动点击；
- 剪贴板；
- URL 清理；
- 系统分享；
- 不同屏幕尺寸；
- 不同 Android 版本。

### 12.3 真机测试

模拟器通过后必须使用真机。

建议至少覆盖：

```text
接近原生 Android 的设备
小米/Redmi
OPPO、vivo、荣耀或其他主流国产设备
```

真机重点验证：

- 厂商无障碍设置差异；
- 后台限制；
- 分享面板差异；
- 剪贴板提示；
- 悬浮按钮；
- 真实微信分享入口；
- 真实目标 App 节点质量；
- 设备重启后的服务状态。

### 12.4 UI Automator

`UI Automator` 只用于测试和节点分析。

正式 App 运行时使用：

```text
AccessibilityService
```

测试时可以使用：

```bash
adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml ./snapshots/window.xml
adb exec-out screencap -p > ./snapshots/screen.png
```

---

## 13. Codex 工作方式

Codex 应在 Android Studio 项目根目录运行。

推荐流程：

```text
Android Studio
→ View
→ Tool Windows
→ Terminal
→ codex
```

Codex 每次只执行一个阶段，不得一次实现整个项目。

每个任务遵守：

1. 先检查现有代码；
2. 输出实施计划；
3. 只修改当前阶段相关文件；
4. 不大规模重构无关代码；
5. 修改后运行构建或测试；
6. 修复本阶段产生的错误；
7. 输出变更摘要；
8. 列出未完成事项；
9. 不擅自加入第三方 GPL 代码；
10. 不擅自加入自动发送、联系人选择等高风险功能。

---

## 14. Codex 首条任务

将以下内容作为第一条开发任务：

```text
请检查当前 Android 项目，并完成“阶段 0：项目基线”。

要求：
1. 不实现无障碍点击。
2. 不引入 GKD、Assists、Auto.js 或其他自动化框架代码。
3. 检查 Kotlin、Compose、minSdk、targetSdk、compileSdk 和 AGP 配置。
4. 确认项目能够构建。
5. 初始化适合本项目的包结构。
6. 创建 README 中需要的基础说明。
7. 创建统一日志接口，但暂不记录敏感数据。
8. 运行 assembleDebug。
9. 输出修改文件列表、构建结果和下一阶段建议。
```

---

## 15. Codex 第二条任务

阶段 0 完成后执行：

```text
请完成“阶段 1：无障碍服务基础”。

要求：
1. 保留现有 MainActivity。
2. 创建 CleanShareAccessibilityService。
3. 在 AndroidManifest.xml 中正确声明服务。
4. 添加 accessibility_service_config.xml。
5. MainActivity 显示无障碍服务是否开启。
6. 增加“打开无障碍设置”按钮。
7. 服务接收窗口状态和内容变化事件。
8. 当前阶段只将包名、事件类型和基础节点信息输出到 Logcat。
9. 禁止执行任何点击。
10. 禁止读取剪贴板。
11. 构建项目并修复编译错误。
12. 输出手动验证步骤。
```

---

## 16. 首个里程碑

首个里程碑不是“适配所有 App”，而是：

```text
在 Android Studio 模拟器中，
安装主 App 和 demo-target-app，
开启无障碍服务，
用户点击“净化分享”后，
主 App 准确点击 demo-target-app 的“复制链接”，
获得剪贴板 URL，
删除 utm_* 参数，
展示结果，
并成功打开系统分享面板。
```

只有完成这一里程碑后，才开始真实 App 适配。

---

## 17. 完成定义

MVP 完成必须同时满足：

- 无障碍服务可正常开启和关闭；
- 用户主动触发后才执行；
- 在测试目标 App 中稳定识别复制链接；
- 一次任务最多点击一次；
- 不误点危险按钮；
- 成功获得剪贴板 URL；
- URL 清理结果可解释；
- 未知和高风险参数默认保留；
- 短链解析可关闭；
- 系统分享面板正常；
- 自动化失败有手动粘贴兜底；
- 无后台持续剪贴板读取；
- 无自动联系人选择和发送；
- 无第三方 GPL 代码依赖；
- 单元测试和核心模拟器流程通过；
- 至少一个真实 App 在至少一台真机上验证成功。

---

## 18. 后续可选功能

MVP 完成后再考虑：

- 远程规则更新；
- 用户自定义参数策略；
- 支持多个 URL；
- 批量清理；
- 浏览器分享入口；
- 规则调试 UI；
- App 版本兼容范围；
- 失败快照本地导出；
- 更多真实 App 适配；
- 微信开放平台网页卡片；
- 商店普通版与增强版拆分；
- 企业 SDK；
- 社区规则仓库。

所有新增功能仍必须遵守：

```text
用户主动
行为透明
本地优先
最小权限
不自动发送
不破坏交易和权益参数
```
