package app.stoptrackingme.rules

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.IDN
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

class RuleParser {
    fun parse(bytes: ByteArray, sourceOverride: RuleSource? = null): RuleBundle {
        if (bytes.isEmpty()) throw RuleValidationException("规则文件为空")
        if (bytes.size > MAX_BUNDLE_BYTES) throw RuleValidationException("规则文件超过大小限制")

        val json = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw RuleValidationException("规则文件不是有效的 UTF-8", error)
        }

        val root = try {
            StrictJsonParser.parse(json).requiredObject("规则根节点")
        } catch (error: RuleValidationException) {
            throw error
        } catch (error: Exception) {
            throw RuleValidationException("规则 JSON 无法解析", error)
        }

        root.requireOnly("schemaVersion", "rules")
        val schemaVersion = root.requiredInt("schemaVersion")
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw RuleValidationException("不支持的规则格式版本：$schemaVersion")
        }

        val rulesJson = root.requiredArray("rules")
        if (rulesJson.size() !in 1..MAX_RULES_PER_BUNDLE) {
            throw RuleValidationException("规则数量必须在 1 到 $MAX_RULES_PER_BUNDLE 之间")
        }
        val rules = rulesJson.mapIndexed { index, element ->
            parseRule(element.requiredObject("rules[$index]"), sourceOverride)
        }
        val duplicate = rules.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) throw RuleValidationException("规则标识重复：${duplicate.key}")
        return RuleBundle(schemaVersion, rules)
    }

    private fun parseRule(json: JsonObject, sourceOverride: RuleSource?): AppRule {
        json.requireOnly(
            "id",
            "version",
            "displayName",
            "source",
            "target",
            "shareTriggerSelectors",
            "sharePanelFingerprint",
            "copyLinkScrollAnchorSelectors",
            "copyLinkSelectors",
            "copyTriggerMode",
            "maxClickableParentDepth",
            "sharePanelTimeoutMs",
            "copySettleDelayMs",
            "clipboardExtraction",
            "redirectPolicy",
            "sharePreview",
            "cleaningPolicy",
        )
        val id = json.requiredString("id").validatedToken("规则标识", MAX_ID_LENGTH)
        val version = json.requiredInt("version")
        if (version <= 0) throw RuleValidationException("规则 $id 的版本必须为正整数")
        val displayName = json.requiredString("displayName").validatedText("规则名称", MAX_NAME_LENGTH)
        val declaredSource = parseSource(json.requiredObject("source"))
        val source = sourceOverride ?: declaredSource
        val target = parseTarget(json.requiredObject("target"))
        val triggers = parseSelectors(json.requiredArray("shareTriggerSelectors"), "分享触发选择器")
        val fingerprint = parseSelectors(json.requiredArray("sharePanelFingerprint"), "分享面板指纹")
        val scrollAnchors = json.get("copyLinkScrollAnchorSelectors")?.let { element ->
            if (!element.isJsonArray) {
                throw RuleValidationException("复制链接滚动锚点必须是数组")
            }
            parseSelectors(element.asJsonArray, "复制链接滚动锚点")
        }.orEmpty()
        val copySelectors = parseSelectors(json.requiredArray("copyLinkSelectors"), "复制链接选择器")
        val copyTriggerMode = json.optionalString("copyTriggerMode")?.let { value ->
            try {
                CopyTriggerMode.valueOf(value.uppercase(Locale.ROOT))
            } catch (error: IllegalArgumentException) {
                throw RuleValidationException("复制触发模式无效", error)
            }
        } ?: CopyTriggerMode.AUTOMATIC
        val maxParentDepth = json.requiredInt("maxClickableParentDepth")
        if (maxParentDepth !in 0..MAX_CLICKABLE_PARENT_DEPTH) {
            throw RuleValidationException("最大可点击父节点深度超出限制")
        }
        val panelTimeout = json.requiredLong("sharePanelTimeoutMs")
        if (panelTimeout !in MIN_PANEL_TIMEOUT_MS..MAX_PANEL_TIMEOUT_MS) {
            throw RuleValidationException("分享面板超时超出限制")
        }
        val settleDelay = json.requiredLong("copySettleDelayMs")
        if (settleDelay !in MIN_SETTLE_DELAY_MS..MAX_SETTLE_DELAY_MS) {
            throw RuleValidationException("剪贴板等待时间超出限制")
        }

        return AppRule(
            id = id,
            version = version,
            displayName = displayName,
            source = source,
            target = target,
            shareTriggerSelectors = triggers,
            sharePanelFingerprint = fingerprint,
            copyLinkScrollAnchorSelectors = scrollAnchors,
            copyLinkSelectors = copySelectors,
            copyTriggerMode = copyTriggerMode,
            maxClickableParentDepth = maxParentDepth,
            sharePanelTimeoutMs = panelTimeout,
            copySettleDelayMs = settleDelay,
            clipboardExtraction = parseClipboard(json.requiredObject("clipboardExtraction")),
            redirectPolicy = parseRedirectPolicy(json.requiredObject("redirectPolicy")),
            sharePreview = json.get("sharePreview")?.let { element ->
                parseSharePreview(element.requiredObject("sharePreview"))
            },
            cleaningPolicy = parseCleaningPolicy(json.requiredObject("cleaningPolicy")),
        )
    }

    private fun parseSource(json: JsonObject): RuleSource {
        json.requireOnly("kind", "reference")
        val kind = try {
            RuleSourceKind.valueOf(json.requiredString("kind").uppercase(Locale.ROOT))
        } catch (error: IllegalArgumentException) {
            throw RuleValidationException("未知的规则来源类型", error)
        }
        val reference = json.requiredString("reference").validatedText("规则来源", MAX_SOURCE_LENGTH)
        return RuleSource(kind, reference)
    }

    private fun parseTarget(json: JsonObject): AppTarget {
        json.requireOnly("packageName", "minVersionCode", "maxVersionCode")
        val packageName = json.requiredString("packageName")
        if (packageName.length > MAX_PACKAGE_LENGTH || !PACKAGE_PATTERN.matches(packageName)) {
            throw RuleValidationException("目标包名无效")
        }
        val min = json.optionalLong("minVersionCode")
        val max = json.optionalLong("maxVersionCode")
        if (min != null && min < 0 || max != null && max < 0 || min != null && max != null && min > max) {
            throw RuleValidationException("目标应用版本范围无效")
        }
        return AppTarget(packageName, min, max)
    }

    private fun parseSelectors(json: JsonArray, label: String): List<NodeSelector> {
        if (json.size() !in 1..MAX_SELECTORS_PER_GROUP) {
            throw RuleValidationException("$label 数量超出限制")
        }
        return json.mapIndexed { index, element ->
            val selector = element.requiredObject("$label[$index]")
            selector.requireOnly("resourceId", "textRegex", "descriptionRegex", "className", "clickable")
            val resourceId = selector.optionalString("resourceId")?.validatedText("资源 ID", MAX_SELECTOR_TEXT)
            val textRegex = selector.optionalRegex("textRegex")
            val descriptionRegex = selector.optionalRegex("descriptionRegex")
            val className = selector.optionalString("className")?.validatedText("节点类型", MAX_SELECTOR_TEXT)
            val clickable = selector.optionalBoolean("clickable")
            if (resourceId == null && textRegex == null && descriptionRegex == null && className == null) {
                throw RuleValidationException("$label 中存在空选择器")
            }
            NodeSelector(resourceId, textRegex, descriptionRegex, className, clickable)
        }
    }

    private fun parseClipboard(json: JsonObject): ClipboardExtractionRule {
        json.requireOnly("urlRegex", "maxInputLength")
        val regex = json.requiredRegex("urlRegex")
        val maxLength = json.requiredInt("maxInputLength")
        if (maxLength !in 1..MAX_CLIPBOARD_INPUT_LENGTH) {
            throw RuleValidationException("剪贴板输入长度限制无效")
        }
        return ClipboardExtractionRule(regex, maxLength)
    }

    private fun parseRedirectPolicy(json: JsonObject): RedirectPolicy {
        json.requireOnly(
            "shortLinkHosts",
            "allowedFinalHosts",
            "maxRedirects",
            "requireHttps",
            "connectTimeoutMs",
            "readTimeoutMs",
            "stopAtAllowedFinalHost",
            "accessFailures",
        )
        val shortHosts = json.requiredStringSet("shortLinkHosts", MAX_HOSTS).mapTo(linkedSetOf(), ::normalizeHost)
        val finalHosts = json.requiredStringSet("allowedFinalHosts", MAX_HOSTS).mapTo(linkedSetOf(), ::normalizeHost)
        if (finalHosts.isEmpty()) throw RuleValidationException("最终域名白名单不能为空")
        val maxRedirects = json.requiredInt("maxRedirects")
        if (maxRedirects !in 0..MAX_REDIRECTS) throw RuleValidationException("重定向次数超出限制")
        val connectTimeout = json.requiredInt("connectTimeoutMs")
        val readTimeout = json.requiredInt("readTimeoutMs")
        if (connectTimeout !in MIN_NETWORK_TIMEOUT_MS..MAX_NETWORK_TIMEOUT_MS ||
            readTimeout !in MIN_NETWORK_TIMEOUT_MS..MAX_NETWORK_TIMEOUT_MS
        ) {
            throw RuleValidationException("网络超时超出限制")
        }
        return RedirectPolicy(
            shortLinkHosts = shortHosts,
            allowedFinalHosts = finalHosts,
            maxRedirects = maxRedirects,
            requireHttps = json.requiredBoolean("requireHttps"),
            connectTimeoutMs = connectTimeout,
            readTimeoutMs = readTimeout,
            stopAtAllowedFinalHost = json.optionalBoolean("stopAtAllowedFinalHost") ?: false,
            accessFailures = json.get("accessFailures")?.let(::parseAccessFailures).orEmpty(),
        )
    }

    private fun parseAccessFailures(element: JsonElement): List<AccessFailureRule> {
        if (!element.isJsonArray || element.asJsonArray.size() !in 1..MAX_ACCESS_FAILURE_RULES) {
            throw RuleValidationException("访问失败规则数量无效")
        }
        return element.asJsonArray.mapIndexed { index, item ->
            parseAccessFailure(item.requiredObject("accessFailures[$index]"))
        }
    }

    private fun parseAccessFailure(json: JsonObject): AccessFailureRule {
        json.requireOnly("urlRegex", "recoveryQueryParameter")
        val recoveryParameter = json.optionalString("recoveryQueryParameter")?.also { value ->
            if (value.isEmpty() || value.length > MAX_PARAMETER_LENGTH || !PARAMETER_PATTERN.matches(value)) {
                throw RuleValidationException("访问失败恢复参数名无效")
            }
        }
        return AccessFailureRule(
            urlRegex = json.requiredRegex("urlRegex"),
            recoveryQueryParameter = recoveryParameter,
        )
    }

    private fun parseCleaningPolicy(json: JsonObject): CleaningPolicy {
        json.requireOnly("removeExact", "removePrefixes", "forceKeep")
        return CleaningPolicy(
            removeExact = json.requiredParameterSet("removeExact"),
            removePrefixes = json.requiredParameterSet("removePrefixes"),
            forceKeep = json.requiredParameterSet("forceKeep"),
        )
    }

    private fun parseSharePreview(json: JsonObject): SharePreviewRule {
        json.requireOnly(
            "titleSelectors",
            "descriptionSelectors",
            "imageSelectors",
            "imageAllowedHosts",
            "request",
            "fallbackRequests",
            "bootstrap",
            "pageRequestHeaders",
            "imageRequestHeaders",
        )
        return SharePreviewRule(
            titleSelectors = parsePreviewSelectors(
                json.requiredArray("titleSelectors"),
                "预览标题选择器",
                allowHtmlTitle = true,
            ),
            descriptionSelectors = parsePreviewSelectors(
                json.requiredArray("descriptionSelectors"),
                "预览摘要选择器",
                allowHtmlTitle = false,
            ),
            imageSelectors = parsePreviewSelectors(
                json.requiredArray("imageSelectors"),
                "预览图片选择器",
                allowHtmlTitle = false,
            ),
            imageAllowedHosts = json.requiredStringSet("imageAllowedHosts", MAX_HOSTS)
                .mapTo(linkedSetOf(), ::normalizeHost),
            request = json.get("request")?.let { parsePreviewRequest(it.requiredObject("request")) },
            fallbackRequests = json.get("fallbackRequests")?.let(::parsePreviewRequests).orEmpty(),
            bootstrap = json.get("bootstrap")?.let { parsePreviewBootstrap(it.requiredObject("bootstrap")) },
            pageRequestHeaders = json.get("pageRequestHeaders")?.let {
                parseHeaders(it.requiredObject("pageRequestHeaders"))
            }.orEmpty(),
            imageRequestHeaders = json.get("imageRequestHeaders")?.let {
                parseHeaders(it.requiredObject("imageRequestHeaders"))
            }.orEmpty(),
        )
    }

    private fun parsePreviewRequests(element: JsonElement): List<PreviewRequestRule> {
        if (!element.isJsonArray || element.asJsonArray.size() !in 1..MAX_PREVIEW_FALLBACK_REQUESTS) {
            throw RuleValidationException("预览备用请求数量无效")
        }
        return element.asJsonArray.mapIndexed { index, item ->
            parsePreviewRequest(item.requiredObject("fallbackRequests[$index]"))
        }
    }

    private fun parsePreviewRequest(json: JsonObject): PreviewRequestRule {
        json.requireOnly(
            "urlRegex", "urlReplacement", "method", "headers", "formParameters",
            "signature", "responseType",
        )
        val method = enumValue<PreviewHttpMethod>(json.requiredString("method"), "预览请求方法")
        val form = json.get("formParameters")?.let {
            parseStringMap(it.requiredObject("formParameters"), MAX_FORM_PARAMETERS, MAX_TEMPLATE_LENGTH)
        }.orEmpty()
        if (method == PreviewHttpMethod.GET && form.isNotEmpty()) {
            throw RuleValidationException("GET 预览请求不能包含表单")
        }
        return PreviewRequestRule(
            urlRegex = json.requiredRegex("urlRegex"),
            urlReplacement = json.requiredString("urlReplacement")
                .validatedText("预览 URL 模板", MAX_TEMPLATE_LENGTH),
            method = method,
            headers = parseHeaders(json.requiredObject("headers")),
            formParameters = form,
            signature = json.get("signature")?.let { parseSignature(it.requiredObject("signature")) },
            responseType = enumValue(json.requiredString("responseType"), "预览响应类型"),
        )
    }

    private fun parseSignature(json: JsonObject): PreviewSignatureRule {
        json.requireOnly("algorithm", "parameterName", "suffix")
        val parameterName = json.requiredString("parameterName")
            .validatedToken("签名参数", MAX_PARAMETER_LENGTH)
        return PreviewSignatureRule(
            algorithm = enumValue(json.requiredString("algorithm"), "签名算法"),
            parameterName = parameterName,
            suffix = json.requiredString("suffix").validatedText("签名后缀", MAX_HEADER_VALUE_LENGTH),
        )
    }

    private fun parsePreviewBootstrap(json: JsonObject): PreviewBootstrapRule {
        json.requireOnly(
            "tokenUrl", "tokenHeaders", "tokenFormParameters", "tokenRegex",
            "sessionUrlTemplate", "sessionHeaders",
        )
        return PreviewBootstrapRule(
            tokenUrl = json.requiredString("tokenUrl").validatedText("访问令牌 URL", MAX_TEMPLATE_LENGTH),
            tokenHeaders = parseHeaders(json.requiredObject("tokenHeaders")),
            tokenFormParameters = parseStringMap(
                json.requiredObject("tokenFormParameters"), MAX_FORM_PARAMETERS, MAX_TEMPLATE_LENGTH,
            ),
            tokenRegex = json.requiredRegex("tokenRegex"),
            sessionUrlTemplate = json.requiredString("sessionUrlTemplate")
                .validatedText("会话 URL 模板", MAX_TEMPLATE_LENGTH),
            sessionHeaders = parseHeaders(json.requiredObject("sessionHeaders")),
        )
    }

    private fun parseHeaders(json: JsonObject): Map<String, String> =
        parseStringMap(json, MAX_HEADERS, MAX_HEADER_VALUE_LENGTH).also { headers ->
            headers.keys.forEach { name ->
                if (!HEADER_NAME_PATTERN.matches(name)) throw RuleValidationException("HTTP 请求头名称无效")
            }
        }

    private fun parseStringMap(json: JsonObject, maxEntries: Int, maxValueLength: Int): Map<String, String> {
        if (json.size() > maxEntries) throw RuleValidationException("配置对象字段过多")
        return linkedMapOf<String, String>().also { result ->
            json.entrySet().forEach { (key, element) ->
                if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString ||
                    key.isBlank() || key.length > MAX_HEADER_NAME_LENGTH
                ) throw RuleValidationException("配置对象必须只包含短字符串")
                result[key] = element.asString.validatedText("配置值", maxValueLength)
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T = try {
        enumValueOf<T>(value.uppercase(Locale.ROOT))
    } catch (error: IllegalArgumentException) {
        throw RuleValidationException("$label 无效", error)
    }

    private fun parsePreviewSelectors(
        json: JsonArray,
        label: String,
        allowHtmlTitle: Boolean,
    ): List<PreviewFieldSelector> {
        if (json.size() !in 1..MAX_PREVIEW_SELECTORS) {
            throw RuleValidationException("$label 数量超出限制")
        }
        return json.mapIndexed { index, element ->
            val selector = element.requiredObject("$label[$index]")
            selector.requireOnly("type", "key")
            val type = try {
                PreviewSelectorType.valueOf(selector.requiredString("type").uppercase(Locale.ROOT))
            } catch (error: IllegalArgumentException) {
                throw RuleValidationException("$label 类型无效", error)
            }
            val key = selector.optionalString("key")
            when (type) {
                PreviewSelectorType.HTML_TITLE -> {
                    if (!allowHtmlTitle || key != null) {
                        throw RuleValidationException("$label 中 HTML_TITLE 配置无效")
                    }
                }
                PreviewSelectorType.META_PROPERTY,
                PreviewSelectorType.META_NAME,
                -> {
                    if (key == null || key.length > MAX_PREVIEW_KEY_LENGTH ||
                        !PREVIEW_KEY_PATTERN.matches(key)
                    ) {
                        throw RuleValidationException("$label 元数据键无效")
                    }
                }
                PreviewSelectorType.JSON_PATH,
                PreviewSelectorType.SCRIPT_JSON_PATH,
                -> {
                    if (key == null || key.length > MAX_PREVIEW_KEY_LENGTH ||
                        !PREVIEW_JSON_PATH_PATTERN.matches(key)
                    ) {
                        throw RuleValidationException("$label 元数据键无效")
                    }
                }
            }
            PreviewFieldSelector(type, key)
        }
    }

    private fun normalizeHost(value: String): String {
        if (value.length > MAX_HOST_LENGTH || value.contains('*') || value.contains('/') || value.contains(':')) {
            throw RuleValidationException("域名格式无效")
        }
        val normalized = try {
            IDN.toASCII(value.trimEnd('.')).lowercase(Locale.ROOT)
        } catch (error: IllegalArgumentException) {
            throw RuleValidationException("域名格式无效", error)
        }
        if (!HOST_PATTERN.matches(normalized)) throw RuleValidationException("域名格式无效")
        return normalized
    }

    private fun JsonObject.requiredParameterSet(name: String): Set<String> =
        requiredStringSet(name, MAX_PARAMETERS).mapTo(linkedSetOf()) { value ->
            if (value.isEmpty() || value.length > MAX_PARAMETER_LENGTH || !PARAMETER_PATTERN.matches(value)) {
                throw RuleValidationException("参数规则无效：$name")
            }
            value.lowercase(Locale.ROOT)
        }

    private fun JsonObject.requiredStringSet(name: String, max: Int): Set<String> {
        val array = requiredArray(name)
        if (array.size() > max) throw RuleValidationException("$name 数量超出限制")
        val result = linkedSetOf<String>()
        array.forEachIndexed { index, element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                throw RuleValidationException("$name[$index] 必须是字符串")
            }
            result += element.asString
        }
        return result
    }

    private fun JsonObject.requiredRegex(name: String): String =
        requiredString(name).also(::validateRegex)

    private fun JsonObject.optionalRegex(name: String): String? =
        optionalString(name)?.also(::validateRegex)

    private fun validateRegex(value: String) {
        if (value.isEmpty() || value.length > MAX_REGEX_LENGTH) {
            throw RuleValidationException("正则表达式长度超出限制")
        }
        SafeRegex.validate(value)
    }

    private fun JsonObject.requireOnly(vararg allowed: String) {
        val unexpected = keySet().firstOrNull { it !in allowed }
        if (unexpected != null) {
            val suffix = if (DANGEROUS_KEYS.any { unexpected.contains(it, ignoreCase = true) }) {
                "（规则不允许坐标、脚本、Intent 或连续动作）"
            } else {
                ""
            }
            throw RuleValidationException("未知字段：$unexpected$suffix")
        }
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw RuleValidationException("$name 必须是字符串")
        }
        return value.asString
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw RuleValidationException("$name 必须是字符串或 null")
        }
        return value.asString
    }

    private fun JsonObject.requiredInt(name: String): Int =
        requiredLong(name).also {
            if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw RuleValidationException("$name 超出整数范围")
        }.toInt()

    private fun JsonObject.requiredLong(name: String): Long {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw RuleValidationException("$name 必须是整数")
        }
        val text = value.asString
        if (!INTEGER_PATTERN.matches(text)) throw RuleValidationException("$name 必须是整数")
        return text.toLongOrNull() ?: throw RuleValidationException("$name 超出整数范围")
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw RuleValidationException("$name 必须是整数或 null")
        }
        val text = value.asString
        if (!INTEGER_PATTERN.matches(text)) throw RuleValidationException("$name 必须是整数")
        return text.toLongOrNull() ?: throw RuleValidationException("$name 超出整数范围")
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
            throw RuleValidationException("$name 必须是布尔值")
        }
        return value.asBoolean
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
            throw RuleValidationException("$name 必须是布尔值或 null")
        }
        return value.asBoolean
    }

    private fun JsonObject.requiredObject(name: String): JsonObject =
        get(name).requiredObject(name)

    private fun JsonObject.requiredArray(name: String): JsonArray {
        val value = get(name)
        if (value == null || !value.isJsonArray) throw RuleValidationException("$name 必须是数组")
        return value.asJsonArray
    }

    private fun JsonElement?.requiredObject(name: String): JsonObject {
        if (this == null || !isJsonObject) throw RuleValidationException("$name 必须是对象")
        return asJsonObject
    }

    private fun String.validatedToken(label: String, maxLength: Int): String {
        if (length !in 1..maxLength || !TOKEN_PATTERN.matches(this)) {
            throw RuleValidationException("$label 格式无效")
        }
        return this
    }

    private fun String.validatedText(label: String, maxLength: Int): String {
        if (isBlank() || length > maxLength || any { it.isISOControl() }) {
            throw RuleValidationException("$label 格式无效")
        }
        return this
    }

    companion object {
        const val MAX_BUNDLE_BYTES = 512 * 1024
        const val MAX_CLIPBOARD_INPUT_LENGTH = 32 * 1024
        const val MAX_NODE_MATCHES = 1_500
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val MAX_RULES_PER_BUNDLE = 32
        private const val MAX_SELECTORS_PER_GROUP = 24
        private const val MAX_CLICKABLE_PARENT_DEPTH = 8
        private const val MAX_REDIRECTS = 5
        private const val MAX_HOSTS = 32
        private const val MAX_PARAMETERS = 128
        private const val MAX_PREVIEW_SELECTORS = 8
        private const val MAX_PREVIEW_FALLBACK_REQUESTS = 3
        private const val MAX_ACCESS_FAILURE_RULES = 8
        private const val MAX_PREVIEW_KEY_LENGTH = 80
        private const val MAX_HEADERS = 16
        private const val MAX_FORM_PARAMETERS = 24
        private const val MAX_HEADER_NAME_LENGTH = 64
        private const val MAX_HEADER_VALUE_LENGTH = 512
        private const val MAX_TEMPLATE_LENGTH = 1_024
        private const val MAX_ID_LENGTH = 80
        private const val MAX_NAME_LENGTH = 80
        private const val MAX_SOURCE_LENGTH = 512
        private const val MAX_PACKAGE_LENGTH = 160
        private const val MAX_SELECTOR_TEXT = 256
        private const val MAX_REGEX_LENGTH = 256
        private const val MAX_HOST_LENGTH = 253
        private const val MAX_PARAMETER_LENGTH = 64
        private const val MIN_PANEL_TIMEOUT_MS = 1_000L
        private const val MAX_PANEL_TIMEOUT_MS = 10_000L
        private const val MIN_SETTLE_DELAY_MS = 100L
        private const val MAX_SETTLE_DELAY_MS = 2_000L
        private const val MIN_NETWORK_TIMEOUT_MS = 500
        private const val MAX_NETWORK_TIMEOUT_MS = 10_000
        private val PACKAGE_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
        private val HOST_PATTERN = Regex("""(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?""")
        private val TOKEN_PATTERN = Regex("""[A-Za-z0-9._-]+""")
        private val PARAMETER_PATTERN = Regex("""[A-Za-z0-9._~-]+""")
        private val PREVIEW_KEY_PATTERN = Regex("""[A-Za-z0-9._:-]+""")
        private val PREVIEW_JSON_PATH_PATTERN =
            Regex("""(?:[A-Za-z0-9_:-]+|\*)(?:\.(?:[A-Za-z0-9_:-]+|\*))*""")
        private val HEADER_NAME_PATTERN = Regex("""[A-Za-z0-9!#$%&'*+.^_`|~-]+""")
        private val INTEGER_PATTERN = Regex("""-?(0|[1-9][0-9]*)""")
        private val DANGEROUS_KEYS = listOf(
            "coordinate",
            "script",
            "intent",
            "repeat",
            "continuous",
            "x",
            "y",
        )
    }
}
