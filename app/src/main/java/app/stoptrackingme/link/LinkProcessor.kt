package app.stoptrackingme.link

import app.stoptrackingme.rules.AppRule
import app.stoptrackingme.rules.CleanResult
import app.stoptrackingme.rules.ProcessingFailure
import java.net.URI
import java.util.Locale

enum class LinkProcessingStage {
    EXTRACT,
    RESOLVE,
    CLEAN,
}

class LinkProcessor(
    private val redirectResolver: RedirectResolver = HttpRedirectResolver(),
) {
    fun process(
        sourceText: String,
        rule: AppRule,
        onStage: (LinkProcessingStage) -> Unit = {},
    ): CleanResult {
        onStage(LinkProcessingStage.EXTRACT)
        if (sourceText.isBlank()) {
            return failure(sourceText, ProcessingFailure.CLIPBOARD_EMPTY, "剪贴板为空")
        }
        if (sourceText.length > rule.clipboardExtraction.maxInputLength) {
            return failure(sourceText, ProcessingFailure.URL_NOT_FOUND, "剪贴板内容超过规则允许的长度")
        }
        val extracted = UrlExtractor.extract(sourceText, rule.clipboardExtraction)
            ?: return failure(sourceText, ProcessingFailure.URL_NOT_FOUND, "没有找到 HTTP/HTTPS URL")
        val originalUri = parseWebUri(extracted.value)
            ?: return failure(
                sourceText,
                ProcessingFailure.INVALID_URL,
                "提取到的 URL 格式无效",
                originalUrl = extracted.value,
                urlCount = extracted.totalMatches,
            )

        val warnings = ArrayList<String>()
        if (extracted.totalMatches > 1) {
            warnings += "检测到多个 URL；保留原文时只替换第一个已处理 URL。"
        }
        onStage(LinkProcessingStage.RESOLVE)
        val policy = rule.redirectPolicy
        val isShortLink = HostPolicy.isAllowed(originalUri.host, policy.shortLinkHosts)
        val resolvedUrl = if (isShortLink) {
            when (val outcome = redirectResolver.resolve(extracted.value, policy)) {
                is RedirectOutcome.Success -> outcome.finalUrl
                is RedirectOutcome.Failure -> {
                    return CleanResult(
                        sourceText = sourceText,
                        originalUrl = extracted.value,
                        expandedUrl = null,
                        cleanedUrl = null,
                        removedParameters = emptyList(),
                        warnings = warnings,
                        urlCount = extracted.totalMatches,
                        failure = if (outcome.blockedTarget) {
                            ProcessingFailure.NETWORK_TARGET_BLOCKED
                        } else {
                            ProcessingFailure.REDIRECT_FAILED
                        },
                        failureMessage = outcome.message,
                        retryable = true,
                    )
                }
            }
        } else {
            extracted.value
        }

        var expandedUri = parseWebUri(resolvedUrl)
            ?: return failure(
                sourceText,
                ProcessingFailure.INVALID_URL,
                "展开后的 URL 格式无效",
                originalUrl = extracted.value,
                expandedUrl = resolvedUrl,
                urlCount = extracted.totalMatches,
                warnings = warnings,
            )
        val expandedUrl = if (AccessFailureUrl.matches(expandedUri, policy)) {
            expandedUri = AccessFailureUrl.recoverTarget(expandedUri, policy)
                ?: return failure(
                    sourceText,
                    ProcessingFailure.REDIRECT_FAILED,
                    "链接跳转到访问限制页面，且无法恢复原始地址",
                    originalUrl = extracted.value,
                    expandedUrl = resolvedUrl,
                    urlCount = extracted.totalMatches,
                    warnings = warnings,
                )
            warnings += "检测到访问限制页面；已恢复原始内容地址。"
            expandedUri.toASCIIString()
        } else {
            resolvedUrl
        }
        if (!HostPolicy.isAllowed(expandedUri.host, policy.allowedFinalHosts)) {
            return failure(
                sourceText,
                ProcessingFailure.DISALLOWED_HOST,
                "最终地址不属于规则允许的域名",
                originalUrl = extracted.value,
                expandedUrl = expandedUrl,
                urlCount = extracted.totalMatches,
                warnings = warnings,
            )
        }

        onStage(LinkProcessingStage.CLEAN)
        val cleaned = try {
            EncodedUrlCleaner.clean(expandedUrl, rule.cleaningPolicy)
        } catch (_: Exception) {
            return failure(
                sourceText,
                ProcessingFailure.INVALID_URL,
                "无法安全重建 URL",
                originalUrl = extracted.value,
                expandedUrl = expandedUrl,
                urlCount = extracted.totalMatches,
                warnings = warnings,
            )
        }
        if (cleaned.removedParameters.isEmpty()) warnings += "未发现规则中列出的追踪参数。"
        return CleanResult(
            sourceText = sourceText,
            originalUrl = extracted.value,
            expandedUrl = expandedUrl,
            cleanedUrl = cleaned.cleanedUrl,
            removedParameters = cleaned.removedParameters,
            warnings = warnings,
            urlCount = extracted.totalMatches,
            failure = null,
            failureMessage = null,
            retryable = false,
        )
    }

    private fun parseWebUri(value: String): URI? = try {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme == "http" || scheme == "https") &&
            uri.host != null &&
            uri.userInfo == null
        ) {
            uri
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun failure(
        sourceText: String,
        failure: ProcessingFailure,
        message: String,
        originalUrl: String? = null,
        expandedUrl: String? = null,
        urlCount: Int = 0,
        warnings: List<String> = emptyList(),
    ) = CleanResult(
        sourceText = sourceText,
        originalUrl = originalUrl,
        expandedUrl = expandedUrl,
        cleanedUrl = null,
        removedParameters = emptyList(),
        warnings = warnings,
        urlCount = urlCount,
        failure = failure,
        failureMessage = message,
        retryable = false,
    )
}

object ShareTextBuilder {
    fun build(result: CleanResult, preserveOriginalText: Boolean, extractionRule: app.stoptrackingme.rules.ClipboardExtractionRule): String? {
        val cleanedUrl = result.cleanedUrl ?: return null
        if (!preserveOriginalText) return cleanedUrl
        val extracted = UrlExtractor.extract(result.sourceText, extractionRule) ?: return null
        return buildString(result.sourceText.length - extracted.value.length + cleanedUrl.length) {
            append(result.sourceText, 0, extracted.range.first)
            append(cleanedUrl)
            append(result.sourceText, extracted.range.last + 1, result.sourceText.length)
        }
    }
}
