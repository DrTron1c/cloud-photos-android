package io.github.drtron1c.cloudphotos.ai

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * CLIP's byte-level BPE tokenizer, read from a Hugging Face `tokenizer.json`.
 * Turns "red hat" into the 77 token ids the text model expects.
 */
class ClipTokenizer(
    private val vocab: Map<String, Int>,
    merges: List<Pair<String, String>>,
    private val startId: Int,
    private val endId: Int,
    private val padId: Int,
    val contextLength: Int = 77,
) {
    private val ranks: Map<Pair<String, String>, Int> = merges.withIndex().associate { it.value to it.index }
    private val cache = HashMap<String, List<String>>()

    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>()
        ids += startId
        for (word in words(text)) {
            val mapped = word.toByteArray(Charsets.UTF_8).joinToString("") { BYTE_TO_CHAR[it.toInt() and 0xff].toString() }
            for (piece in bpe(mapped)) {
                vocab[piece]?.let { ids += it }
            }
        }
        val body = ids.take(contextLength - 1).toMutableList()
        body += endId
        val out = IntArray(contextLength) { padId }
        body.forEachIndexed { i, id -> out[i] = id }
        return out
    }

    /** Lower-cased words, numbers and symbol runs, like CLIP's pre-tokenizer. */
    internal fun words(text: String): List<String> {
        val clean = Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim().lowercase()
        return PATTERN.findAll(clean).map { it.value }.toList()
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()
        var parts = token.map { it.toString() }.toMutableList()
        parts[parts.lastIndex] = parts.last() + "</w>"
        while (parts.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val r = ranks[parts[i] to parts[i + 1]] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    best = i
                }
            }
            if (best < 0) break
            val a = parts[best]
            val b = parts[best + 1]
            val merged = ArrayList<String>(parts.size)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == a && parts[i + 1] == b) {
                    merged += a + b
                    i += 2
                } else {
                    merged += parts[i]
                    i++
                }
            }
            parts = merged
        }
        if (cache.size < 10_000) cache[token] = parts
        return parts
    }

    companion object {
        private val PATTERN = Regex(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+"
        )

        /** GPT-2 / CLIP byte → printable character table. */
        private val BYTE_TO_CHAR: CharArray = run {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code)
            bs += ('¡'.code..'¬'.code)
            bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n
                    n++
                }
            }
            val table = CharArray(256)
            for (i in bs.indices) table[bs[i]] = cs[i].toChar()
            table
        }

        /** Reads a Hugging Face fast-tokenizer file (`tokenizer.json`) for a CLIP model. */
        fun fromTokenizerJson(text: String): ClipTokenizer {
            val root = JSONObject(text)
            val model = root.getJSONObject("model")
            val vocabJson = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabJson.length() * 2)
            for (k in vocabJson.keys()) vocab[k] = vocabJson.getInt(k)
            val mergesJson = model.getJSONArray("merges")
            val merges = ArrayList<Pair<String, String>>(mergesJson.length())
            for (i in 0 until mergesJson.length()) {
                when (val m = mergesJson.get(i)) {
                    is String -> {
                        val sp = m.indexOf(' ')
                        if (sp > 0) merges += m.substring(0, sp) to m.substring(sp + 1)
                    }
                    is JSONArray -> merges += m.getString(0) to m.getString(1)
                }
            }
            fun special(name: String, fallback: Int): Int {
                vocab[name]?.let { return it }
                val added = root.optJSONArray("added_tokens") ?: return fallback
                for (i in 0 until added.length()) {
                    val t = added.getJSONObject(i)
                    if (t.optString("content") == name) return t.optInt("id", fallback)
                }
                return fallback
            }
            val start = special("<|startoftext|>", 49406)
            val end = special("<|endoftext|>", 49407)
            val pad = root.optJSONObject("padding")?.optInt("pad_id", end) ?: end
            return ClipTokenizer(vocab, merges, start, end, pad)
        }
    }
}
