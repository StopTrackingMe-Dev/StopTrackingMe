# 规则 JSON 编写说明（schemaVersion 1）

本文说明当前应用实际支持的规则 JSON 格式。字段、默认值和限制以
[RuleParser.kt](app/src/main/java/app/stoptrackingme/rules/RuleParser.kt)、
[RuleModels.kt](app/src/main/java/app/stoptrackingme/rules/RuleModels.kt) 及其运行逻辑为准。

规则的大致执行顺序如下：

~~~text
识别目标应用和分享入口
  → 定位分享面板
  → 查找并点击“复制链接”
  → 从剪贴板提取第一个 URL
  → 按需展开短链
  → 恢复访问失败页中的原始 URL
  → 校验最终域名
  → 删除追踪参数
  → 按需获取分享预览
~~~

## 1. 可直接修改的最小模板

下面是一个不含网页预览等高级功能、但结构完整的模板。JSON 不支持注释，实际文件中不要加入注释或尾随逗号。

~~~json
{
  "schemaVersion": 1,
  "rules": [
    {
      "id": "local.example-app.share-clean",
      "version": 1,
      "displayName": "示例应用",
      "source": {
        "kind": "LOCAL",
        "reference": "example-app.json"
      },
      "target": {
        "packageName": "com.example.app",
        "minVersionCode": null,
        "maxVersionCode": null
      },
      "shareTriggerSelectors": [
        {
          "descriptionRegex": "^(分享|Share)$",
          "clickable": true
        }
      ],
      "sharePanelFingerprint": [
        {
          "textRegex": "^(分享至|分享到|Share to)$"
        },
        {
          "textRegex": "^(复制链接|复制連結|Copy Link)$"
        }
      ],
      "copyLinkSelectors": [
        {
          "textRegex": "^(复制链接|复制連結|Copy Link)$",
          "descriptionRegex": "^(复制链接|复制連結|Copy Link)$"
        }
      ],
      "maxClickableParentDepth": 5,
      "sharePanelTimeoutMs": 6000,
      "copySettleDelayMs": 800,
      "clipboardExtraction": {
        "urlRegex": "https?://[^\\s<>\"'，。！？；;（）()《》【】]+",
        "maxInputLength": 16384
      },
      "redirectPolicy": {
        "shortLinkHosts": [],
        "allowedFinalHosts": [
          "example.com"
        ],
        "maxRedirects": 5,
        "requireHttps": true,
        "connectTimeoutMs": 5000,
        "readTimeoutMs": 5000
      },
      "cleaningPolicy": {
        "removeExact": [
          "share_id",
          "from",
          "gclid",
          "fbclid"
        ],
        "removePrefixes": [
          "utm_"
        ],
        "forceKeep": []
      }
    }
  ]
}
~~~

## 2. 通用语法和校验规则

- 文件必须是非空的 UTF-8，最大为 512 KiB。
- 使用严格 JSON：不接受注释、尾随逗号、重复字段、根对象后的额外内容或错误类型。
- 所有配置对象只接受本文列出的字段。拼错字段名不会被忽略，而会导致整份规则加载失败。
- 整数字段必须写成十进制整数，例如 `5000`；`5000.0`、`5e3` 和字符串 `"5000"` 都无效。
- 枚举值解析时不区分大小写，但建议统一使用本文展示的大写形式。
- “可选”通常表示应省略字段。不要随意填 `null`；只有明确标注可空的字段才接受 `null`。
- 一个 bundle 内的 `rules[].id` 不能重复。
- 文件中每个对象最多 128 个成员、每个数组最多 512 项、嵌套深度最多 32 层。各字段还有更严格的专用限制。
- 规则不能定义坐标点击、脚本、Intent、重复点击或任意动作。当前自动化最多执行一次可选滚动和一次复制点击。

### 2.1 正则表达式

所有规则正则都必须能由 RE2/J 编译，长度为 1～256 个字符。RE2 保证线性匹配，因此不支持回溯引用和环视，例如 `\1`、`(?=...)`、`(?<=...)` 均不可用。

JSON 会先处理一次反斜杠，所以正则中的 `\s`、`\.` 应分别写成 `\\s`、`\\.`。例如：

~~~json
{
  "urlRegex": "^https://www\\.example\\.com/item/[0-9]+$"
}
~~~

不同字段的实际匹配方式略有区别：

- 控件选择器、剪贴板 URL 和访问失败页正则使用不区分大小写的“查找匹配”。如需匹配整个字符串，请加 `^` 和 `$`。
- 预览请求的 `urlRegex`、`fallbackRequests[].urlRegex` 和 `bootstrap.tokenRegex` 在运行时区分大小写，也使用查找匹配。建议对 URL 正则加锚点。

## 3. 根对象

根对象只允许两个字段：

| 字段 | 类型 | 必填 | 填写方式 |
| --- | --- | --- | --- |
| `schemaVersion` | 整数 | 是 | 当前只能填 `1`。 |
| `rules` | 对象数组 | 是 | 1～32 条规则；同一数组内的 `id` 必须唯一。 |

一个 JSON 文件可以包含多个应用规则，也可以只包含一条。外置规则仓库采用“一文件一规则”，再由 CI 合并为订阅 bundle。

## 4. 单条规则字段总览

| 字段 | 类型 | 必填 | 默认值/作用 |
| --- | --- | --- | --- |
| `id` | 字符串 | 是 | 稳定的规则标识。 |
| `version` | 正整数 | 是 | 规则自身版本。 |
| `displayName` | 字符串 | 是 | UI 中显示的规则名称。 |
| `source` | 对象 | 是 | 声明规则来源。 |
| `target` | 对象 | 是 | 目标 Android 包名和版本范围。 |
| `shareTriggerSelectors` | 选择器数组 | 是 | 识别用户点击分享入口。 |
| `sharePanelFingerprint` | 选择器数组 | 是 | 确认当前窗口确实是目标分享面板。 |
| `copyLinkScrollAnchorSelectors` | 选择器数组 | 否 | 找不到复制入口时，用于定位一次滚动的锚点；省略表示不滚动。 |
| `copyLinkSelectors` | 选择器数组 | 是 | 定位“复制链接”节点。 |
| `copyTriggerMode` | 枚举字符串 | 否 | 默认 `AUTOMATIC`。 |
| `maxClickableParentDepth` | 整数 | 是 | 从标签节点向上寻找可点击/可滚动祖先的最大层数。 |
| `sharePanelTimeoutMs` | 整数 | 是 | 查找分享面板和复制入口的超时时间。 |
| `copySettleDelayMs` | 整数 | 是 | 点击复制后等待剪贴板稳定的时间。 |
| `clipboardExtraction` | 对象 | 是 | 从复制文本中提取 URL。 |
| `redirectPolicy` | 对象 | 是 | 短链展开、最终域名和网络安全策略。 |
| `sharePreview` | 对象 | 否 | 网页标题、摘要和封面的获取规则；省略则使用默认分享卡片。 |
| `cleaningPolicy` | 对象 | 是 | 要删除和强制保留的查询参数。 |

### 4.1 `id`、`version` 和 `displayName`

#### `id`

- 长度 1～80。
- 只允许英文字母、数字、点、下划线、连字符：`[A-Za-z0-9._-]+`。
- 应在不同版本中保持稳定。推荐采用 `来源.应用.用途`，例如 `local.example-app.share-clean`。
- 同一 bundle 内不能重复；不同来源的文件可以出现相同 `id`，但它们仍会成为不同的已安装规则。

#### `version`

- 必须是 1～2,147,483,647 的整数。
- 修改选择器、域名、清洗参数或预览逻辑后应递增。
- 当前实现不会自动选择 `version` 最大的规则。若同一包名出现多条兼容规则，会暂停自动化并要求用户明确选择。

#### `displayName`

- 非空，最长 80 个字符，不允许控制字符。
- 建议填写用户能识别的应用名，不要把版本范围塞进名称；版本范围应写在 `target`。

## 5. 来源和目标应用

### 5.1 `source`

`source` 只允许以下字段：

| 字段 | 类型 | 必填 | 填写方式 |
| --- | --- | --- | --- |
| `kind` | 枚举字符串 | 是 | `BUILTIN`、`LOCAL` 或 `REMOTE`；当前应用只从本地文件或远程订阅加载规则。 |
| `reference` | 字符串 | 是 | 非空、最长 512 个字符；通常是资源路径、文件名或订阅 URL。 |

推荐写法：

- 外置规则仓库：`"kind": "REMOTE"`，`reference` 填订阅 bundle 地址。
- 本地导入：`"kind": "LOCAL"`，`reference` 填便于识别的文件名。
- 远程订阅：`"kind": "REMOTE"`，`reference` 填发布地址。

`BUILTIN` 仅为旧格式兼容值，当前 APK 不再携带或加载内置规则。通过应用仓库加载时，运行时会用实际加载渠道覆盖声明值：本地文件会记录内部文件名，订阅会记录真实 HTTPS URL。因此本地或远程规则不能通过填写 `BUILTIN` 冒充内置规则；但 `source` 对象仍是语法上的必填项。

### 5.2 `target`

| 字段 | 类型 | 必填 | 填写方式 |
| --- | --- | --- | --- |
| `packageName` | 字符串 | 是 | Android applicationId，例如 `com.example.app`。 |
| `minVersionCode` | 非负整数或 `null` | 否 | 最低兼容 `versionCode`，包含边界。 |
| `maxVersionCode` | 非负整数或 `null` | 否 | 最高兼容 `versionCode`，包含边界。 |

`packageName` 最长 160 个字符，必须包含至少一个点，格式为：

~~~text
[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+
~~~

版本字段填写的是 Android `versionCode`，不是面向用户的 `versionName`。规则可按以下方式填写：

- 所有版本：两个字段都省略或填 `null`。
- 只有下限：填写 `minVersionCode`，`maxVersionCode` 省略或填 `null`。
- 只有上限：填写 `maxVersionCode`，`minVersionCode` 省略或填 `null`。
- 闭区间：同时填写，并保证最小值不大于最大值。

如果规则带版本边界，而应用无法读取目标应用的 `versionCode`，该有界规则不会参与匹配；无界规则仍可匹配。多条规则同时兼容同一包名时不会按版本号猜测，而是形成冲突并等待用户选择。

## 6. 无障碍节点选择器

以下四个字段都使用同一种节点选择器：

- `shareTriggerSelectors`
- `sharePanelFingerprint`
- `copyLinkScrollAnchorSelectors`
- `copyLinkSelectors`

一个选择器对象只允许以下字段：

| 字段 | 类型 | 必填 | 匹配方式 |
| --- | --- | --- | --- |
| `resourceId` | 字符串或 `null` | 否 | 与完整资源 ID 完全相等，或匹配完整 ID 的 `:id/短名称` 后缀。 |
| `textRegex` | 正则字符串或 `null` | 否 | 在节点可见文字中查找，不区分大小写。 |
| `descriptionRegex` | 正则字符串或 `null` | 否 | 在 contentDescription 中查找，不区分大小写。 |
| `className` | 字符串或 `null` | 否 | 与完整类名完全相等，区分大小写。 |
| `clickable` | 布尔值或 `null` | 否 | 节点的 `isClickable` 必须与该值一致。 |

`resourceId` 和 `className` 必须非空、最长 256 个字符；两个正则最长 256 个字符。`clickable` 不能单独构成选择器，至少要提供 `resourceId`、`textRegex`、`descriptionRegex`、`className` 中的一项。

单个选择器的组合逻辑是：

~~~text
resourceId 条件
AND className 条件
AND clickable 条件
AND (textRegex 命中 OR descriptionRegex 命中)
~~~

未填写的条件会被忽略。`textRegex` 和 `descriptionRegex` 是唯一的“或”关系：两者同时存在时，文字或描述命中任意一个即可；它们仍需与资源 ID、类名和可点击状态同时满足。

示例：

~~~json
{
  "resourceId": "label",
  "textRegex": "^(复制链接|复制連結|Copy Link)$",
  "descriptionRegex": "^(复制链接|复制連結|Copy Link)$",
  "className": "android.widget.TextView"
}
~~~

`resourceId` 建议优先写短名称，例如 `label`。运行时既能匹配 `label`，也能匹配 `com.example.app:id/label`。若资源 ID 经常混淆或被混淆，可组合稳定的文字、contentDescription、类名和可点击状态，但要避免选择器过宽。

### 6.1 四个选择器数组的关系

每个数组最多 24 项。三个必填数组以及存在时的滚动锚点数组都必须至少有 1 项；不需要滚动时应省略 `copyLinkScrollAnchorSelectors`，不能填空数组。

| 字段 | 数组内部逻辑 | 运行行为 |
| --- | --- | --- |
| `shareTriggerSelectors` | 任意一项命中即可 | 仅处理点击事件；检查事件源的直接子节点、事件源自身及其若干层祖先。 |
| `sharePanelFingerprint` | 每一项都必须在窗口树中找到 | 用于分享点击漏报时的后备启动，并用于确认模式下判断面板是否已准备好或已关闭。 |
| `copyLinkScrollAnchorSelectors` | 任意一项命中即可 | 未找到复制入口时，向上寻找可滚动且启用的祖先，最多滚动一次。 |
| `copyLinkSelectors` | 任意一项命中即可 | 广度优先查找首个命中的标签，再向上寻找可点击且启用的节点，只点击一次。 |

`sharePanelFingerprint` 的各项不要求由不同节点满足；同一节点理论上可以同时满足多项。因此编写时应主动选取两个或更多真正独立、稳定的面板特征，降低普通页面被误判为分享面板的风险。

### 6.2 自动化相关字段

| 字段 | 范围/枚举 | 说明 |
| --- | --- | --- |
| `copyTriggerMode` | `AUTOMATIC`、`USER_CONFIRMATION` | 可选，默认 `AUTOMATIC`。前者找到复制入口后自动点击；后者等待用户点击悬浮按钮。 |
| `maxClickableParentDepth` | 0～8 | 0 只检查匹配节点本身；值越大，允许向上查找越多层可点击或可滚动祖先。也用于分享点击事件的祖先匹配。 |
| `sharePanelTimeoutMs` | 1000～10000 | 自动模式下查找复制入口的任务时限。确认模式的用户等待时限当前至少为 30 秒。 |
| `copySettleDelayMs` | 100～2000 | 成功点击复制后，等待这段时间再读取剪贴板。 |

`copyTriggerMode` 是规则默认值，用户可以在应用设置中按已安装规则覆盖它。`USER_CONFIRMATION` 只有在使用无障碍悬浮结果模式时才会进入悬浮确认流程；其他展示模式仍按自动流程处理。

建议先用较小的 `maxClickableParentDepth`。值过大可能让一个普通标签借用远处容器的点击能力，从而提高误点风险。

## 7. `clipboardExtraction`

| 字段 | 类型 | 必填 | 填写方式 |
| --- | --- | --- | --- |
| `urlRegex` | 正则字符串 | 是 | 用于从完整复制文本中查找 HTTP/HTTPS URL。 |
| `maxInputLength` | 整数 | 是 | 1～32768；超过长度的剪贴板内容直接拒绝。 |

运行时最多统计 32 个匹配，只检查第一个匹配；如果第一个匹配是空字符串，则本次提取直接失败，不会跳到后续匹配。若复制文案含多个 URL，净化和“保留原文”替换的都是第一个，结果页会提示检测到多个 URL。

建议使用：

~~~json
{
  "urlRegex": "https?://[^\\s<>\"'，。！？；;（）()《》【】]+",
  "maxInputLength": 16384
}
~~~

正则应只覆盖 URL 本身，不要把前后的分享文案、句号或括号一起捕获。提取后只会自动裁掉末尾不可见空白，不会通用地猜测并删除所有标点。

## 8. `redirectPolicy`

| 字段 | 类型 | 必填 | 范围/作用 |
| --- | --- | --- | --- |
| `shortLinkHosts` | 字符串数组 | 是 | 0～32 个短链域名；原 URL 命中后才会发起短链展开请求。可填空数组。 |
| `allowedFinalHosts` | 字符串数组 | 是 | 1～32 个最终域名；净化结果、预览页面、预览 API 和 Bootstrap 请求都受此白名单约束。 |
| `maxRedirects` | 整数 | 是 | 0～5；0 表示短链请求不能发生重定向。 |
| `requireHttps` | 布尔值 | 是 | 要求短链跳转、恢复目标和预览网络请求使用 HTTPS。 |
| `connectTimeoutMs` | 整数 | 是 | 连接超时，500～10000 毫秒。 |
| `readTimeoutMs` | 整数 | 是 | 读取超时，500～10000 毫秒。 |
| `stopAtAllowedFinalHost` | 布尔值或 `null` | 否 | 默认 `false`；到达最终白名单域名后是否直接停止展开，不再探测目标页面。 |
| `accessFailures` | 对象数组 | 否 | 访问失败页识别和原始地址恢复规则；存在时必须为 1～8 项。 |

### 8.1 域名的填写和匹配

域名只写主机名，不带协议、端口、路径或通配符，例如写 `example.com`，不要写 `https://example.com`、`example.com:443`、`example.com/path` 或 `*.example.com`。

域名会转为 IDNA ASCII、小写，并去掉末尾的点。白名单自动包含子域名：

- `example.com` 会允许 `example.com`、`www.example.com` 和 `api.example.com`。
- `www.example.com` 只允许该主机及其更深子域，不会允许根域 `example.com`。
- `badexample.com` 不会因为包含相同后缀而被允许。

所有用于预览的网页、API、令牌接口、会话接口以及图片域名都必须落入对应白名单。页面/API/Bootstrap 主机放入 `allowedFinalHosts`；仅图片 CDN 可以额外放入 `sharePreview.imageAllowedHosts`。

### 8.2 短链展开行为

只有原始 URL 主机命中 `shortLinkHosts` 时才请求网络。每一步都会：

1. 只接受 HTTP/HTTPS、无用户名密码的 URI。
2. 在 `requireHttps` 为 `true` 时阻止非 HTTPS 跳转；无显式端口的初始 HTTP 短链会先尝试升级为 HTTPS。
3. 阻止本机、私网和链路本地地址。
4. 检测重定向循环和超过 `maxRedirects` 的链路。
5. 最终要求主机命中 `allowedFinalHosts`。

`stopAtAllowedFinalHost: true` 适合“拿到最终 Location 就够了”的站点。短链一旦跳到最终白名单域名且该域名不再属于短链域名，解析器会直接采用该 URL，避免为了确认 2xx 再请求一次内容页。网页预览稍后会带站点专用请求头自行获取页面。

注意：当前 `requireHttps` 会严格约束短链展开、恢复目标和预览请求，但不会单独拒绝一个直接进入清洗流程、且不属于短链的 HTTP 最终 URL。如果规则必须连直接链接也只接受 HTTPS，应把 `clipboardExtraction.urlRegex` 写成只匹配 `https://`。

### 8.3 `accessFailures`

某些站点会把公开内容重定向到登录错误页或安全 404，同时把原始地址放进查询参数。每项只允许：

| 字段 | 类型 | 必填 | 填写方式 |
| --- | --- | --- | --- |
| `urlRegex` | 正则字符串 | 是 | 在完整 ASCII URL 上不区分大小写查找；建议使用 `^...$`。 |
| `recoveryQueryParameter` | 字符串或 `null` | 否 | 保存原始 URL 的查询参数名，最长 64，只允许字母、数字、点、下划线、波浪线和连字符。 |

示例：

~~~json
{
  "accessFailures": [
    {
      "urlRegex": "^https://([a-z0-9-]+\\.)*example\\.com/login/error([?#].*)?$",
      "recoveryQueryParameter": "redirectPath"
    }
  ]
}
~~~

恢复时会读取该参数、URL 解码一次，再验证目标：

- 只能是 HTTP/HTTPS，不能含用户名密码。
- 必须仍属于 `allowedFinalHosts`。
- `requireHttps` 为 `true` 时，默认端口的 HTTP 目标会尝试升级为 HTTPS。
- 恢复后的目标不能再次命中访问失败页规则。

如果省略 `recoveryQueryParameter`，规则仍能识别访问失败页，但无法恢复时本次处理会明确失败。

## 9. `cleaningPolicy`

三个数组都必填，但可以为空；每个数组最多 128 项。每项最长 64，只允许字母、数字、点、下划线、波浪线和连字符。

| 字段 | 匹配方式 | 用途 |
| --- | --- | --- |
| `removeExact` | 查询参数名完全相等 | 删除确定的追踪参数，例如 `share_id`、`gclid`。 |
| `removePrefixes` | 查询参数名以该值开头 | 批量删除一族参数，例如 `utm_`。 |
| `forceKeep` | 查询参数名完全相等，优先级最高 | 即使同时命中删除规则也必须保留，例如内容 ID、页码、签名或访问令牌。 |

参数名会先做 URL 解码，再转小写进行比较，因此匹配不区分大小写。`forceKeep` 先于两个删除列表判断：

~~~text
forceKeep 命中 → 保留
否则 removeExact 命中 → 删除
否则任意 removePrefixes 命中 → 删除
否则 → 保留
~~~

清洗器只修改查询字符串，不改路径和片段。未删除参数的原始顺序、值和百分号编码会保留；无法正确 URL 解码的参数名会保留。应谨慎填写 `removePrefixes`，并把维持页面语义所需的参数放入 `forceKeep`。

## 10. `sharePreview`

`sharePreview` 整体可选；不需要联网读取网页卡片时直接省略，不能写 `null`。存在时只允许：

| 字段 | 类型 | 必填 | 默认值/作用 |
| --- | --- | --- | --- |
| `titleSelectors` | 预览选择器数组 | 是 | 1～8 项，按顺序寻找标题。 |
| `descriptionSelectors` | 预览选择器数组 | 是 | 1～8 项，按顺序寻找摘要。 |
| `imageSelectors` | 预览选择器数组 | 是 | 1～8 项，按顺序寻找封面 URL。 |
| `imageAllowedHosts` | 域名数组 | 是 | 0～32 个额外图片域名，可为空。 |
| `request` | 请求对象 | 否 | 将净化 URL 转换为 HTML/JSON API 请求。 |
| `fallbackRequests` | 请求对象数组 | 否 | 主请求或默认页面请求失败后依次尝试；存在时 1～3 项。 |
| `bootstrap` | 对象 | 否 | 在首个配置请求前建立 Cookie/访客会话。 |
| `pageRequestHeaders` | 字符串对象 | 否 | 默认直接请求净化页面时使用；默认空对象。 |
| `imageRequestHeaders` | 字符串对象 | 否 | 下载封面时使用；默认从页面请求头继承安全子集。 |

没有 `request`，或其 `urlRegex` 不匹配净化 URL 时，首先直接 GET 净化 URL，并按 HTML 解析。若 `request` 匹配，则首先使用配置请求，不会自动再回退到直接页面；需要的备用方案必须显式写入 `fallbackRequests`。

`fallbackRequests` 只选取 `urlRegex` 能匹配当前净化 URL 的项，并按数组顺序尝试。主请求不存在或不匹配时，顺序是“默认页面请求 → 匹配的备用请求”；主请求匹配时，顺序是“主请求 → 匹配的备用请求”。

页面、API 和 Bootstrap 请求的每一步重定向仍必须命中 `redirectPolicy.allowedFinalHosts`，并受 HTTPS、公共网络和超时限制。预览页面响应最大 2 MiB，图片响应最大 4 MiB；预览重定向另有固定的最多 3 次限制。

### 10.1 预览字段选择器

每个选择器只允许 `type` 和 `key`：

| `type` | `key` | 可用于 | 行为 |
| --- | --- | --- | --- |
| `META_PROPERTY` | 必填 | HTML 标题/摘要/图片 | 查找 `<meta property="key" content="...">`，属性值匹配不区分大小写。 |
| `META_NAME` | 必填 | HTML 标题/摘要/图片 | 查找 `<meta name="key" content="...">`，属性值匹配不区分大小写。 |
| `HTML_TITLE` | 必须省略或为 `null` | 仅 `titleSelectors` | 读取 HTML `<title>`。摘要和图片数组中禁止使用。 |
| `JSON_PATH` | 必填 | JSON 响应 | 从整个 JSON 响应按点路径取字符串。HTML 响应中会忽略。 |
| `SCRIPT_JSON_PATH` | 必填 | HTML 响应 | 从页面 `<script>` 中的 JSON 状态按点路径取字符串。JSON 响应中会忽略。 |

除 `HTML_TITLE` 外，`key` 长度为 1～80：

- `META_PROPERTY` 和 `META_NAME` 只允许字母、数字、点、下划线、冒号和连字符。
- `JSON_PATH` 和 `SCRIPT_JSON_PATH` 使用点分段；每段只允许字母、数字、下划线、冒号、连字符或单独的 `*`。

标题和摘要选择器按数组顺序取第一个可用字符串。图片选择器会先记住第一个可用值，并优先采用首个主机命中 `imageAllowedHosts` 的值；没有这种值时才回退到先前记录的第一个值，下载前仍会执行完整图片白名单校验。返回的 JSON 值必须是字符串；数字、布尔值、对象和数组不会自动转成文本。

#### JSON 点路径

路径不写 `$`，不使用方括号：

~~~text
songs.0.name
post_list.0.content.0.text
entities.answers.*.question.title
~~~

- 对象段按属性名访问。
- 数组段使用从 0 开始的数字索引。
- `*` 可遍历当前对象的所有值或当前数组的所有元素，返回第一个可用字符串。
- 单次路径查找最多访问 512 个节点，避免对巨大状态树做无界扫描。

#### `SCRIPT_JSON_PATH` 的根

第一个路径段是脚本根名。当前解析器识别两种 HTML 写法：

1. `<script id="rootName">{"...":"..."}</script>`，脚本内容本身是 JSON。
2. 脚本文本严格以 `window.rootName=` 开头，等号后是 JSON，可有结尾分号。

例如：

~~~json
{
  "type": "SCRIPT_JSON_PATH",
  "key": "__SETUP_SERVER_STATE__.pageData.note.title"
}
~~~

这里会寻找 ID 为 `__SETUP_SERVER_STATE__` 的 JSON 脚本，或以
`window.__SETUP_SERVER_STATE__=` 开头的脚本，再读取
`pageData.note.title`。

### 10.2 HTML 页面预览示例

~~~json
{
  "sharePreview": {
    "titleSelectors": [
      { "type": "META_PROPERTY", "key": "og:title" },
      { "type": "META_NAME", "key": "twitter:title" },
      { "type": "HTML_TITLE" }
    ],
    "descriptionSelectors": [
      { "type": "META_PROPERTY", "key": "og:description" },
      { "type": "META_NAME", "key": "description" }
    ],
    "imageSelectors": [
      { "type": "META_PROPERTY", "key": "og:image" },
      { "type": "META_NAME", "key": "twitter:image" }
    ],
    "imageAllowedHosts": [
      "examplecdn.com"
    ],
    "pageRequestHeaders": {
      "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
      "Accept": "text/html,application/xhtml+xml;q=0.9"
    },
    "imageRequestHeaders": {
      "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
      "Referer": "https://www.example.com/"
    }
  }
}
~~~

图片 URL 可以是绝对 URL 或相对页面 URL。最终图片主机必须属于
`redirectPolicy.allowedFinalHosts` 与 `imageAllowedHosts` 的并集。若
`requireHttps` 为 `true`，普通 HTTP 图片会先尝试改成 HTTPS。

### 10.3 请求头和表单对象

请求头对象最多 16 项：

- 键必须是合法 HTTP header token，非空且最长 64。
- 值必须是非空字符串，最长 512，不含控制字符。

表单对象最多 24 项：

- 键必须非空且最长 64；建议只用普通表单参数名。
- 值必须是非空字符串，最长 1024，不含控制字符。

`request.headers` 是配置请求的完整请求头，不会与 `pageRequestHeaders` 合并。`pageRequestHeaders` 仅用于默认直接页面请求。

如果省略或留空 `imageRequestHeaders`，图片请求会继承 `pageRequestHeaders`，但会去除 `Accept` 和 `Content-Type`。显式填写 `imageRequestHeaders` 后则使用该对象，不再自动合并。

### 10.4 `request` 和 `fallbackRequests[]`

两者的单项结构完全相同：

| 字段 | 类型 | 必填 | 填写方式 |
| --- | --- | --- | --- |
| `urlRegex` | 正则字符串 | 是 | 区分大小写匹配净化 URL，也用于提取捕获组。 |
| `urlReplacement` | 字符串 | 是 | 将净化 URL 转换成实际请求 URL，非空、最长 1024。 |
| `method` | 枚举字符串 | 是 | `GET` 或 `POST`。 |
| `headers` | 字符串对象 | 是 | 最多 16 项；不需要时填 `{}`。 |
| `formParameters` | 字符串对象 | 否 | 最多 24 项，省略时为空；GET 只能省略或填空对象。 |
| `signature` | 对象 | 否 | 对表单参数生成签名。 |
| `responseType` | 枚举字符串 | 是 | `HTML` 或 `JSON`。 |

`urlReplacement` 和每个 `formParameters` 值都会以净化 URL 为输入，对
`urlRegex` 执行一次替换。可用 `$1`、`$2` 引用捕获组：

~~~json
{
  "request": {
    "urlRegex": "^https://www\\.example\\.com/item/([0-9]+).*$",
    "urlReplacement": "https://api.example.com/v1/item/$1",
    "method": "GET",
    "headers": {
      "Accept": "application/json"
    },
    "formParameters": {},
    "responseType": "JSON"
  }
}
~~~

POST 表单会使用 UTF-8 URL 编码，空格编码为 `%20`。客户端不会自动替规则补上站点要求的 Content-Type；通常应在 `headers` 中显式填写：

~~~json
{
  "Content-Type": "application/x-www-form-urlencoded"
}
~~~

`responseType: "HTML"` 会按 HTML 解析；服务器提供 Content-Type 时，只接受
`text/html` 或 `application/xhtml+xml`。`responseType: "JSON"` 会直接把响应体解析成
JSON，因此响应体必须是合法 JSON，并使用 `JSON_PATH` 选择器。

### 10.5 `signature`

当前只支持：

| 字段 | 类型 | 必填 | 填写方式 |
| --- | --- | --- | --- |
| `algorithm` | 枚举字符串 | 是 | 只能填 `MD5_CONCAT`。 |
| `parameterName` | 字符串 | 是 | 最长 64，只允许字母、数字、点、下划线和连字符。 |
| `suffix` | 字符串 | 是 | 非空、最长 512，不含控制字符。 |

`MD5_CONCAT` 按 `formParameters` 的 JSON 声明顺序生成材料：

~~~text
参数1名=替换后的值参数2名=替换后的值...suffix
~~~

对该 UTF-8 字符串计算小写十六进制 MD5，再以 `parameterName` 加入表单。签名发生在 URL 编码之前。由于顺序会影响结果，不要随意重排需要签名的表单字段。`signature` 设计用于 POST 表单请求，GET 请求不要配置它。

示例：

~~~json
{
  "formParameters": {
    "item_id": "$1",
    "page": "1"
  },
  "signature": {
    "algorithm": "MD5_CONCAT",
    "parameterName": "sign",
    "suffix": "server-agreed-suffix"
  }
}
~~~

上例的签名材料为：

~~~text
item_id=<捕获组1>page=1server-agreed-suffix
~~~

### 10.6 `bootstrap`

需要先取得访客令牌或 Cookie 才能调用预览 API 时使用。对象存在时六个字段全部必填：

| 字段 | 类型 | 填写方式 |
| --- | --- | --- |
| `tokenUrl` | 字符串 | 令牌接口的绝对 URL，非空、最长 1024。运行时必须命中 `allowedFinalHosts`。 |
| `tokenHeaders` | 字符串对象 | 令牌 POST 请求头，最多 16 项。 |
| `tokenFormParameters` | 字符串对象 | 令牌 POST 表单，最多 24 项；不需要参数时填 `{}`。 |
| `tokenRegex` | 正则字符串 | 区分大小写解析响应，必须用第 1 个捕获组返回非空令牌。 |
| `sessionUrlTemplate` | 字符串 | 建立会话的 GET URL，非空、最长 1024；用 `{token}` 放置 URL 编码后的令牌。 |
| `sessionHeaders` | 字符串对象 | 建立会话的 GET 请求头，最多 16 项。 |

执行顺序为：

1. POST `tokenUrl`，保存响应 Cookie。
2. 用 `tokenRegex` 的第 1 个捕获组取得令牌。
3. URL 编码令牌并替换 `sessionUrlTemplate` 中所有 `{token}`。
4. GET 会话 URL，继续保存 Cookie。
5. 使用同一个 Cookie 会话执行 `request` 或 `fallbackRequests`。

Bootstrap 只在即将执行首个配置请求时运行；单纯的默认 HTML 页面请求不会触发它。所有 Bootstrap URL 和重定向同样受 `allowedFinalHosts`、`requireHttps` 和公共网络检查约束。

## 11. 常用限制速查

| 项目 | 限制 |
| --- | --- |
| 单文件大小 | 1～524288 字节 |
| `rules` | 1～32 项 |
| 每组无障碍选择器 | 1～24 项 |
| `maxClickableParentDepth` | 0～8 |
| `sharePanelTimeoutMs` | 1000～10000 |
| `copySettleDelayMs` | 100～2000 |
| `clipboardExtraction.maxInputLength` | 1～32768 |
| 任意规则正则 | 1～256 字符，且必须兼容 RE2/J |
| 每个域名数组 | 最多 32 项；`allowedFinalHosts` 至少 1 项 |
| `maxRedirects` | 0～5 |
| 网络连接/读取超时 | 500～10000 毫秒 |
| `accessFailures` | 省略，或 1～8 项 |
| 每个清洗参数数组 | 0～128 项 |
| 每组预览字段选择器 | 1～8 项 |
| `fallbackRequests` | 省略，或 1～3 项 |
| 单个请求头对象 | 0～16 项 |
| 单个表单对象 | 0～24 项 |

## 12. 现有规则可参考的功能

| 需求 | 参考文件 |
| --- | --- |
| 普通 HTML 元数据与脚本 JSON | [xiaohongshu.json](https://github.com/StopTrackingMe-Dev/rules/blob/main/rules/xiaohongshu.json) |
| 用户确认、滚动锚点、JSON GET API | [bilibili.json](https://github.com/StopTrackingMe-Dev/rules/blob/main/rules/bilibili.json) |
| 简单 JSON GET API | [netease-cloud-music.json](https://github.com/StopTrackingMe-Dev/rules/blob/main/rules/netease-cloud-music.json) |
| POST 表单和 `MD5_CONCAT` 签名 | [baidu-tieba.json](https://github.com/StopTrackingMe-Dev/rules/blob/main/rules/baidu-tieba.json) |
| Bootstrap 访客会话 | [weibo.json](https://github.com/StopTrackingMe-Dev/rules/blob/main/rules/weibo.json) |
| 主请求、备用请求和通配 JSON 路径 | [zhihu.json](https://github.com/StopTrackingMe-Dev/rules/blob/main/rules/zhihu.json) |

## 13. 编写和验证建议

1. 先确认目标应用的 `packageName` 和实际 `versionCode`。
2. 用无障碍检查工具记录分享入口、面板标题、复制链接节点的资源 ID、文字、contentDescription、类名和可点击状态。
3. 优先使用稳定资源 ID；再用锚定的多语言文字/描述正则补充。不要只写过宽的 `.*`。
4. 让 `sharePanelFingerprint` 包含至少两个独立特征，并尽量避免与内容页通用控件重合。
5. 先只完成复制、URL 提取、域名校验和参数清洗；确认稳定后再增加预览 API、Bootstrap 或签名。
6. 把所有可能的短链入口放入 `shortLinkHosts`，把所有合法最终页面和预览 API 主机放入 `allowedFinalHosts`。
7. 清洗参数时先确认哪些参数决定内容、页码、时间点、鉴权或签名，并加入 `forceKeep`。
8. 修改已有规则后递增 `version`，但不要假设应用会自动按版本选择规则。
9. 外置规则变更后应先运行 Android 单元测试：

~~~powershell
.\gradlew.bat testDebugUnitTest
~~~

测试夹具来自外置 `stoptracking-rules` 仓库。默认路径是主项目同级目录；若仓库位于其他位置，使用
`./gradlew.bat -PrulesRepoDir=规则仓库路径 testDebugUnitTest` 指定。

规则加载失败时，优先检查：

- 字段名拼写、未知字段、重复字段或尾随逗号。
- 可选数组是否错误地填成空数组；`copyLinkScrollAnchorSelectors`、`accessFailures` 和 `fallbackRequests` 不需要时应省略。
- JSON 中的正则反斜杠是否正确双重转义。
- 是否使用了 RE2/J 不支持的环视或回溯引用。
- 请求、图片和 Bootstrap 域名是否都在对应白名单中。
- GET 请求是否错误地带了非空 `formParameters`。
- `JSON_PATH` 是否用于 JSON 响应、`SCRIPT_JSON_PATH` 是否用于 HTML 响应，以及目标值是否真的是字符串。
- 参数删除规则是否误删了内容 ID、页码、时间点、令牌或签名。
