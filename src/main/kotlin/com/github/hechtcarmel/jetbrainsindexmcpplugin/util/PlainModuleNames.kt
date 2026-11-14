package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import java.util.Locale

/** Portable module file names, also unique on case-insensitive filesystems. */
internal object PlainModuleNames {
    fun unique(base: String, parent: String, unavailable: (String) -> Boolean): String {
        val simple = safe(base)
        if (!unavailable(simple)) return simple
        val qualified = "${safe(parent)}-$simple"
        var candidate = qualified
        var index = 2
        while (unavailable(candidate)) candidate = "$qualified-${index++}"
        return candidate
    }

    private fun safe(value: String): String {
        val name = value.replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_")
            .trim().trimEnd('.').ifBlank { "module" }
        val stem = name.substringBefore('.').uppercase(Locale.ROOT)
        return if (stem in setOf("CON", "PRN", "AUX", "NUL") ||
            stem.matches(Regex("(?:COM|LPT)[1-9]"))) "module-$name" else name
    }
}
