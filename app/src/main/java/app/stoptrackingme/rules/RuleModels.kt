package app.stoptrackingme.rules

enum class RuleSourceKind {
    BUILTIN,
    LOCAL,
    REMOTE,
}

data class RuleSource(
    val kind: RuleSourceKind,
    val reference: String,
)

data class RuleBundle(
    val schemaVersion: Int,
    val rules: List<AppRule>,
)

data class AppTarget(
    val packageName: String,
    val minVersionCode: Long?,
    val maxVersionCode: Long?,
)

/**
 * Fields within one selector are conjunctive. The two label regexes are the one exception:
 * when both are present, a match in either visible text or content description is sufficient.
 */
data class NodeSelector(
    val resourceId: String?,
    val textRegex: String?,
    val descriptionRegex: String?,
    val className: String?,
    val clickable: Boolean?,
)

data class ClipboardExtractionRule(
    val urlRegex: String,
    val maxInputLength: Int,
)

enum class CopyTriggerMode {
    AUTOMATIC,
    USER_CONFIRMATION,
}

data class RedirectPolicy(
    val shortLinkHosts: Set<String>,
    val allowedFinalHosts: Set<String>,
    val maxRedirects: Int,
    val requireHttps: Boolean,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
)

data class CleaningPolicy(
    val removeExact: Set<String>,
    val removePrefixes: Set<String>,
    val forceKeep: Set<String>,
)

enum class PreviewSelectorType {
    META_PROPERTY,
    META_NAME,
    HTML_TITLE,
}

data class PreviewFieldSelector(
    val type: PreviewSelectorType,
    val key: String?,
)

data class SharePreviewRule(
    val titleSelectors: List<PreviewFieldSelector>,
    val descriptionSelectors: List<PreviewFieldSelector>,
    val imageSelectors: List<PreviewFieldSelector>,
    val imageAllowedHosts: Set<String>,
)

data class AppRule(
    val id: String,
    val version: Int,
    val displayName: String,
    val source: RuleSource,
    val target: AppTarget,
    val shareTriggerSelectors: List<NodeSelector>,
    /** Every selector in this list must be found before fallback automation can start. */
    val sharePanelFingerprint: List<NodeSelector>,
    /** Optional visible labels whose scrollable ancestor is advanced once to reveal copy-link. */
    val copyLinkScrollAnchorSelectors: List<NodeSelector>,
    val copyLinkSelectors: List<NodeSelector>,
    val copyTriggerMode: CopyTriggerMode,
    val maxClickableParentDepth: Int,
    val sharePanelTimeoutMs: Long,
    val copySettleDelayMs: Long,
    val clipboardExtraction: ClipboardExtractionRule,
    val redirectPolicy: RedirectPolicy,
    val sharePreview: SharePreviewRule?,
    val cleaningPolicy: CleaningPolicy,
)

data class InstalledRule(
    val key: String,
    val rule: AppRule,
)

enum class ProcessingFailure {
    CLIPBOARD_EMPTY,
    URL_NOT_FOUND,
    INVALID_URL,
    UNSUPPORTED_SCHEME,
    DISALLOWED_HOST,
    REDIRECT_FAILED,
    NETWORK_TARGET_BLOCKED,
}

data class CleanResult(
    val sourceText: String,
    val originalUrl: String?,
    val expandedUrl: String?,
    val cleanedUrl: String?,
    val removedParameters: List<String>,
    val warnings: List<String>,
    val urlCount: Int,
    val failure: ProcessingFailure?,
    val failureMessage: String?,
    val retryable: Boolean,
) {
    val isSuccess: Boolean
        get() = cleanedUrl != null && failure == null
}
