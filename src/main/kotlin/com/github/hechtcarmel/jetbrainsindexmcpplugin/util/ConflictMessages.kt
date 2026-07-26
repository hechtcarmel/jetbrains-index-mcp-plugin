package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.util.text.StringUtil

/**
 * Refactoring processors build conflict messages for the IDE's HTML ConflictsDialog —
 * `StringUtil.htmlEmphasize` wraps element names in `<b><code>…</code></b>` and bundle
 * templates XML-escape special characters. MCP clients receive plain text, so the markup
 * must be stripped and the entities decoded before the message goes on the wire.
 */
object ConflictMessages {

    fun sanitize(message: String): String =
        StringUtil.unescapeXmlEntities(StringUtil.removeHtmlTags(message)).trim()

    fun sanitizeAll(messages: Collection<String>): List<String> =
        messages.map(::sanitize).filter { it.isNotEmpty() }.distinct()
}
