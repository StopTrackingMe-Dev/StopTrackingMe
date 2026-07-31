package app.stoptrackingme.rules

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import androidx.core.content.edit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

data class RuleCatalog(
    val installedRules: List<InstalledRule>,
    val loadErrors: List<String>,
    val subscriptions: List<String>,
)

sealed interface ActiveRuleResolution {
    data object NoRule : ActiveRuleResolution
    data class Active(val installed: InstalledRule) : ActiveRuleResolution
    data class Conflict(val candidates: List<InstalledRule>) : ActiveRuleResolution
    data class InvalidSelection(val candidates: List<InstalledRule>) : ActiveRuleResolution
}

object RuleSelection {
    fun compatible(
        packageName: String,
        versionCode: Long?,
        rules: List<InstalledRule>,
    ): List<InstalledRule> = rules.filter {
        val target = it.rule.target
        if (target.packageName != packageName) return@filter false
        if (versionCode == null) {
            target.minVersionCode == null && target.maxVersionCode == null
        } else {
            (target.minVersionCode == null || versionCode >= target.minVersionCode) &&
                (target.maxVersionCode == null || versionCode <= target.maxVersionCode)
        }
    }

    fun resolve(
        packageName: String,
        versionCode: Long?,
        rules: List<InstalledRule>,
        selectedKey: String?,
    ): ActiveRuleResolution {
        val candidates = compatible(packageName, versionCode, rules)
        if (candidates.isEmpty()) return ActiveRuleResolution.NoRule
        if (selectedKey != null) {
            val selected = candidates.singleOrNull { it.key == selectedKey }
            return if (selected != null) {
                ActiveRuleResolution.Active(selected)
            } else {
                ActiveRuleResolution.InvalidSelection(candidates)
            }
        }
        return if (candidates.size == 1) {
            ActiveRuleResolution.Active(candidates.single())
        } else {
            ActiveRuleResolution.Conflict(candidates)
        }
    }
}

class RuleRepository private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val parser = RuleParser()
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val rootDirectory = File(appContext.noBackupFilesDir, RULE_DIRECTORY)
    private val localDirectory = File(rootDirectory, LOCAL_DIRECTORY)
    private val remoteDirectory = File(rootDirectory, REMOTE_DIRECTORY)
    private val lock = Any()

    @Volatile
    private var catalog = RuleCatalog(emptyList(), emptyList(), emptyList())

    init {
        reload()
    }

    fun currentCatalog(): RuleCatalog = catalog

    fun reload(): RuleCatalog = synchronized(lock) {
        val installed = ArrayList<InstalledRule>()
        val errors = ArrayList<String>()

        loadBuiltInRules(installed, errors)
        loadLocalRules(installed, errors)
        val subscriptions = preferences.getStringSet(KEY_SUBSCRIPTIONS, emptySet())
            .orEmpty()
            .toList()
            .sorted()
        loadRemoteRules(subscriptions, installed, errors)

        catalog = RuleCatalog(
            installedRules = installed.sortedWith(
                compareBy<InstalledRule> { it.rule.target.packageName }
                    .thenBy { it.rule.displayName }
                    .thenBy { it.key },
            ),
            loadErrors = errors,
            subscriptions = subscriptions,
        )
        catalog
    }

    fun resolveActiveRule(packageName: String): ActiveRuleResolution {
        val selected = preferences.getString(selectionKey(packageName), null)
        return RuleSelection.resolve(
            packageName = packageName,
            versionCode = installedVersionCodeIfRequired(packageName),
            rules = catalog.installedRules,
            selectedKey = selected,
        )
    }

    fun findInstalledRule(key: String): InstalledRule? =
        catalog.installedRules.singleOrNull { it.key == key }

    fun selectedRuleKey(packageName: String): String? =
        preferences.getString(selectionKey(packageName), null)

    fun compatibleInstalledRules(packageName: String): List<InstalledRule> =
        RuleSelection.compatible(
            packageName = packageName,
            versionCode = installedVersionCodeIfRequired(packageName),
            rules = catalog.installedRules,
        )

    fun chooseActiveRule(packageName: String, key: String) {
        val matching = catalog.installedRules.filter { it.rule.target.packageName == packageName }
        if (matching.none { it.key == key }) throw IllegalArgumentException("所选规则不属于目标应用")
        preferences.edit { putString(selectionKey(packageName), key) }
    }

    fun importLocal(input: InputStream): RuleBundle = synchronized(lock) {
        val bytes = input.use(::readBounded)
        val fileName = "${sha256(bytes)}.json"
        val source = RuleSource(RuleSourceKind.LOCAL, fileName)
        val bundle = parser.parse(bytes, source)
        writeAtomically(File(localDirectory, fileName), bytes)
        reload()
        bundle
    }

    fun previewRemote(url: String, client: RuleSubscriptionClient = RuleSubscriptionClient()): RemoteRulePreview {
        val normalizedUrl = client.validateSubscriptionUrl(url)
        val bytes = client.fetch(normalizedUrl)
        val source = RuleSource(RuleSourceKind.REMOTE, normalizedUrl)
        val bundle = parser.parse(bytes, source)
        return RemoteRulePreview(normalizedUrl, bytes, bundle)
    }

    @Suppress("ApplySharedPref", "UseKtx")
    fun trustRemote(preview: RemoteRulePreview) = synchronized(lock) {
        val source = RuleSource(RuleSourceKind.REMOTE, preview.url)
        parser.parse(preview.bytes, source)
        writeAtomically(remoteFile(preview.url), preview.bytes)
        val updated = preferences.getStringSet(KEY_SUBSCRIPTIONS, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { add(preview.url) }
        if (!preferences.edit().putStringSet(KEY_SUBSCRIPTIONS, updated).commit()) {
            throw IllegalStateException("无法保存订阅配置")
        }
        reload()
    }

    fun refreshRemote(
        url: String,
        client: RuleSubscriptionClient = RuleSubscriptionClient(),
    ): RuleBundle = synchronized(lock) {
        if (url !in catalog.subscriptions) throw IllegalArgumentException("订阅不存在")
        val normalizedUrl = client.validateSubscriptionUrl(url)
        val bytes = client.fetch(normalizedUrl)
        val source = RuleSource(RuleSourceKind.REMOTE, normalizedUrl)
        val bundle = parser.parse(bytes, source)
        // AtomicFile keeps the previous complete file if writing fails.
        writeAtomically(remoteFile(normalizedUrl), bytes)
        reload()
        bundle
    }

    private fun loadBuiltInRules(
        destination: MutableList<InstalledRule>,
        errors: MutableList<String>,
    ) {
        val names = try {
            appContext.assets.list(BUILTIN_ASSET_DIRECTORY).orEmpty().filter { it.endsWith(".json") }
        } catch (error: Exception) {
            errors += "无法列出内置规则"
            return
        }
        names.forEach { name ->
            try {
                val reference = "$BUILTIN_ASSET_DIRECTORY/$name"
                val source = RuleSource(RuleSourceKind.BUILTIN, reference)
                val bytes = appContext.assets.open(reference).use(::readBounded)
                parser.parse(bytes, source).rules.forEach { rule ->
                    destination += InstalledRule("BUILTIN:${rule.id}", rule)
                }
            } catch (error: Exception) {
                errors += "内置规则 $name 无效：${safeMessage(error)}"
            }
        }
    }

    private fun loadLocalRules(
        destination: MutableList<InstalledRule>,
        errors: MutableList<String>,
    ) {
        localDirectory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                try {
                    val source = RuleSource(RuleSourceKind.LOCAL, file.name)
                    parser.parse(file.readBytesBounded(), source).rules.forEach { rule ->
                        destination += InstalledRule("LOCAL:${file.name}:${rule.id}", rule)
                    }
                } catch (error: Exception) {
                    errors += "本地规则 ${file.name} 无效：${safeMessage(error)}"
                }
            }
    }

    private fun loadRemoteRules(
        subscriptions: List<String>,
        destination: MutableList<InstalledRule>,
        errors: MutableList<String>,
    ) {
        subscriptions.forEach { url ->
            val file = remoteFile(url)
            if (!file.isFile) {
                errors += "订阅尚无可用缓存：${displayHost(url)}"
                return@forEach
            }
            try {
                val source = RuleSource(RuleSourceKind.REMOTE, url)
                parser.parse(file.readBytesBounded(), source).rules.forEach { rule ->
                    destination += InstalledRule("REMOTE:${sha256(url.toByteArray())}:${rule.id}", rule)
                }
            } catch (error: Exception) {
                errors += "订阅规则 ${displayHost(url)} 无效，已停止使用：${safeMessage(error)}"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(packageName: String): Long? = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            appContext.packageManager.getPackageInfo(packageName, 0)
        }
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun installedVersionCodeIfRequired(packageName: String): Long? {
        val hasVersionBound = catalog.installedRules.any { installed ->
            installed.rule.target.packageName == packageName &&
                (installed.rule.target.minVersionCode != null ||
                    installed.rule.target.maxVersionCode != null)
        }
        return if (hasVersionBound) installedVersionCode(packageName) else null
    }

    private fun File.readBytesBounded(): ByteArray = inputStream().use(::readBounded)

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > RuleParser.MAX_BUNDLE_BYTES) {
                throw RuleValidationException("规则文件超过大小限制")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun remoteFile(url: String): File =
        File(remoteDirectory, "${sha256(url.toByteArray())}.json")

    private fun selectionKey(packageName: String): String = "$KEY_ACTIVE_PREFIX$packageName"

    private fun displayHost(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull().orEmpty().ifBlank { "未知域名" }

    private fun safeMessage(error: Exception): String =
        error.message?.take(160)?.replace(Regex("""https?://\S+"""), "[URL]") ?: "未知错误"

    companion object {
        private const val PREFERENCES = "rule_repository"
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
        private const val KEY_ACTIVE_PREFIX = "active."
        private const val RULE_DIRECTORY = "rules"
        private const val LOCAL_DIRECTORY = "local"
        private const val REMOTE_DIRECTORY = "remote"
        private const val BUILTIN_ASSET_DIRECTORY = "rules"

        @Volatile
        private var instance: RuleRepository? = null

        fun get(context: Context): RuleRepository =
            instance ?: synchronized(this) {
                instance ?: RuleRepository(context).also { instance = it }
            }

        private fun sha256(value: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}

data class RemoteRulePreview(
    val url: String,
    val bytes: ByteArray,
    val bundle: RuleBundle,
)
