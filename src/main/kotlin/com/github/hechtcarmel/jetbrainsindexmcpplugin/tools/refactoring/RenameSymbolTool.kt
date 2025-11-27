package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenamePsiElementProcessor
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
 * This implementation:
 * 1. Uses direct PSI manipulation instead of RenameProcessor.run() to avoid dialogs
 * 2. Automatically renames related elements (constructor parameters, getters/setters) without prompting
 * 3. Supports all nameable elements: classes, methods, fields, parameters, variables
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

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
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

        // Find the element to rename
        val element = readAction {
            findNamedElement(project, file, line, column)
        } ?: return createErrorResult("No renameable symbol found at the specified position")

        val oldName = readAction { element.name } ?: return createErrorResult("Cannot determine current symbol name")

        if (oldName == newName) {
            return createErrorResult("New name is the same as the current name")
        }

        // Collect all elements to rename (main element + related elements like constructor params)
        val elementsToRename = mutableMapOf<PsiNamedElement, String>()
        val affectedFiles = mutableSetOf<String>()

        try {
            readAction {
                // Add the main element
                elementsToRename[element] = newName

                // Find related elements that should also be renamed
                findRelatedElementsToRename(element, oldName, newName, elementsToRename)

                // Collect all affected files for all elements
                for ((elem, _) in elementsToRename) {
                    elem.containingFile?.virtualFile?.let { vf ->
                        trackAffectedFile(project, vf, affectedFiles)
                    }

                    val references = ReferencesSearch.search(elem).findAll()
                    for (reference in references) {
                        reference.element.containingFile?.virtualFile?.let { vf ->
                            trackAffectedFile(project, vf, affectedFiles)
                        }
                    }
                }
            }

            // Perform the rename using headless PSI manipulation
            var success = false
            var errorMessage: String? = null
            var changesCount = 0

            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.writeCommandAction(project)
                    .withName("Rename: $oldName to $newName")
                    .withGroupId("MCP Refactoring")
                    .run<Throwable> {
                        try {
                            // Rename all references first (before changing declarations)
                            for ((elem, targetName) in elementsToRename) {
                                val references = ReferencesSearch.search(elem).findAll()
                                for (reference in references) {
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
                            for ((elem, targetName) in elementsToRename) {
                                if (elem.isValid) {
                                    // Use RenamePsiElementProcessor for proper rename handling
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

                            success = true
                        } catch (e: Exception) {
                            errorMessage = e.message
                        }
                    }
            }

            return if (success) {
                createJsonResult(
                    RefactoringResult(
                        success = true,
                        affectedFiles = affectedFiles.toList(),
                        changesCount = changesCount,
                        message = "Successfully renamed '$oldName' to '$newName'" +
                            if (elementsToRename.size > 1) " (including ${elementsToRename.size - 1} related element(s))" else ""
                    )
                )
            } else {
                createErrorResult("Rename failed: ${errorMessage ?: "Unknown error"}")
            }

        } catch (e: Exception) {
            return createErrorResult("Rename failed: ${e.message}")
        }
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
        when (element) {
            is PsiField -> {
                // Find constructor parameters with the same name
                findMatchingConstructorParameters(element, oldName, newName, elementsToRename)
                // Find getter/setter methods
                findMatchingAccessorMethods(element, oldName, newName, elementsToRename)
            }
            is PsiParameter -> {
                // If this is a constructor parameter, check if there's a matching field
                val method = element.declarationScope
                if (method is PsiMethod && method.isConstructor) {
                    findMatchingField(method.containingClass, oldName, newName, elementsToRename)
                }
            }
            is PsiMethod -> {
                // Find overriding/implementing methods in subclasses
                findOverridingMethods(element, newName, elementsToRename)
            }
        }
    }

    /**
     * Finds constructor parameters that match a field name.
     */
    private fun findMatchingConstructorParameters(
        field: PsiField,
        oldName: String,
        newName: String,
        elementsToRename: MutableMap<PsiNamedElement, String>
    ) {
        val containingClass = field.containingClass ?: return

        for (constructor in containingClass.constructors) {
            for (parameter in constructor.parameterList.parameters) {
                if (parameter.name == oldName) {
                    elementsToRename[parameter] = newName
                }
            }
        }
    }

    /**
     * Finds getter/setter methods that match a field name.
     */
    private fun findMatchingAccessorMethods(
        field: PsiField,
        oldName: String,
        newName: String,
        elementsToRename: MutableMap<PsiNamedElement, String>
    ) {
        val containingClass = field.containingClass ?: return
        val capitalizedOldName = oldName.replaceFirstChar { it.uppercase() }
        val capitalizedNewName = newName.replaceFirstChar { it.uppercase() }

        // Look for getters: getFieldName(), isFieldName() (for booleans)
        val getterNames = listOf("get$capitalizedOldName", "is$capitalizedOldName")
        val setterName = "set$capitalizedOldName"

        for (method in containingClass.methods) {
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

    /**
     * Finds a field that matches a constructor parameter name.
     */
    private fun findMatchingField(
        containingClass: PsiClass?,
        oldName: String,
        newName: String,
        elementsToRename: MutableMap<PsiNamedElement, String>
    ) {
        containingClass ?: return

        for (field in containingClass.fields) {
            if (field.name == oldName) {
                elementsToRename[field] = newName
                // Also rename related accessor methods for the field
                findMatchingAccessorMethods(field, oldName, newName, elementsToRename)
            }
        }
    }

    /**
     * Finds methods that override or implement the given method.
     */
    private fun findOverridingMethods(
        method: PsiMethod,
        newName: String,
        elementsToRename: MutableMap<PsiNamedElement, String>
    ) {
        // Find all methods that override this one
        val overridingMethods = com.intellij.psi.search.searches.OverridingMethodsSearch.search(method).findAll()
        for (overridingMethod in overridingMethods) {
            elementsToRename[overridingMethod] = newName
        }
    }
}
