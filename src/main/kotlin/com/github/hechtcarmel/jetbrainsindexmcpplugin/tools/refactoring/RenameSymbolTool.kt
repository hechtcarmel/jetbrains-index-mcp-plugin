package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.util.containers.MultiMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Universal rename tool that works across all languages supported by JetBrains IDEs.
 *
 * This tool uses IntelliJ's `RenameProcessor` which is language-agnostic and delegates
 * to language-specific `RenamePsiElementProcessor` implementations. This enables:
 * - Java/Kotlin: getter/setter renaming, overriding methods, test classes
 * - Python: function/class/variable renaming
 * - JavaScript/TypeScript: symbol renaming across files
 * - Go: function/type/variable renaming
 * - And more languages via their respective plugins
 *
 * The tool uses a two-phase approach:
 * 1. **Background Phase**: Find element and validate (read action)
 * 2. **EDT Phase**: Execute rename via RenameProcessor (handles all references)
 */
class RenameSymbolTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<RenameSymbolTool>()
    }

    override val name = "ide_refactor_rename"

    override val description = """
        Rename a symbol and update all references across the project. Use instead of find-and-replace for safe, semantic renaming that handles all usages correctly. Supports undo (Ctrl+Z).

        Automatically renames related elements: getters/setters, overriding methods, constructor parameters ↔ fields, test classes.

        When renaming a method that overrides a base method, the `overrideStrategy` parameter controls behavior:
        - "rename_base" (default): Automatically renames the base method and all overrides. No dialog shown.
        - "rename_only_current": Renames only the current method, leaving the base and other overrides unchanged.
        - "ask": Shows the IDE's built-in dialog to let the user choose interactively.

        The `relatedRenamingStrategy` parameter controls automatic renaming of related symbols (e.g., same-named properties on unrelated classes, getters/setters, test classes, variables):
        - "all" (default): Automatically rename all related symbols. Current behavior.
        - "none": Rename only the targeted symbol. Skip all automatic related renames.
        - "accessors_and_tests": Only rename getters/setters and test classes/methods. Skip variables, inheritors, overloads, and parameters on unrelated classes.
        - "ask": Show the IDE dialog for each related rename for interactive choice.

        Returns: affected files list and change count. Modifies source files.

        Parameters: file + line + column + newName (all required), overrideStrategy + relatedRenamingStrategy (optional).

        Example: {"file": "src/UserService.java", "line": 15, "column": 18, "newName": "CustomerService"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file relative to project root. REQUIRED.")
        .lineAndColumn()
        .stringProperty("newName", "The new name for the symbol. REQUIRED.", required = true)
        .enumProperty(
            "overrideStrategy",
            "Strategy when renaming a method that overrides a base method. " +
                "'rename_base' (default): rename the base method and all overrides automatically. " +
                "'rename_only_current': rename only the current method. " +
                "'ask': show the IDE dialog for interactive choice.",
            listOf("rename_base", "rename_only_current", "ask")
        )
        .enumProperty(
            "relatedRenamingStrategy",
            "Strategy for automatic renaming of related symbols (same-named properties, getters/setters, test classes, variables). " +
                "'all' (default): automatically rename all related symbols. " +
                "'none': rename only the targeted symbol, skip all automatic related renames. " +
                "'accessors_and_tests': only rename getters/setters and test classes/methods. " +
                "'ask': show the IDE dialog for each related rename for interactive choice.",
            listOf("all", "none", "accessors_and_tests", "ask")
        )
        .build()

    /**
     * Data class holding validated rename parameters from Phase 1.
     */
    private data class RenameValidation(
        val element: PsiNamedElement,
        val oldName: String,
        val error: String? = null
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")
        val newName = arguments["newName"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: newName")

        val overrideStrategy = arguments["overrideStrategy"]?.jsonPrimitive?.content ?: "rename_base"
        if (overrideStrategy !in listOf("rename_base", "rename_only_current", "ask")) {
            return createErrorResult("Invalid overrideStrategy: '$overrideStrategy'. Must be 'rename_base', 'rename_only_current', or 'ask'.")
        }

        val relatedRenamingStrategy = arguments["relatedRenamingStrategy"]?.jsonPrimitive?.content ?: "all"
        if (relatedRenamingStrategy !in listOf("all", "none", "accessors_and_tests", "ask")) {
            return createErrorResult("Invalid relatedRenamingStrategy: '$relatedRenamingStrategy'. Must be 'all', 'none', 'accessors_and_tests', or 'ask'.")
        }

        if (newName.isBlank()) {
            return createErrorResult("newName cannot be blank")
        }

        requireSmartMode(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Find element and validate (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val validation = suspendingReadAction {
            validateAndPrepare(project, file, line, column, newName)
        }

        if (validation.error != null) {
            return createErrorResult(validation.error)
        }

        val element = validation.element
        val oldName = validation.oldName

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Execute rename using RenameProcessor
        // ═══════════════════════════════════════════════════════════════════════
        var changesCount = 0
        val affectedFiles = mutableSetOf<String>()
        var relatedRenamesCount = 0
        var errorMessage: String? = null

        edtAction {
            try {
                val result = executeRename(project, element, newName, overrideStrategy, relatedRenamingStrategy, affectedFiles)
                changesCount = result.first
                relatedRenamesCount = result.second
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error during rename"
            }
        }

        // Commit and save outside EDT block — commitDocuments uses
        // TransactionGuard.submitTransactionAndWait for write-safe context
        if (errorMessage == null) {
            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
        }

        return if (errorMessage != null) {
            createErrorResult("Rename failed: $errorMessage")
        } else {
            val relatedNote = if (relatedRenamesCount > 0) {
                " (also renamed $relatedRenamesCount related element(s))"
            } else ""

            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = affectedFiles.toList(),
                    changesCount = changesCount,
                    message = "Successfully renamed '$oldName' to '$newName'$relatedNote"
                )
            )
        }
    }

    /**
     * Validates rename parameters and prepares the element for renaming.
     * Runs in a read action (background thread).
     */
    private fun validateAndPrepare(
        project: Project,
        file: String,
        line: Int,
        column: Int,
        newName: String
    ): RenameValidation {
        val psiElement = findPsiElement(project, file, line, column)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "No element found at the specified position"
            )

        val namedElement = findNamedElement(psiElement)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "No renameable symbol found at the specified position"
            )

        val oldName = namedElement.name
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "Element has no name"
            )

        if (oldName == newName) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = "New name is the same as the current name"
            )
        }

        // Validate the new name using language-specific rules
        val validationError = validateNewName(project, namedElement, newName)
        if (validationError != null) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = validationError
            )
        }

        // Check for naming conflicts (would show dialog otherwise)
        val conflictError = checkForConflicts(namedElement, newName)
        if (conflictError != null) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = conflictError
            )
        }

        return RenameValidation(
            element = namedElement,
            oldName = oldName
        )
    }

    /**
     * Checks for naming conflicts that would prevent the rename.
     * Returns an error message if conflicts exist, null otherwise.
     */
    private fun checkForConflicts(element: PsiNamedElement, newName: String): String? {
        val processor = RenamePsiElementProcessor.forElement(element)
        val conflicts = MultiMap<PsiElement, String>()

        // Let the processor find existing name conflicts
        processor.findExistingNameConflicts(element, newName, conflicts)

        if (!conflicts.isEmpty) {
            val conflictMessages = conflicts.values().take(3).joinToString("; ")
            val moreCount = conflicts.values().size - 3
            val suffix = if (moreCount > 0) " (and $moreCount more)" else ""
            return "Name conflict: $conflictMessages$suffix"
        }

        return null
    }

    /**
     * Validates the new name using language-specific identifier rules.
     */
    private fun validateNewName(
        project: Project,
        element: PsiElement,
        newName: String
    ): String? {
        val psiFile = element.containingFile ?: return null
        val language = psiFile.language

        val validator = LanguageNamesValidation.INSTANCE.forLanguage(language)

        if (!validator.isIdentifier(newName, project)) {
            return "'$newName' is not a valid identifier in ${language.displayName}"
        }

        if (validator.isKeyword(newName, project)) {
            return "'$newName' is a reserved keyword in ${language.displayName}"
        }

        return null
    }

    /**
     * Executes the rename using IntelliJ's RenameProcessor.
     * Must be called on EDT.
     *
     * HEADLESS OPERATION WITH AUTOMATIC RELATED RENAMES:
     * - Related elements (getters/setters, overriding methods, tests, etc.) are delegated to
     *   IntelliJ's automatic renamer infrastructure
     * - Dialog-producing renamers are force-applied through [HeadlessRenameProcessor]
     * - Constructor parameter -> field coupling is pre-added because the platform only provides
     *   the inverse relation (field -> constructor parameters)
     *
     * @return Pair of (affected files count, related elements renamed count)
     */
    private fun executeRename(
        project: Project,
        element: PsiNamedElement,
        newName: String,
        overrideStrategy: String,
        relatedRenamingStrategy: String,
        affectedFiles: MutableSet<String>
    ): Pair<Int, Int> {
        // Resolve the actual target element to rename based on override strategy.
        // For methods that override a base method, RenameJavaMethodProcessor's
        // substituteElementToRename() calls SuperMethodWarningUtil.checkSuperMethod()
        // which shows a modal dialog. We handle this ourselves based on the strategy:
        // - "rename_base": resolve to deepest super method (no dialog)
        // - "rename_only_current": use the element as-is (no dialog)
        // - "ask": delegate to substituteElementToRename (shows dialog)
        val targetElement = resolveRenameTarget(element, overrideStrategy)

        // Create the RenameProcessor with language-appropriate settings.
        // NOTE: We intentionally DON'T search in comments/text occurrences to avoid
        // non-code usage dialogs. The basic rename is more predictable for agents.
        // When relatedRenamingStrategy is "ask", use a standard RenameProcessor so the
        // IDE shows its built-in dialog for each automatic renamer.
        val renameProcessor = if (relatedRenamingStrategy == "ask") {
            RenameProcessor(project, targetElement, newName, false, false)
        } else {
            HeadlessRenameProcessor(project, targetElement, newName, false, false)
        }

        // Register automatic renamers based on the relatedRenamingStrategy.
        // Factories with null option names are already handled automatically by RenameProcessor.
        if (relatedRenamingStrategy != "none") {
            for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
                if (factory.optionName == null) continue
                if (relatedRenamingStrategy == "accessors_and_tests" && !isAccessorOrTestFactory(factory)) continue
                renameProcessor.addRenamerFactory(factory)
            }
        }

        // Add constructor parameter -> field relation up front.
        addParameterFieldRelations(project, targetElement, newName, renameProcessor)

        // Disable preview dialog for headless operation
        renameProcessor.setPreviewUsages(false)

        // Execute the rename - this modifies files in place (primary + all related elements)
        renameProcessor.run()

        val relatedRenamesCount = renameProcessor.elements.count { it != targetElement }
        for (renamedElement in renameProcessor.elements) {
            renamedElement.containingFile?.virtualFile?.let { vf ->
                affectedFiles.add(getRelativePath(project, vf))
            }
        }

        return Pair(affectedFiles.size, relatedRenamesCount)
    }

    /**
     * Checks if an [AutomaticRenamerFactory] is an accessor (getter/setter) or test renamer.
     *
     * Used by the "accessors_and_tests" related renaming strategy to filter factories.
     * Matches by class name suffix to remain language-agnostic (works for Java, Kotlin, etc.).
     */
    private fun isAccessorOrTestFactory(factory: AutomaticRenamerFactory): Boolean {
        val className = factory.javaClass.simpleName
        return className.contains("GetterSetter") ||
            className.contains("Accessor") ||
            className.contains("Test")
    }

    /**
     * Resolves the actual PsiNamedElement to rename based on the override strategy.
     *
     * For methods that override a base method, IntelliJ's substituteElementToRename()
     * calls SuperMethodWarningUtil.checkSuperMethod() which shows a modal dialog.
     *
     * @param overrideStrategy Controls behavior for override methods:
     *   - "rename_base": resolve to deepest super method automatically (no dialog)
     *   - "rename_only_current": use the element as-is, skip substitution (no dialog)
     *   - "ask": delegate to substituteElementToRename (shows IDE dialog)
     */
    private fun resolveRenameTarget(element: PsiNamedElement, overrideStrategy: String): PsiNamedElement {
        when (overrideStrategy) {
            "rename_base" -> {
                // Resolve to the deepest super method to avoid the dialog
                val deepestSuper = resolveDeepestSuperMethod(element)
                if (deepestSuper != null) return deepestSuper
            }
            "rename_only_current" -> {
                // Use the element directly — skip substituteElementToRename entirely
                // to avoid the dialog. Only apply non-dialog substitutions.
                return resolveNonDialogSubstitution(element)
            }
            "ask" -> {
                // Fall through to substituteElementToRename (will show dialog)
            }
        }

        // For non-override elements or "ask" strategy, use standard substitution
        val elementProcessor = RenamePsiElementProcessor.forElement(element)
        val substituted = elementProcessor.substituteElementToRename(element, null)
        return (substituted as? PsiNamedElement) ?: element
    }

    /**
     * Applies non-dialog substitutions (e.g., record component for accessor).
     * Skips substituteElementToRename() which would trigger the super method dialog.
     */
    private fun resolveNonDialogSubstitution(element: PsiNamedElement): PsiNamedElement {
        try {
            // Check for record component accessor (Java 16+)
            val recordUtilClass = Class.forName("com.intellij.psi.util.JavaPsiRecordUtil")
            val result = recordUtilClass.getMethod("getRecordComponentForAccessor", Class.forName("com.intellij.psi.PsiMethod"))
                .invoke(null, element)
            if (result is PsiNamedElement) return result
        } catch (e: Exception) {
            LOG.warn("Failed to resolve record component for accessor: ${e.message}", e)
        }
        return element
    }

    /**
     * If the element is a method that overrides a base method, returns the deepest
     * super method. Returns null if the element is not a method or has no super methods.
     *
     * Handles both:
     * - Java/Kotlin PsiMethod (including KtLightMethod) via PsiMethod.findDeepestSuperMethods()
     * - Kotlin KtNamedFunction via KtNamedFunction.getOverriddenDescriptors() (reflection)
     *
     * Uses reflection to access language-specific APIs to keep the tool language-agnostic.
     */
    private fun resolveDeepestSuperMethod(element: PsiNamedElement): PsiNamedElement? {
        // Try Java/Kotlin PsiMethod path (covers KtLightMethod too)
        try {
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (psiMethodClass.isInstance(element)) {
                val deepestSuperMethods = psiMethodClass.getMethod("findDeepestSuperMethods")
                    .invoke(element) as? Array<*> ?: return null
                if (deepestSuperMethods.isNotEmpty()) {
                    return deepestSuperMethods[0] as? PsiNamedElement
                }
                return null
            }
        } catch (e: Exception) {
            LOG.warn("Failed to resolve deepest super method via PsiMethod API: ${e.message}", e)
        }

        // Try Kotlin KtNamedFunction path — unwrap to light method and use PsiMethod API
        try {
            val ktNamedFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            if (!ktNamedFunctionClass.isInstance(element)) return null

            // Use LightClassUtils to get the light method wrapper
            val lightClassUtilsClass = Class.forName("org.jetbrains.kotlin.asJava.LightClassUtilsKt")
            val lightElements = lightClassUtilsClass.getMethod("toLightMethods", Class.forName("org.jetbrains.kotlin.psi.KtDeclaration"))
                .invoke(null, element) as? List<*> ?: return null

            val lightMethod = lightElements.firstOrNull() ?: return null

            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (!psiMethodClass.isInstance(lightMethod)) return null

            val deepestSuperMethods = psiMethodClass.getMethod("findDeepestSuperMethods")
                .invoke(lightMethod) as? Array<*> ?: return null

            if (deepestSuperMethods.isNotEmpty()) {
                return deepestSuperMethods[0] as? PsiNamedElement
            }
        } catch (e: Exception) {
            LOG.warn("Failed to resolve deepest super method via Kotlin KtNamedFunction API: ${e.message}", e)
        }

        return null
    }

    /**
     * Detects and adds constructor parameter -> field relationships that IntelliJ does not
     * model automatically.
     *
     * The platform has a built-in automatic renamer for the inverse direction
     * (field -> constructor parameters), but not for parameter -> field. We mirror the
     * Java naming logic so constructor parameters like `ready` can rename related fields
     * such as `isReady` or code-style-prefixed variants.
     *
     * Uses reflection to access Java PSI classes to keep the tool language-agnostic.
     *
     * @return Number of related elements added
     */
    private fun addParameterFieldRelations(
        project: Project,
        element: PsiNamedElement,
        newName: String,
        renameProcessor: RenameProcessor
    ): Int {
        var count = 0

        try {
            // Check if this is a Java/Kotlin parameter declared on a constructor
            val psiParameterClass = try {
                Class.forName("com.intellij.psi.PsiParameter")
            } catch (e: ClassNotFoundException) {
                return 0 // Java plugin not available
            }

            if (!psiParameterClass.isInstance(element)) {
                return 0
            }

            val declarationScope = element.javaClass.getMethod("getDeclarationScope").invoke(element)
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (!psiMethodClass.isInstance(declarationScope)) {
                return 0
            }

            val isConstructor = psiMethodClass.getMethod("isConstructor").invoke(declarationScope) as Boolean
            if (!isConstructor) {
                return 0
            }

            val parameterName = element.name ?: return 0
            val containingClass = psiMethodClass.getMethod("getContainingClass").invoke(declarationScope) ?: return 0
            val psiClassClass = Class.forName("com.intellij.psi.PsiClass")
            val javaCodeStyleManagerClass = Class.forName("com.intellij.psi.codeStyle.JavaCodeStyleManager")
            val variableKindClass = Class.forName("com.intellij.psi.codeStyle.VariableKind")

            @Suppress("UNCHECKED_CAST")
            val enumClass = variableKindClass as Class<out Enum<*>>
            val parameterKind = java.lang.Enum.valueOf(enumClass, "PARAMETER")
            val fieldKind = java.lang.Enum.valueOf(enumClass, "FIELD")

            val styleManager = javaCodeStyleManagerClass.getMethod("getInstance", Project::class.java)
                .invoke(null, project)
            val variableNameToPropertyName = javaCodeStyleManagerClass.getMethod(
                "variableNameToPropertyName",
                String::class.java,
                variableKindClass
            )
            val propertyNameToVariableName = javaCodeStyleManagerClass.getMethod(
                "propertyNameToVariableName",
                String::class.java,
                variableKindClass
            )

            val parameterPropertyName = variableNameToPropertyName.invoke(
                styleManager,
                parameterName,
                parameterKind
            ) as? String ?: return 0
            val newPropertyName = variableNameToPropertyName.invoke(
                styleManager,
                newName,
                parameterKind
            ) as? String ?: return 0
            val expectedFieldName = propertyNameToVariableName.invoke(
                styleManager,
                newPropertyName,
                fieldKind
            ) as? String ?: return 0

            val fields = psiClassClass.getMethod("getAllFields").invoke(containingClass) as Array<*>
            for (field in fields) {
                if (field !is PsiNamedElement) continue

                val fieldName = field.name ?: continue
                val fieldPropertyName = variableNameToPropertyName.invoke(
                    styleManager,
                    fieldName,
                    fieldKind
                ) as? String ?: continue

                if (fieldPropertyName != parameterPropertyName) continue
                if (fieldName == expectedFieldName) continue

                renameProcessor.addElement(field, expectedFieldName)
                count++
            }
        } catch (e: Exception) {
            // Reflection failed - likely not a Java/Kotlin project or different PSI structure
            // This is expected for other languages, silently continue
        }

        return count
    }

    /**
     * Finds the named element from a PSI element (traverses up if needed).
     */
    private fun findNamedElement(element: PsiElement): PsiNamedElement? {
        if (element is PsiNamedElement && element.name != null) {
            return element
        }
        return PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
    }

    /**
     * Dummy placeholder for error cases to satisfy non-null return type.
     */
    @Suppress("DEPRECATION")
    private object DummyNamedElement : PsiNamedElement {
        override fun setName(name: String): PsiElement = this
        override fun getName(): String? = null
        override fun getProject() = throw UnsupportedOperationException()
        override fun getLanguage() = throw UnsupportedOperationException()
        override fun getManager() = throw UnsupportedOperationException()
        override fun getChildren() = throw UnsupportedOperationException()
        override fun getParent() = throw UnsupportedOperationException()
        override fun getFirstChild() = throw UnsupportedOperationException()
        override fun getLastChild() = throw UnsupportedOperationException()
        override fun getNextSibling() = throw UnsupportedOperationException()
        override fun getPrevSibling() = throw UnsupportedOperationException()
        override fun getContainingFile() = throw UnsupportedOperationException()
        override fun getTextRange() = throw UnsupportedOperationException()
        override fun getStartOffsetInParent() = throw UnsupportedOperationException()
        override fun getTextLength() = throw UnsupportedOperationException()
        override fun findElementAt(offset: Int) = throw UnsupportedOperationException()
        override fun findReferenceAt(offset: Int) = throw UnsupportedOperationException()
        override fun getTextOffset() = throw UnsupportedOperationException()
        override fun getText() = throw UnsupportedOperationException()
        override fun textToCharArray() = throw UnsupportedOperationException()
        override fun getNavigationElement() = throw UnsupportedOperationException()
        override fun getOriginalElement() = throw UnsupportedOperationException()
        override fun textMatches(text: CharSequence) = throw UnsupportedOperationException()
        override fun textMatches(element: PsiElement) = throw UnsupportedOperationException()
        override fun textContains(c: Char) = throw UnsupportedOperationException()
        override fun accept(visitor: com.intellij.psi.PsiElementVisitor) = throw UnsupportedOperationException()
        override fun acceptChildren(visitor: com.intellij.psi.PsiElementVisitor) = throw UnsupportedOperationException()
        override fun copy() = throw UnsupportedOperationException()
        override fun add(element: PsiElement) = throw UnsupportedOperationException()
        override fun addBefore(element: PsiElement, anchor: PsiElement?) = throw UnsupportedOperationException()
        override fun addAfter(element: PsiElement, anchor: PsiElement?) = throw UnsupportedOperationException()
        override fun checkAdd(element: PsiElement) = throw UnsupportedOperationException()
        override fun addRange(first: PsiElement, last: PsiElement) = throw UnsupportedOperationException()
        override fun addRangeBefore(first: PsiElement, last: PsiElement, anchor: PsiElement) = throw UnsupportedOperationException()
        override fun addRangeAfter(first: PsiElement, last: PsiElement, anchor: PsiElement) = throw UnsupportedOperationException()
        override fun delete() = throw UnsupportedOperationException()
        override fun checkDelete() = throw UnsupportedOperationException()
        override fun deleteChildRange(first: PsiElement, last: PsiElement) = throw UnsupportedOperationException()
        override fun replace(newElement: PsiElement) = throw UnsupportedOperationException()
        override fun isValid() = false
        override fun isWritable() = false
        override fun getReference() = throw UnsupportedOperationException()
        override fun getReferences() = throw UnsupportedOperationException()
        override fun <T> getCopyableUserData(key: com.intellij.openapi.util.Key<T>) = throw UnsupportedOperationException()
        override fun <T> putCopyableUserData(key: com.intellij.openapi.util.Key<T>, value: T?) = throw UnsupportedOperationException()
        override fun processDeclarations(processor: com.intellij.psi.scope.PsiScopeProcessor, state: com.intellij.psi.ResolveState, lastParent: PsiElement?, place: PsiElement) = throw UnsupportedOperationException()
        override fun getContext() = throw UnsupportedOperationException()
        override fun isPhysical() = false
        override fun getResolveScope() = throw UnsupportedOperationException()
        override fun getUseScope() = throw UnsupportedOperationException()
        override fun getNode() = throw UnsupportedOperationException()
        override fun isEquivalentTo(another: PsiElement?) = false
        override fun getIcon(flags: Int) = throw UnsupportedOperationException()
        override fun <T> getUserData(key: com.intellij.openapi.util.Key<T>): T? = null
        override fun <T> putUserData(key: com.intellij.openapi.util.Key<T>, value: T?) {}
    }
}
