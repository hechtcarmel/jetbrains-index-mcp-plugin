package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.util.Processor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Headless rename tool that performs renaming without showing any dialogs.
 *
 * This implementation uses a two-phase approach to avoid UI freezes:
 * 1. **Background Phase**: Collect all references and prepare changes (in read action)
 * 2. **EDT Phase**: Apply pre-computed changes quickly (in write action)
 *
 * This ensures the EDT is only blocked for the minimal time required to apply changes,
 * not for the potentially slow reference search operations.
 */
class RenameSymbolTool : AbstractRefactoringTool() {

    override val name = "ide_refactor_rename"

    override val description = """
        Renames a symbol and updates all references across the project. Supports Ctrl+Z undo.

        REQUIRED: file + line + column to identify the symbol, plus newName.

        WARNING: This modifies files. Returns affected files and change count.

        EXAMPLE: {"file": "src/main/java/com/example/UserService.java", "line": 15, "column": 18, "newName": "CustomerService"}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to project root. Only needed when multiple projects are open.")
            }
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to file relative to project root. REQUIRED.")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number where the symbol is located. REQUIRED.")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number. REQUIRED.")
            }
            putJsonObject("newName") {
                put("type", "string")
                put("description", "The new name for the symbol. REQUIRED.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
            add(JsonPrimitive("line"))
            add(JsonPrimitive("column"))
            add(JsonPrimitive("newName"))
        }
    }

    /**
     * Data class to hold all information collected in background for rename operation.
     */
    private data class RenamePreparation(
        val elementsToRename: Map<PsiNamedElement, String>,
        val allReferences: Map<PsiNamedElement, List<PsiReference>>,
        val affectedFiles: Set<String>,
        val oldName: String
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

        if (newName.isBlank()) {
            return createErrorResult("newName cannot be blank")
        }

        requireSmartMode(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Collect all data (read action, can be slow)
        // ═══════════════════════════════════════════════════════════════════════
        val preparation = readAction {
            prepareRename(project, file, line, column, newName)
        } ?: return createErrorResult("No renameable symbol found at the specified position")

        if (preparation.oldName == newName) {
            return createErrorResult("New name is the same as the current name")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Apply changes quickly (write action, must be fast)
        // ═══════════════════════════════════════════════════════════════════════
        var changesCount = 0
        var errorMessage: String? = null

        withContext(Dispatchers.EDT) {
            WriteCommandAction.writeCommandAction(project)
                .withName("Rename: ${preparation.oldName} to $newName")
                .withGroupId("MCP Refactoring")
                .run<Throwable> {
                    try {
                        // Rename all references first (using pre-collected data)
                        for ((elem, targetName) in preparation.elementsToRename) {
                            val refs = preparation.allReferences[elem] ?: continue
                            for (reference in refs) {
                                if (reference.element.isValid) {
                                    try {
                                        reference.handleElementRename(targetName)
                                        changesCount++
                                    } catch (e: Exception) {
                                        // Some references may not support direct rename
                                    }
                                }
                            }
                        }

                        // Now rename the declarations themselves
                        for ((elem, targetName) in preparation.elementsToRename) {
                            if (elem.isValid) {
                                val processor = RenamePsiElementProcessor.forElement(elem)
                                val substitutor = processor.substituteElementToRename(elem, null)
                                val elementToRename = substitutor ?: elem

                                if (elementToRename is PsiNamedElement && elementToRename.isValid) {
                                    elementToRename.setName(targetName)
                                    changesCount++
                                }
                            }
                        }

                        // Commit and save
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                        FileDocumentManager.getInstance().saveAllDocuments()
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                }
        }

        return if (errorMessage != null) {
            createErrorResult("Rename failed: $errorMessage")
        } else {
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = preparation.affectedFiles.toList(),
                    changesCount = changesCount,
                    message = "Successfully renamed '${preparation.oldName}' to '$newName'" +
                        if (preparation.elementsToRename.size > 1)
                            " (including ${preparation.elementsToRename.size - 1} related element(s))"
                        else ""
                )
            )
        }
    }

    /**
     * Prepares all data needed for rename in a read action.
     * This is the slow part that runs in background.
     */
    private fun prepareRename(
        project: Project,
        file: String,
        line: Int,
        column: Int,
        newName: String
    ): RenamePreparation? {
        val element = findNamedElement(project, file, line, column) ?: return null

        val oldName = element.name ?: return null

        val elementsToRename = mutableMapOf<PsiNamedElement, String>()
        val allReferences = mutableMapOf<PsiNamedElement, List<PsiReference>>()
        val affectedFiles = mutableSetOf<String>()

        // Add the main element
        elementsToRename[element] = newName

        // Find related elements that should also be renamed
        findRelatedElementsToRename(element, oldName, newName, elementsToRename)

        // Collect all references for all elements (HEAVY WORK - but in background!)
        for ((elem, _) in elementsToRename) {
            ProgressManager.checkCanceled() // Allow cancellation

            elem.containingFile?.virtualFile?.let { vf ->
                affectedFiles.add(getRelativePath(project, vf))
            }

            val refs = mutableListOf<PsiReference>()
            ReferencesSearch.search(elem).forEach(Processor { ref ->
                ProgressManager.checkCanceled()
                refs.add(ref)
                ref.element.containingFile?.virtualFile?.let { vf ->
                    affectedFiles.add(getRelativePath(project, vf))
                }
                true // Continue collecting all references for rename
            })
            allReferences[elem] = refs
        }

        return RenamePreparation(
            elementsToRename = elementsToRename,
            allReferences = allReferences,
            affectedFiles = affectedFiles,
            oldName = oldName
        )
    }

    /**
     * Finds related elements that should be renamed together with the main element.
     * This includes:
     * - Constructor parameters that match field names
     * - Getter/setter methods that follow naming conventions
     * - Overriding/implementing methods
     */
    private fun findRelatedElementsToRename(
        element: PsiNamedElement,
        oldName: String,
        newName: String,
        elementsToRename: MutableMap<PsiNamedElement, String>
    ) {
        ProgressManager.checkCanceled()

        when (element) {
            is PsiField -> {
                findMatchingConstructorParameters(element, oldName, newName, elementsToRename)
                findMatchingAccessorMethods(element, oldName, newName, elementsToRename)
            }
            is PsiParameter -> {
                val method = element.declarationScope
                if (method is PsiMethod && method.isConstructor) {
                    findMatchingField(method.containingClass, oldName, newName, elementsToRename)
                }
            }
            is PsiMethod -> {
                findOverridingMethods(element, newName, elementsToRename)
            }
        }
    }

    private fun findMatchingConstructorParameters(
        field: PsiField,
        oldName: String,
        newName: String,
        elementsToRename: MutableMap<PsiNamedElement, String>
    ) {
        val containingClass = field.containingClass ?: return

        for (constructor in containingClass.constructors) {
            ProgressManager.checkCanceled()
            for (parameter in constructor.parameterList.parameters) {
                if (parameter.name == oldName) {
                    elementsToRename[parameter] = newName
                }
            }
        }
    }

    private fun findMatchingAccessorMethods(
        field: PsiField,
        oldName: String,
        newName: String,
        elementsToRename: MutableMap<PsiNamedElement, String>
    ) {
        val containingClass = field.containingClass ?: return
        val capitalizedOldName = oldName.replaceFirstChar { it.uppercase() }
        val capitalizedNewName = newName.replaceFirstChar { it.uppercase() }

        val getterNames = listOf("get$capitalizedOldName", "is$capitalizedOldName")
        val setterName = "set$capitalizedOldName"

        for (method in containingClass.methods) {
            ProgressManager.checkCanceled()
            val methodName = method.name
            when {
                methodName in getterNames && method.parameterList.parametersCount == 0 -> {
                    val newGetterName = if (methodName.startsWith("is")) "is$capitalizedNewName" else "get$capitalizedNewName"
                    elementsToRename[method] = newGetterName
                }
                methodName == setterName && method.parameterList.parametersCount == 1 -> {
                    elementsToRename[method] = "set$capitalizedNewName"
                }
            }
        }
    }

    private fun findMatchingField(
        containingClass: PsiClass?,
        oldName: String,
        newName: String,
        elementsToRename: MutableMap<PsiNamedElement, String>
    ) {
        containingClass ?: return

        for (field in containingClass.fields) {
            ProgressManager.checkCanceled()
            if (field.name == oldName) {
                elementsToRename[field] = newName
                findMatchingAccessorMethods(field, oldName, newName, elementsToRename)
            }
        }
    }

    private fun findOverridingMethods(
        method: PsiMethod,
        newName: String,
        elementsToRename: MutableMap<PsiNamedElement, String>
    ) {
        OverridingMethodsSearch.search(method).forEach(Processor { overridingMethod ->
            ProgressManager.checkCanceled()
            elementsToRename[overridingMethod] = newName
            true // Continue collecting all overriding methods
        })
    }
}
