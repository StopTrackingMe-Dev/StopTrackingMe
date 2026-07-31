package app.stoptrackingme.session

import app.stoptrackingme.rules.CleanResult
import java.util.UUID

data class ShareSession(
    val id: String,
    val ruleKey: String,
    val sourcePackage: String,
    val createdAtMillis: Long,
    val sourceText: String? = null,
    val result: CleanResult? = null,
)

/**
 * Deliberately process-local: share text and URLs never enter preferences, files, saved state, or logs.
 */
object ShareSessionStore {
    private val lock = Any()
    private var session: ShareSession? = null
    private var worker: Thread? = null

    fun begin(ruleKey: String, sourcePackage: String, nowMillis: Long = System.currentTimeMillis()): String =
        synchronized(lock) {
            worker?.interrupt()
            worker = null
            val id = UUID.randomUUID().toString()
            session = ShareSession(id, ruleKey, sourcePackage, nowMillis)
            id
        }

    fun putSourceText(id: String, sourceText: String): Boolean = synchronized(lock) {
        val current = session
        if (current?.id != id) return@synchronized false
        session = current.copy(sourceText = sourceText)
        true
    }

    fun putResult(id: String, result: CleanResult): Boolean = synchronized(lock) {
        val current = session
        if (current?.id != id) return@synchronized false
        session = current.copy(sourceText = result.sourceText, result = result)
        true
    }

    fun attachWorker(id: String, thread: Thread): Boolean = synchronized(lock) {
        if (session?.id != id) return@synchronized false
        worker?.interrupt()
        worker = thread
        true
    }

    fun detachWorker(id: String, thread: Thread) = synchronized(lock) {
        if (session?.id == id && worker === thread) worker = null
    }

    fun get(id: String): ShareSession? = synchronized(lock) {
        session?.takeIf { it.id == id }
    }

    fun clear(id: String? = null) = synchronized(lock) {
        if (id == null || session?.id == id) {
            worker?.interrupt()
            worker = null
            session = null
        }
    }
}
