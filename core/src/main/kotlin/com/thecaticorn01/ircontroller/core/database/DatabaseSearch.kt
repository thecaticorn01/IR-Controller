package com.thecaticorn01.ircontroller.core.database

import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.searchKey

/** Filters applied to a database search. */
data class DatabaseQuery(
    val text: String = "",
    /** Restrict to these sources; empty means all. */
    val sourceIds: Set<String> = emptySet(),
    val category: DeviceCategory? = null,
    /** Exact brand (matched case- and diacritic-insensitively). */
    val brand: String? = null,
    /** Only hand-curated files (excludes bulk-converted collections). */
    val curatedOnly: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isBlank() && category == null && brand == null

    val hasFilters: Boolean
        get() = text.isNotEmpty() || category != null || brand != null || curatedOnly || sourceIds.isNotEmpty()
}

/** A facet value with its number of matching entries. */
data class FacetCount<T>(val value: T, val count: Int)

/** Ranked full-text search over database entries. */
object DatabaseSearch {

    fun search(entries: List<DatabaseEntry>, query: DatabaseQuery, limit: Int = 500): List<DatabaseEntry> {
        val tokens = tokens(query.text)
        val brandKey = query.brand?.searchKey
        val scored = ArrayList<Pair<DatabaseEntry, Int>>()
        for (entry in entries) {
            if (!matchesFilters(entry, query, brandKey)) continue
            val score = score(entry, tokens) ?: continue
            scored.add(entry to score)
        }
        scored.sortWith { lhs, rhs ->
            val (l, ls) = lhs
            val (r, rs) = rhs
            when {
                ls != rs -> rs.compareTo(ls)
                l.brandKey != r.brandKey -> when {
                    // Entries without a brand sort after named brands.
                    l.brandKey.isEmpty() -> 1
                    r.brandKey.isEmpty() -> -1
                    else -> l.brandKey.compareTo(r.brandKey)
                }
                else -> NaturalOrder.compare(l.model, r.model)
            }
        }
        return scored.asSequence().take(limit).map { it.first }.toList()
    }

    /** Counts of brands among entries matching everything except the brand filter. */
    fun brandFacets(entries: List<DatabaseEntry>, query: DatabaseQuery): List<FacetCount<String>> {
        val q = query.copy(brand = null)
        val tokens = tokens(q.text)
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (entry in entries) {
            if (entry.brand.isEmpty() || !matchesFilters(entry, q, null) || score(entry, tokens) == null) continue
            val existing = counts[entry.brandKey]
            counts[entry.brandKey] = (existing?.first ?: entry.brand) to ((existing?.second ?: 0) + 1)
        }
        return counts.values.map { FacetCount(it.first, it.second) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.value })
    }

    /** Counts of categories among entries matching everything except the category filter. */
    fun categoryFacets(entries: List<DatabaseEntry>, query: DatabaseQuery): List<FacetCount<DeviceCategory>> {
        val q = query.copy(category = null)
        val tokens = tokens(q.text)
        val brandKey = q.brand?.searchKey
        val counts = HashMap<DeviceCategory, Int>()
        for (entry in entries) {
            if (!matchesFilters(entry, q, brandKey) || score(entry, tokens) == null) continue
            counts[entry.category] = (counts[entry.category] ?: 0) + 1
        }
        return DeviceCategory.entries.mapNotNull { category -> counts[category]?.let { FacetCount(category, it) } }
    }

    private fun tokens(text: String): List<String> = text.searchKey.split(' ').filter { it.isNotEmpty() }

    private fun matchesFilters(entry: DatabaseEntry, query: DatabaseQuery, brandKey: String?): Boolean {
        if (query.sourceIds.isNotEmpty() && entry.sourceId !in query.sourceIds) return false
        if (query.category != null && entry.category != query.category) return false
        if (brandKey != null && entry.brandKey != brandKey) return false
        if (query.curatedOnly && !entry.isCurated) return false
        return true
    }

    /** Returns null when the entry does not contain every token. */
    fun score(entry: DatabaseEntry, tokens: List<String>): Int? {
        var score = if (entry.isCurated) 15 else 0
        if (tokens.isEmpty()) return score
        val key = entry.searchKey
        val modelKey = entry.modelKey
        for (token in tokens) {
            if (!key.contains(token) && !entry.compactKey.contains(token)) return null
            if (entry.brandKey == token) score += 100
            else if (entry.brandKey.startsWith(token)) score += 50
            if (key.contains(" $token")) score += 10
            if (modelKey == token) {
                score += 80
            } else if (modelKey.contains(token)) {
                score += 20
                // Compact model numbers: "un55" should match "UN55NU7100".
                if (modelKey.firstOrNull() == token.firstOrNull() && modelKey.replace(" ", "").startsWith(token)) score += 15
            }
        }
        return score
    }
}

/**
 * Case-insensitive comparison that orders embedded numbers numerically
 * ("Model 9" < "Model 10"), the way people expect model numbers to sort.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
