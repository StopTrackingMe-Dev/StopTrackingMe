package app.stoptrackingme.rules

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal

/**
 * Gson's tree parser accepts duplicate object keys. Rule files use this stricter reader so a key
 * cannot be interpreted differently by preview, validation, and execution code.
 */
object StrictJsonParser {
    fun parse(json: String): JsonElement =
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            val result = readElement(reader, depth = 0)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw RuleValidationException("规则 JSON 含有多余内容")
            }
            result
        }

    private fun readElement(reader: JsonReader, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) throw RuleValidationException("规则 JSON 嵌套过深")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(reader, depth)
            JsonToken.BEGIN_ARRAY -> readArray(reader, depth)
            JsonToken.STRING -> JsonPrimitive(reader.nextString().validatedJsonString())
            JsonToken.NUMBER -> {
                val raw = reader.nextString()
                if (raw.length > MAX_NUMBER_LENGTH) {
                    throw RuleValidationException("规则 JSON 数字过长")
                }
                try {
                    JsonPrimitive(BigDecimal(raw))
                } catch (error: NumberFormatException) {
                    throw RuleValidationException("规则 JSON 数字无效", error)
                }
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }
            else -> throw RuleValidationException("规则 JSON 结构无效")
        }
    }

    private fun readObject(reader: JsonReader, depth: Int): JsonObject {
        reader.beginObject()
        val result = JsonObject()
        val names = HashSet<String>()
        var count = 0
        while (reader.hasNext()) {
            count += 1
            if (count > MAX_OBJECT_MEMBERS) {
                throw RuleValidationException("规则 JSON 对象字段过多")
            }
            val name = reader.nextName().validatedJsonString()
            if (!names.add(name)) throw RuleValidationException("规则 JSON 字段重复：$name")
            result.add(name, readElement(reader, depth + 1))
        }
        reader.endObject()
        return result
    }

    private fun readArray(reader: JsonReader, depth: Int): JsonArray {
        reader.beginArray()
        val result = JsonArray()
        var count = 0
        while (reader.hasNext()) {
            count += 1
            if (count > MAX_ARRAY_ELEMENTS) {
                throw RuleValidationException("规则 JSON 数组元素过多")
            }
            result.add(readElement(reader, depth + 1))
        }
        reader.endArray()
        return result
    }

    private fun String.validatedJsonString(): String {
        if (length > MAX_RAW_STRING_LENGTH) throw RuleValidationException("规则 JSON 字符串过长")
        return this
    }

    private const val MAX_DEPTH = 32
    private const val MAX_OBJECT_MEMBERS = 128
    private const val MAX_ARRAY_ELEMENTS = 512
    private const val MAX_RAW_STRING_LENGTH = 64 * 1024
    private const val MAX_NUMBER_LENGTH = 32
}
