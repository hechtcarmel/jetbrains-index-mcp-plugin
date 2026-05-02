package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallHierarchyData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createMatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createNameFilter
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ImplementationData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.UsageLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex

/**
 * Lightweight Rider fallback for C#/F# tools whose IntelliJ frontend PSI does not
 * expose full ReSharper semantics. This is intentionally conservative: semantic
 * protocol handlers are preferred, and these helpers only recover useful results
 * when frontend indexes return empty or resolve to namespace/package directories.
 */
object DotNetTextSearchSupport {
    private val DOTNET_EXTENSIONS = setOf("cs", "csx", "fs", "fsi", "fsx")
    private val TYPE_DECLARATION_REGEX = Regex(
        """^\s*(?:(?:public|private|protected|internal|abstract|sealed|static|partial|unsafe|new|readonly|required)\s+)*(class|interface|struct|record|enum)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*[:(]\s*([^({]+))?"""
    )
    private val FSHARP_TYPE_DECLARATION_REGEX = Regex(
        """^\s*(type|module)\s+([A-Za-z_][A-Za-z0-9_'.]*)\b"""
    )
    private val NAMESPACE_REGEX = Regex("""^\s*namespace\s+([A-Za-z_][A-Za-z0-9_.]*)""")
    private val CSHARP_METHOD_DECLARATION_REGEX = Regex(
        """^\s*(?:(?:public|private|protected|internal|static|virtual|override|abstract|async|sealed|partial|extern|new|unsafe)\s+)*(?:[A-Za-z_][A-Za-z0-9_<>,?\[\].]*\s+)+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*(?:\{|=>|$)"""
    )
    private val CSHARP_INVOCATION_REGEX = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val NON_CALL_IDENTIFIERS = setOf(
        "if", "for", "foreach", "while", "switch", "catch", "using", "lock",
        "return", "new", "nameof", "typeof", "sizeof", "default", "base", "this"
    )

    fun isDotNetFile(file: VirtualFile?): Boolean =
        file?.extension?.lowercase() in DOTNET_EXTENSIONS

    fun isDotNetElement(element: PsiElement): Boolean =
        isDotNetFile(element.containingFile?.virtualFile)

    fun wordAt(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return null
        if (element.textOffset !in 0..document.textLength) return null

        val text = document.charsSequence
        var start = element.textOffset.coerceAtMost(text.length - 1)
        var end = start
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        while (end < text.length && isIdentifierPart(text[end])) end++
        if (start >= end) return null
        return text.subSequence(start, end).toString().takeIf { it.firstOrNull()?.let(::isIdentifierStart) == true }
    }

    fun searchTypes(
        project: Project,
        query: String,
        scope: BuiltInSearchScope,
        matchMode: String,
        languageFilter: String?,
        limit: Int
    ): List<SymbolMatch> {
        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        val matcher = createMatcher(query, matchMode)
        val nameFilter = createNameFilter(query, matchMode, matcher)
        val results = mutableListOf<SymbolMatch>()
        val seen = mutableSetOf<String>()

        for (file in dotNetFiles(project, searchScope)) {
            if (results.size >= limit) break
            val language = languageFor(file)
            if (languageFilter != null && !language.equals(languageFilter, ignoreCase = true)) continue
            val declarations = typeDeclarations(project, file)
            for (decl in declarations) {
                if (!nameFilter(decl.name)) continue
                val key = "${decl.file}:${decl.line}:${decl.column}:${decl.name}"
                if (seen.add(key)) {
                    results.add(decl.toSymbolMatch(language))
                    if (results.size >= limit) break
                }
            }
        }

        return results.sortedByDescending { matcher.matchingDegree(it.name) }
    }

    fun findDefinition(project: Project, symbolName: String, scope: BuiltInSearchScope): DefinitionResult? {
        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        for (file in dotNetFiles(project, searchScope)) {
            val document = PsiDocumentManager.getInstance(project).getDocument(
                PsiManager.getInstance(project).findFile(file) ?: continue
            ) ?: continue
            val lines = document.text.lines()

            lines.forEachIndexed { index, line ->
                val typeMatch = TYPE_DECLARATION_REGEX.find(line) ?: FSHARP_TYPE_DECLARATION_REGEX.find(line)
                if (typeMatch?.groupValues?.getOrNull(2)?.substringBefore("'") == symbolName) {
                    return definitionResult(project, file, document, index, line.indexOf(symbolName), symbolName)
                }
            }

            val declarationRegex = Regex(
                """\b(?:public|private|protected|internal|static|virtual|override|abstract|async|sealed|readonly|partial|required|\s)+[A-Za-z_][A-Za-z0-9_<>,?\[\]\s.]*\s+$symbolName\s*(?:\(|\{|=|;)"""
            )
            lines.forEachIndexed { index, line ->
                val match = declarationRegex.find(line)
                if (match != null) {
                    return definitionResult(project, file, document, index, line.indexOf(symbolName), symbolName)
                }
            }
        }
        return null
    }

    fun findWordUsages(
        project: Project,
        word: String,
        scope: BuiltInSearchScope,
        limit: Int
    ): List<UsageLocation> {
        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        val wordRegex = Regex("""\b${Regex.escape(word)}\b""")
        val usages = mutableListOf<UsageLocation>()

        for (file in dotNetFiles(project, searchScope)) {
            if (usages.size >= limit) break
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: continue
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: continue
            val relativePath = ProjectUtils.getToolFilePath(project, file)

            document.text.lines().forEachIndexed { index, line ->
                if (usages.size >= limit) return@forEachIndexed
                for (match in wordRegex.findAll(line)) {
                    usages.add(
                        UsageLocation(
                            file = relativePath,
                            line = index + 1,
                            column = match.range.first + 1,
                            context = line.trim(),
                            type = classifyTextUsage(line, match.range.last + 1),
                            astPath = emptyList()
                        )
                    )
                    if (usages.size >= limit) break
                }
            }
        }

        return usages.distinctBy { "${it.file}:${it.line}:${it.column}" }
    }

    fun findImplementations(
        project: Project,
        typeName: String,
        scope: BuiltInSearchScope,
        language: String,
        limit: Int
    ): List<ImplementationData> {
        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        val results = mutableListOf<ImplementationData>()
        val seen = mutableSetOf<String>()
        for (file in dotNetFiles(project, searchScope)) {
            if (results.size >= limit) break
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: continue
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: continue
            document.text.lines().forEachIndexed { index, line ->
                if (results.size >= limit) return@forEachIndexed
                val match = TYPE_DECLARATION_REGEX.find(line) ?: return@forEachIndexed
                val kind = match.groupValues[1].uppercase()
                if (kind == "INTERFACE" || kind == "ENUM") return@forEachIndexed
                val inheritedTypes = match.groupValues.getOrNull(3).orEmpty()
                if (!containsTypeName(inheritedTypes, typeName)) return@forEachIndexed
                val name = match.groupValues[2]
                val key = "${file.path}:$index:$name"
                if (seen.add(key)) {
                    results.add(
                        ImplementationData(
                            name = name,
                            file = ProjectUtils.getToolFilePath(project, file),
                            line = index + 1,
                            column = line.indexOf(name).coerceAtLeast(0) + 1,
                            kind = kind.lowercase(),
                            language = language
                        )
                    )
                }
            }
        }
        return results
    }

    fun findCallHierarchy(
        project: Project,
        element: PsiElement,
        direction: String,
        scope: BuiltInSearchScope,
        language: String,
        limit: Int
    ): CallHierarchyData? {
        val rootMethod = methodAt(project, element) ?: return null
        val calls = if (direction == "callers") {
            findCallers(project, rootMethod, scope, language, limit)
        } else {
            findCallees(project, rootMethod, scope, language, limit)
        }
        return CallHierarchyData(rootMethod.toCallElement(language), calls)
    }

    fun methodNameAt(project: Project, element: PsiElement): String? =
        methodAt(project, element)?.name ?: wordAt(element)

    private fun definitionResult(
        project: Project,
        file: VirtualFile,
        document: com.intellij.openapi.editor.Document,
        lineIndex: Int,
        columnIndex: Int,
        symbolName: String
    ): DefinitionResult {
        val previewStart = (lineIndex - 1).coerceAtLeast(0)
        val previewEnd = (lineIndex + 2).coerceAtMost(document.lineCount - 1)
        val preview = (previewStart..previewEnd).joinToString("\n") { idx ->
            val startOffset = document.getLineStartOffset(idx)
            val endOffset = document.getLineEndOffset(idx)
            "${idx + 1}: ${document.getText(TextRange(startOffset, endOffset))}"
        }
        return DefinitionResult(
            file = ProjectUtils.getToolFilePath(project, file),
            line = lineIndex + 1,
            column = columnIndex.coerceAtLeast(0) + 1,
            preview = preview,
            symbolName = symbolName,
            astPath = emptyList()
        )
    }

    private fun dotNetFiles(project: Project, scope: com.intellij.psi.search.GlobalSearchScope): List<VirtualFile> =
        DOTNET_EXTENSIONS.flatMap { ext -> FilenameIndex.getAllFilesByExt(project, ext, scope) }

    private fun containsTypeName(inheritedTypes: String, typeName: String): Boolean {
        val simpleName = typeName.substringAfterLast('.')
        return inheritedTypes
            .split(',', ' ', '\t', '\r', '\n')
            .map { it.trim().substringBefore('<').substringAfterLast('.') }
            .any { it == simpleName || it == typeName }
    }

    private fun findCallers(
        project: Project,
        target: MethodDeclaration,
        scope: BuiltInSearchScope,
        language: String,
        limit: Int
    ): List<CallElementData> {
        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        val wordRegex = Regex("""\b${Regex.escape(target.name)}\s*\(""")
        val results = mutableListOf<CallElementData>()
        val seen = mutableSetOf<String>()
        for (file in dotNetFiles(project, searchScope)) {
            if (results.size >= limit) break
            val methods = methodDeclarations(project, file)
            if (methods.isEmpty()) continue
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: continue
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: continue
            document.text.lines().forEachIndexed { index, line ->
                if (results.size >= limit) return@forEachIndexed
                if (!wordRegex.containsMatchIn(line)) return@forEachIndexed
                val caller = methods.lastOrNull { index + 1 in it.startLine..it.endLine } ?: return@forEachIndexed
                if (caller.file == target.file && caller.line == target.line) return@forEachIndexed
                val key = "${caller.file}:${caller.line}:${caller.name}"
                if (seen.add(key)) {
                    results.add(caller.toCallElement(language))
                }
            }
        }
        return results
    }

    private fun findCallees(
        project: Project,
        target: MethodDeclaration,
        scope: BuiltInSearchScope,
        language: String,
        limit: Int
    ): List<CallElementData> {
        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        val allMethods = dotNetFiles(project, searchScope)
            .flatMap { methodDeclarations(project, it) }
            .groupBy { it.name }
        val file = target.virtualFile
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return emptyList()
        val lines = document.text.lines()
        val results = mutableListOf<CallElementData>()
        val seen = mutableSetOf<String>()
        for (lineIndex in (target.startLine - 1)..(target.endLine - 1).coerceAtMost(lines.lastIndex)) {
            val line = lines.getOrNull(lineIndex) ?: continue
            for (match in CSHARP_INVOCATION_REGEX.findAll(line)) {
                if (results.size >= limit) return results
                val name = match.groupValues[1]
                if (name in NON_CALL_IDENTIFIERS || name == target.name) continue
                val declaration = allMethods[name]?.firstOrNull()
                val call = declaration?.toCallElement(language) ?: CallElementData(
                    name = name,
                    file = target.file,
                    line = lineIndex + 1,
                    column = match.range.first + 1,
                    language = language
                )
                val key = "${call.file}:${call.line}:${call.column}:${call.name}"
                if (seen.add(key)) results.add(call)
            }
        }
        return results
    }

    private fun methodAt(project: Project, element: PsiElement): MethodDeclaration? {
        val file = element.containingFile?.virtualFile ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val line = document.getLineNumber(element.textOffset.coerceIn(0, document.textLength.coerceAtLeast(1) - 1)) + 1
        return methodDeclarations(project, file).lastOrNull { line in it.startLine..it.endLine }
    }

    private fun methodDeclarations(project: Project, file: VirtualFile): List<MethodDeclaration> {
        if (file.extension?.lowercase() !in setOf("cs", "csx")) return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return emptyList()
        val relativePath = ProjectUtils.getToolFilePath(project, file)
        val lines = document.text.lines()
        val methods = mutableListOf<MethodDeclaration>()
        var currentType = ""
        lines.forEachIndexed { index, line ->
            TYPE_DECLARATION_REGEX.find(line)?.let { currentType = it.groupValues[2] }
            val match = CSHARP_METHOD_DECLARATION_REGEX.find(line) ?: return@forEachIndexed
            val name = match.groupValues[1]
            if (name in NON_CALL_IDENTIFIERS) return@forEachIndexed
            val startLine = index + 1
            methods.add(
                MethodDeclaration(
                    name = name,
                    containingType = currentType,
                    signature = line.trim(),
                    file = relativePath,
                    virtualFile = file,
                    line = startLine,
                    column = line.indexOf(name).coerceAtLeast(0) + 1,
                    startLine = startLine,
                    endLine = findMethodEndLine(lines, index)
                )
            )
        }
        return methods
    }

    private fun findMethodEndLine(lines: List<String>, startIndex: Int): Int {
        var depth = 0
        var sawBody = false
        for (index in startIndex until lines.size) {
            val line = lines[index]
            depth += line.count { it == '{' }
            if (line.contains("{")) sawBody = true
            depth -= line.count { it == '}' }
            if (line.contains("=>") || (sawBody && depth <= 0 && index > startIndex)) {
                return index + 1
            }
        }
        return startIndex + 1
    }

    private fun typeDeclarations(project: Project, file: VirtualFile): List<TypeDeclaration> {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return emptyList()
        val relativePath = ProjectUtils.getToolFilePath(project, file)
        val namespace = document.text.lines()
            .firstNotNullOfOrNull { line -> NAMESPACE_REGEX.find(line)?.groupValues?.getOrNull(1) }
        return document.text.lines().mapIndexedNotNull { index, line ->
            val match = TYPE_DECLARATION_REGEX.find(line) ?: FSHARP_TYPE_DECLARATION_REGEX.find(line) ?: return@mapIndexedNotNull null
            val kind = match.groupValues[1].uppercase()
            val name = match.groupValues[2].substringBefore("'")
            val column = line.indexOf(name).coerceAtLeast(0) + 1
            TypeDeclaration(
                name = name,
                qualifiedName = namespace?.let { "$it.$name" },
                kind = if (kind == "TYPE") "CLASS" else kind,
                file = relativePath,
                line = index + 1,
                column = column
            )
        }
    }

    private fun TypeDeclaration.toSymbolMatch(language: String): SymbolMatch =
        SymbolMatch(
            name = name,
            qualifiedName = qualifiedName,
            kind = kind,
            file = file,
            line = line,
            column = column,
            containerName = qualifiedName?.substringBeforeLast('.', missingDelimiterValue = ""),
            language = language
        )

    private fun languageFor(file: VirtualFile): String =
        if (file.extension?.lowercase()?.startsWith("fs") == true) "F#" else "C#"

    private fun classifyTextUsage(line: String, endIndex: Int): String {
        val suffix = line.drop(endIndex).trimStart()
        return if (suffix.startsWith("(")) "method_call" else "reference"
    }

    private fun isIdentifierStart(ch: Char): Boolean = ch == '_' || ch.isLetter()

    private fun isIdentifierPart(ch: Char): Boolean = ch == '_' || ch.isLetterOrDigit()

    private data class TypeDeclaration(
        val name: String,
        val qualifiedName: String?,
        val kind: String,
        val file: String,
        val line: Int,
        val column: Int
    )

    private data class MethodDeclaration(
        val name: String,
        val containingType: String,
        val signature: String,
        val file: String,
        val virtualFile: VirtualFile,
        val line: Int,
        val column: Int,
        val startLine: Int,
        val endLine: Int
    ) {
        fun toCallElement(language: String): CallElementData =
            CallElementData(
                name = if (containingType.isBlank()) name else "$containingType.$name",
                file = file,
                line = line,
                column = column,
                language = language
            )
    }
}
