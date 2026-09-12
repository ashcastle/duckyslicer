package com.ashcastle.duckyslicer

import java.util.Locale

/** Keeps catalog normalization outside the per-keystroke filtering path. */
internal class ProfileSearchIndex<T>(
    entries: List<T>,
    searchTerms: (T) -> List<String>,
) {
    private val indexed = entries.map { entry ->
        entry to searchTerms(entry).map { it.lowercase(Locale.ROOT) }
    }

    fun matching(query: String): List<T> {
        val tokens = query.lowercase(Locale.ROOT).split(WHITESPACE).filter(String::isNotBlank)
        return indexed.mapNotNull { (entry, fields) ->
            entry.takeIf { tokens.all { token -> fields.any { token in it } } }
        }
    }
}

private val WHITESPACE = Regex("\\s+")
