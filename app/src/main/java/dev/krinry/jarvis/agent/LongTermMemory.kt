package dev.krinry.jarvis.agent

import android.content.Context
import org.json.JSONArray

/**
 * P3F — Lightweight persistent long-term memory.
 *
 * Stores short, non-sensitive context locally.
 */
class LongTermMemory(context: Context) {

    companion object {
        private const val PREFS_NAME = "jarvis_long_term_memory"
        private const val KEY_MEMORIES = "memories"
        private const val MAX_MEMORIES = 30
        private const val MAX_ITEM_LENGTH = 240
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun add(memory: String) {
        val clean = normalize(memory)

        if (clean.isBlank()) return

        val memories = getAll().toMutableList()

        // Exact + normalized duplicate protection.
        if (memories.any { normalize(it) == clean }) {
            return
        }

        // Similar-memory protection.
        // Prevents multiple memories that contain almost the same information.
        if (memories.any { isSimilarMemory(it, clean) }) {
            return
        }

        memories.add(memory.trim().take(MAX_ITEM_LENGTH))

        while (memories.size > MAX_MEMORIES) {
            memories.removeAt(0)
        }

        save(memories)
    }

    @Synchronized
    fun getAll(): List<String> {
        val raw = prefs.getString(KEY_MEMORIES, null)
            ?: return emptyList()

        return try {
            val array = JSONArray(raw)

            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()

                    if (value.isNotBlank()) {
                        add(value)
                    }
                }
            }.distinctBy { normalize(it) }

        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getRecent(limit: Int = 5): List<String> {
        return getAll().takeLast(limit.coerceAtLeast(0))
    }

    @Synchronized
    fun clear() {
        prefs.edit()
            .remove(KEY_MEMORIES)
            .apply()
    }

    @Synchronized
    fun forget(text: String): Boolean {
        val query = normalize(text)

        if (query.isBlank()) return false

        val memories = getAll().toMutableList()
        val before = memories.size

        memories.removeAll {
            normalize(it).contains(query) ||
                query.contains(normalize(it))
        }

        if (memories.size != before) {
            save(memories)
            return true
        }

        return false
    }

    /**
     * Normalizes Bengali/English text enough to detect
     * obvious duplicates without changing the stored text.
     */
    private fun normalize(text: String): String {
        return text
            .trim()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Detects memories with strong word overlap.
     *
     * Example:
     * "আমি বাংলা ভাষা পছন্দ করি"
     * "বাংলা ভাষায় কথা বলতে পছন্দ করি"
     *
     * will be treated as potentially similar instead of
     * creating unlimited duplicate context.
     */
    private fun isSimilarMemory(
        existing: String,
        incoming: String
    ): Boolean {
        val a = memoryWords(existing)
        val b = memoryWords(incoming)

        if (a.isEmpty() || b.isEmpty()) {
            return false
        }

        val intersection = a.intersect(b)

        val smallerSize = minOf(a.size, b.size)

        if (smallerSize == 0) {
            return false
        }

        val overlap = intersection.size.toDouble() / smallerSize.toDouble()

        // Strong overlap.
        if (overlap >= 0.75) {
            return true
        }

        // For short memories, 2+ matching meaningful words
        // is enough to avoid obvious duplicates.
        return smallerSize <= 4 && intersection.size >= 2
    }

    private fun memoryWords(text: String): Set<String> {
        val stopWords = setOf(
            "আমি",
            "আমার",
            "আমাকে",
            "তুমি",
            "তোমার",
            "এটা",
            "ওটা",
            "যে",
            "এবং",
            "করি",
            "করতে",
            "হয়",
            "হবে",
            "আছে",
            "দিয়ে",
            "ভাষায়",
            "ভাষা",
            "the",
            "that",
            "this",
            "my",
            "i",
            "am",
            "use",
            "using",
            "please",
            "remember"
        )

        return normalize(text)
            .split(" ")
            .filter { it.length >= 3 }
            .filterNot { it in stopWords }
            .toSet()
    }

    private fun save(memories: List<String>) {
        val array = JSONArray()

        memories.forEach {
            array.put(it)
        }

        prefs.edit()
            .putString(KEY_MEMORIES, array.toString())
            .apply()
    }
}
