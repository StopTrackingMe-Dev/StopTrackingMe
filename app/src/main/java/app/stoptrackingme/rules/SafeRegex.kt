package app.stoptrackingme.rules

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import java.util.LinkedHashMap

/**
 * RE2 deliberately omits backreferences and look-around, so matches remain linear in input size.
 */
object SafeRegex {
    private val cache = object : LinkedHashMap<String, Pattern>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>?): Boolean =
            size > MAX_CACHED_PATTERNS
    }

    fun validate(expression: String) {
        compile(expression)
    }

    fun matches(expression: String, input: CharSequence): Boolean =
        compile(expression).matcher(input).find()

    fun findRanges(expression: String, input: CharSequence, limit: Int = 32): List<IntRange> {
        val matcher = compile(expression).matcher(input)
        val ranges = ArrayList<IntRange>()
        while (ranges.size < limit && matcher.find()) {
            ranges += matcher.start() until matcher.end()
        }
        return ranges
    }

    private fun compile(expression: String): Pattern = synchronized(cache) {
        cache[expression] ?: run {
            try {
                Pattern.compile(expression, Pattern.CASE_INSENSITIVE)
            } catch (error: PatternSyntaxException) {
                throw RuleValidationException("非法或不受支持的正则表达式", error)
            }
        }.also {
            cache[expression] = it
        }
    }

    private const val MAX_CACHED_PATTERNS = 512
}

open class RuleValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
