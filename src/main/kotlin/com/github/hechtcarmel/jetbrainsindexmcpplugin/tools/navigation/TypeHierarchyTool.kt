package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ClassInheritorsSearch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class TypeHierarchyTool : AbstractMcpTool() {

    override val name = "ide_type_hierarchy"

    override val description = """
        Retrieves the complete type hierarchy for a class or interface, showing all inheritance relationships.

        INPUT OPTIONS (use one):
        - Option A: Provide className with the fully qualified class name (e.g., "com.example.MyClass")
        - Option B: Provide file + line + column to identify a class by source location

        RETURNS: The target class with full supertype chain (recursive) and all subtypes in the project.

        EXAMPLE with className: {"className": "com.example.service.UserService"}
        EXAMPLE with location: {"file": "src/main/java/com/example/MyClass.java", "line": 10, "column": 14}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to project root. Only needed when multiple projects are open in IDE.")
            }
            putJsonObject("className") {
                put("type", "string")
                put("description", "Fully qualified class name (e.g., 'com.example.MyClass'). RECOMMENDED - use this if you know the class name.")
            }
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). Use with line and column.")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number where the class is defined. Required if using file parameter.")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number. Required if using file parameter.")
            }
        }
        putJsonArray("required") {
            // Empty because either className OR (file+line+column) must be provided
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

        val className = arguments["className"]?.jsonPrimitive?.content
        val file = arguments["file"]?.jsonPrimitive?.content

        return readAction {
            val targetClass = resolveTargetClass(project, arguments)
            if (targetClass == null) {
                val errorMsg = when {
                    className != null -> "Class '$className' not found in project '${project.name}'. Verify the fully qualified name is correct and the class is part of this project."
                    file != null -> "No class found at the specified file/line/column position."
                    else -> "Provide either 'className' (e.g., 'com.example.MyClass') or 'file' + 'line' + 'column'."
                }
                return@readAction createErrorResult(errorMsg)
            }

            val supertypes = getSupertypes(project, targetClass)
            val subtypes = getSubtypes(project, targetClass)

            val element = TypeElement(
                name = targetClass.qualifiedName ?: targetClass.name ?: "unknown",
                file = targetClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                kind = getClassKind(targetClass)
            )

            createJsonResult(TypeHierarchyResult(
                element = element,
                supertypes = supertypes,
                subtypes = subtypes
            ))
        }
    }

    private fun resolveTargetClass(project: Project, arguments: JsonObject): PsiClass? {
        // Try className first
        val className = arguments["className"]?.jsonPrimitive?.content
        if (className != null) {
            return findClassByName(project, className)
        }

        // Otherwise use file/line/column
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return null
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return null
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return null

        val element = findPsiElement(project, file, line, column)
            ?: return null

        return findContainingClass(element)
    }

    private fun findContainingClass(element: PsiElement): PsiClass? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiClass) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun getSupertypes(project: Project, psiClass: PsiClass, visited: MutableSet<String> = mutableSetOf()): List<TypeElement> {
        val supertypes = mutableListOf<TypeElement>()
        val className = psiClass.qualifiedName ?: psiClass.name ?: return supertypes

        // Prevent infinite recursion
        if (className in visited) return supertypes
        visited.add(className)

        // Try resolved superclass first
        val superClass = psiClass.superClass
        if (superClass != null && superClass.qualifiedName != "java.lang.Object") {
            val superSupertypes = getSupertypes(project, superClass, visited)
            supertypes.add(TypeElement(
                name = superClass.qualifiedName ?: superClass.name ?: "unknown",
                file = superClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                kind = getClassKind(superClass),
                supertypes = superSupertypes.takeIf { it.isNotEmpty() }
            ))
        } else {
            // Fallback: check unresolved extends list (when type resolution fails)
            psiClass.extendsList?.referenceElements?.forEach { ref ->
                val resolved = ref.resolve() as? PsiClass
                if (resolved != null && resolved.qualifiedName != "java.lang.Object") {
                    val superSupertypes = getSupertypes(project, resolved, visited)
                    supertypes.add(TypeElement(
                        name = resolved.qualifiedName ?: resolved.name ?: "unknown",
                        file = resolved.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        kind = getClassKind(resolved),
                        supertypes = superSupertypes.takeIf { it.isNotEmpty() }
                    ))
                } else {
                    // Can't resolve, but report the declared type name
                    val typeName = ref.qualifiedName ?: ref.referenceName ?: "unknown"
                    if (typeName != "java.lang.Object") {
                        supertypes.add(TypeElement(
                            name = typeName,
                            file = null,
                            kind = "CLASS"
                        ))
                    }
                }
            }
        }

        // Try resolved interfaces first
        val interfaces = psiClass.interfaces
        if (interfaces.isNotEmpty()) {
            interfaces.forEach { iface ->
                val ifaceSupertypes = getSupertypes(project, iface, visited)
                supertypes.add(TypeElement(
                    name = iface.qualifiedName ?: iface.name ?: "unknown",
                    file = iface.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    kind = "INTERFACE",
                    supertypes = ifaceSupertypes.takeIf { it.isNotEmpty() }
                ))
            }
        } else {
            // Fallback: check unresolved implements list (when type resolution fails)
            psiClass.implementsList?.referenceElements?.forEach { ref ->
                val resolved = ref.resolve() as? PsiClass
                if (resolved != null) {
                    val ifaceSupertypes = getSupertypes(project, resolved, visited)
                    supertypes.add(TypeElement(
                        name = resolved.qualifiedName ?: resolved.name ?: "unknown",
                        file = resolved.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        kind = "INTERFACE",
                        supertypes = ifaceSupertypes.takeIf { it.isNotEmpty() }
                    ))
                } else {
                    // Can't resolve, but report the declared type name
                    val typeName = ref.qualifiedName ?: ref.referenceName ?: "unknown"
                    supertypes.add(TypeElement(
                        name = typeName,
                        file = null,
                        kind = "INTERFACE"
                    ))
                }
            }
        }

        return supertypes
    }

    private fun getSubtypes(project: Project, psiClass: PsiClass): List<TypeElement> {
        return try {
            // true = search in all scopes (not just direct inheritors)
            ClassInheritorsSearch.search(psiClass, true)
                .findAll()
                .take(100) // Limit to prevent huge results
                .map { subClass ->
                    TypeElement(
                        name = subClass.qualifiedName ?: subClass.name ?: "unknown",
                        file = subClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        kind = getClassKind(subClass)
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getClassKind(psiClass: PsiClass): String {
        return when {
            psiClass.isInterface -> "INTERFACE"
            psiClass.isEnum -> "ENUM"
            psiClass.isAnnotationType -> "ANNOTATION"
            psiClass.isRecord -> "RECORD"
            psiClass.hasModifierProperty("abstract") -> "ABSTRACT_CLASS"
            else -> "CLASS"
        }
    }
}
