package com.vinz.music.ui.components

/**
 * A key for each of [list]'s rows in a lazy list, from its [id]: the id itself, and for the second, third… time the same
 * id comes, the id and that count ("id", "id#2"). A lazy list refuses a key twice ("Key … was already used", #330's
 * crash), and what YouTube or the listener's own lists hold can repeat. A row keeps its key when other rows come or go
 * (unlike one keyed by its place), so a list's animations and scroll position still follow it.
 */
fun <T> rowKeys(list: List<T>, id: (T) -> String): List<String> {
    if (list.size < 2) return list.map(id)
    val used = HashSet<String>(list.size * 2)
    return list.map { item ->
        val k = id(item)
        var key = k
        var n = 1
        while (!used.add(key)) key = "$k#${++n}"
        key
    }
}
