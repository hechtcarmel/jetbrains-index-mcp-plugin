package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException

/**
 * Resolves classes by fully qualified name across multiple languages.
 *
 * Supports:
 * - **PHP**: Uses `PhpIndex.getClassesByFQN()`, `getInterfacesByFQN()`, and `getTraitsByFQN()` via reflection
 * - **Java/Kotlin**: Uses `JavaPsiFacade.findClass()` with fallback to filename-based search
 *
 * All language-specific classes are accessed via reflection to avoid compile-time dependencies
 * on optional language plugins.
 */
object ClassResolver {

    /**
     * Finds a class by its fully qualified name.
     *
     * Tries PHP first (if PHP plugin is available), then Java/Kotlin (if Java plugin is available).
     *
     * @param project The project context
     * @param qualifiedName Fully qualified class name (e.g., "com.example.MyClass" or "\App\Models\User")
     * @return The PsiClass/PhpClass, or null if not found or no suitable plugin is available
     */
    fun findClassByName(project: Project, qualifiedName: String): PsiElement? {
        // Try PHP first (if PHP plugin is available)
        if (PluginDetectors.php.isAvailable) {
            val phpResult = findClassByNameWithPhpPlugin(project, qualifiedName)
            if (phpResult != null) return phpResult
        }

        // Try Java/Kotlin (if Java plugin is available)
        if (PluginDetectors.java.isAvailable) {
            return try {
                findClassByNameWithJavaPlugin(project, qualifiedName)
            } catch (e: Exception) {
                rethrowControlFlowExceptions(e)
                null
            }
        }

        return null
    }

    /**
     * Rethrows control-flow exceptions that must never be mapped to "class not found",
     * unwrapping the [InvocationTargetException] wrapper reflective calls add.
     *
     * [ProcessCanceledException] and [CancellationException] must propagate per the platform
     * threading contract, and [IndexNotReadyException] must reach the tool layer's dumb-mode
     * handler so the client gets a "retry after indexing" error instead of a false negative.
     */
    private fun rethrowControlFlowExceptions(e: Exception) {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        when (cause) {
            is ProcessCanceledException, is IndexNotReadyException, is CancellationException -> throw cause
        }
    }

    /**
     * Finds a PHP class, interface, or trait by its fully qualified name using PhpIndex.
     *
     * This method handles various FQN formats:
     * - With leading backslash: `\App\Models\User`
     * - Without leading backslash: `App\Models\User`
     * - JSON-escaped backslashes: `App\\Models\\User` (automatically normalized)
     *
     * @param project The project context
     * @param qualifiedName PHP FQN in any of the above formats
     * @return The PhpClass, or null if not found
     */
    fun findClassByNameWithPhpPlugin(project: Project, qualifiedName: String): PsiElement? {
        return try {
            // Load PhpIndex class via reflection
            val phpIndexClass = Class.forName("com.jetbrains.php.PhpIndex")
            val getInstanceMethod = phpIndexClass.getMethod("getInstance", Project::class.java)
            val phpIndex = getInstanceMethod.invoke(null, project)

            // Normalize FQN:
            // 1. Handle double-escaped backslashes from JSON (\\) -> single backslash (\)
            // 2. Ensure leading backslash (PHP FQN requirement)
            val cleanedFqn = qualifiedName.replace("\\\\", "\\")
            val normalizedFqn = if (cleanedFqn.startsWith("\\")) {
                cleanedFqn
            } else {
                "\\$cleanedFqn"
            }

            // Try getClassesByFQN first (for classes, abstract classes)
            val getClassesByFQNMethod = phpIndexClass.getMethod("getClassesByFQN", String::class.java)
            val classes = getClassesByFQNMethod.invoke(phpIndex, normalizedFqn) as? Collection<*>
            if (!classes.isNullOrEmpty()) {
                return classes.firstOrNull() as? PsiElement
            }

            // Try getInterfacesByFQN (for interfaces)
            val getInterfacesByFQNMethod = phpIndexClass.getMethod("getInterfacesByFQN", String::class.java)
            val interfaces = getInterfacesByFQNMethod.invoke(phpIndex, normalizedFqn) as? Collection<*>
            if (!interfaces.isNullOrEmpty()) {
                return interfaces.firstOrNull() as? PsiElement
            }

            // Try getTraitsByFQN (for traits)
            try {
                val getTraitsByFQNMethod = phpIndexClass.getMethod("getTraitsByFQN", String::class.java)
                val traits = getTraitsByFQNMethod.invoke(phpIndex, normalizedFqn) as? Collection<*>
                if (!traits.isNullOrEmpty()) {
                    return traits.firstOrNull() as? PsiElement
                }
            } catch (e: NoSuchMethodException) {
                // getTraitsByFQN may not exist in older PHP plugin versions
            }

            // Try without leading backslash if normalized version didn't work
            if (normalizedFqn != cleanedFqn) {
                val classesAlt = getClassesByFQNMethod.invoke(phpIndex, cleanedFqn) as? Collection<*>
                if (!classesAlt.isNullOrEmpty()) {
                    return classesAlt.firstOrNull() as? PsiElement
                }

                val interfacesAlt = getInterfacesByFQNMethod.invoke(phpIndex, cleanedFqn) as? Collection<*>
                if (!interfacesAlt.isNullOrEmpty()) {
                    return interfacesAlt.firstOrNull() as? PsiElement
                }
            }

            null
        } catch (e: ClassNotFoundException) {
            // PHP plugin classes not available
            null
        } catch (e: Exception) {
            rethrowControlFlowExceptions(e)
            null
        }
    }

    /**
     * Finds a Java or Kotlin class by its fully qualified name using JavaPsiFacade.
     *
     * Uses indexed lookup first for speed, then falls back to filename-based search
     * if the index lookup fails.
     *
     * This method should only be called when Java plugin is available.
     *
     * @param project The project context
     * @param qualifiedName Fully qualified Java/Kotlin class name (e.g., "com.example.MyClass")
     * @return The PsiClass, or null if not found
     */
    fun findClassByNameWithJavaPlugin(project: Project, qualifiedName: String): PsiElement? {

        // These classes are only available when Java plugin is installed
        val javaPsiFacadeClass = Class.forName("com.intellij.psi.JavaPsiFacade")
        val globalSearchScopeClass = Class.forName("com.intellij.psi.search.GlobalSearchScope")
        val filenameIndexClass = Class.forName("com.intellij.psi.search.FilenameIndex")
        val psiJavaFileClass = Class.forName("com.intellij.psi.PsiJavaFile")

        // Get JavaPsiFacade instance
        val getInstanceMethod = javaPsiFacadeClass.getMethod("getInstance", Project::class.java)
        val javaPsiFacade = getInstanceMethod.invoke(null, project)

        // Get search scopes
        val projectScopeMethod = globalSearchScopeClass.getMethod("projectScope", Project::class.java)
        val allScopeMethod = globalSearchScopeClass.getMethod("allScope", Project::class.java)
        val everythingScopeMethod = globalSearchScopeClass.getMethod("everythingScope", Project::class.java)
        val fileScopeMethod = globalSearchScopeClass.getMethod("fileScope", PsiFile::class.java)

        val projectScope = projectScopeMethod.invoke(null, project)
        val allScope = allScopeMethod.invoke(null, project)

        // Try indexed lookup first (fastest)
        val findClassMethod = javaPsiFacadeClass.getMethod("findClass", String::class.java, globalSearchScopeClass)

        val classInProject = findClassMethod.invoke(javaPsiFacade, qualifiedName, projectScope) as PsiElement?
        if (classInProject != null) return PsiUtils.getNavigationElement(classInProject)
        // JavaPsiFace
        val classInAll = findClassMethod.invoke(javaPsiFacade, qualifiedName, allScope) as PsiElement?
        if (classInAll != null) return PsiUtils.getNavigationElement(classInAll)

        // Fallback: search by filename if index lookup fails
        val simpleClassName = qualifiedName.substringAfterLast('.')
        val expectedPackage = qualifiedName.substringBeforeLast('.', "")

        // Try Java files
        val everythingScope = everythingScopeMethod.invoke(null, project)
        val getFilesByNameMethod = filenameIndexClass.getMethod(
            "getVirtualFilesByName",
            String::class.java,
            globalSearchScopeClass
        )
        val javaFiles = getFilesByNameMethod.invoke(null, "$simpleClassName.java", everythingScope) as Collection<*>

        val psiManager = PsiManager.getInstance(project)
        for (virtualFile in javaFiles) {
            val psiFile = psiManager.findFile(virtualFile as VirtualFile) ?: continue
            if (!psiJavaFileClass.isInstance(psiFile)) continue

            val getPackageNameMethod = psiJavaFileClass.getMethod("getPackageName")
            val getClassesMethod = psiJavaFileClass.getMethod("getClasses")

            val packageName = getPackageNameMethod.invoke(psiFile) as String
            if (packageName == expectedPackage) {
                val classes = getClassesMethod.invoke(psiFile) as Array<*>
                for (psiClass in classes) {
                    val psiElement = psiClass as PsiElement
                    val getNameMethod = psiElement.javaClass.getMethod("getName")
                    val className = getNameMethod.invoke(psiElement) as String?
                    if (className == simpleClassName) return psiElement
                }
            }
        }

        // Try Kotlin files
        val ktFiles = getFilesByNameMethod.invoke(null, "$simpleClassName.kt", everythingScope) as Collection<*>
        for (virtualFile in ktFiles) {
            val psiFile = psiManager.findFile(virtualFile as VirtualFile) ?: continue
            val fileScope = fileScopeMethod.invoke(null, psiFile)
            val ktClass = findClassMethod.invoke(javaPsiFacade, qualifiedName, fileScope)
            if (ktClass != null) return ktClass as PsiElement
        }

        return null
    }
}
