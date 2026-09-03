package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer

/**
 * Keeps the imports of a Java package intact in files that use a class moved *within* that
 * package (issue #360).
 *
 * A file moved between two source roots (or two modules) that map the same package keeps its
 * fully qualified name, so a consumer's `import com.example.pkg.*;` or
 * `import com.example.pkg.Foo;` is exactly as valid after the move as before. The IDE's move
 * processor nevertheless re-binds every usage of the moved class, and that rewrite has been
 * observed to drop the on-demand import from consuming files, which then fail to compile.
 *
 * The guard snapshots, per usage file, the import statements that name the moved file's
 * package before the rewrite and re-adds any that are gone afterwards. Because the package is
 * unchanged, restoring an import restores the file's pre-move name resolution exactly; it can
 * never introduce a new conflict.
 *
 * A single-class import (`import pkg.Foo;`) is only restored when the file has no on-demand
 * import for the package afterwards: the IDE legitimately folds single imports into a
 * wildcard once enough classes of one package are imported, and re-adding them there would be
 * noise, not repair.
 *
 * **This class touches Java-plugin PSI directly and must only be reached behind
 * `PluginDetectors.java.isAvailable`.** Class loading is lazy, so guarding the call site is
 * enough to keep IDEs without the Java plugin from ever resolving these types, the same
 * arrangement `JavaSignatureExtractor` uses.
 */
internal class JavaOnDemandImportGuard private constructor(
    private val packageName: String
) : MoveUsageGuard {

    private class Snapshot(
        val file: SmartPsiElementPointer<PsiJavaFile>,
        val onDemand: Boolean,
        val singleClassNames: Set<String>
    )

    private var snapshots: List<Snapshot> = emptyList()

    override fun beforeRetarget(usageFiles: Collection<PsiFile>) {
        snapshots = usageFiles
            .filterIsInstance<PsiJavaFile>()
            .distinct()
            .mapNotNull { file ->
                val imports = packageImports(file)
                if (imports.isEmpty()) return@mapNotNull null
                Snapshot(
                    file = SmartPointerManager.createPointer(file),
                    onDemand = imports.any { it.isOnDemand },
                    singleClassNames = imports.filterNot { it.isOnDemand }
                        .mapNotNull { it.qualifiedName }
                        .toSet()
                )
            }
    }

    override fun afterRetarget(): List<RestoredImports> {
        return snapshots.mapNotNull { snapshot ->
            val file = snapshot.file.element?.takeIf { it.isValid } ?: return@mapNotNull null
            val importList = file.importList ?: return@mapNotNull null
            val remaining = packageImports(file)
            val hasOnDemand = remaining.any { it.isOnDemand }
            val factory = JavaPsiFacade.getElementFactory(file.project)

            val restored = mutableListOf<String>()
            if (snapshot.onDemand && !hasOnDemand) {
                importList.add(factory.createImportStatementOnDemand(packageName))
                restored.add("$packageName.*")
            }
            if (!hasOnDemand) {
                val remainingSingles = remaining.mapNotNull { it.qualifiedName }.toSet()
                for (qualifiedName in snapshot.singleClassNames - remainingSingles) {
                    importList.add(createSingleClassImport(file, qualifiedName))
                    restored.add(qualifiedName)
                }
            }
            val virtualFile = file.virtualFile
            if (restored.isEmpty() || virtualFile == null) null else RestoredImports(virtualFile, restored)
        }
    }

    /**
     * Builds `import qualifiedName;` from text rather than from a resolved `PsiClass`, so the
     * import comes back even while the moved class is temporarily unresolvable from this file
     * (a destination the build system has not registered yet, see the source-root warning in
     * `MoveFileTool`).
     */
    private fun createSingleClassImport(context: PsiJavaFile, qualifiedName: String): PsiImportStatement {
        val dummy = PsiFileFactory.getInstance(context.project)
            .createFileFromText("Dummy.java", JavaFileType.INSTANCE, "import $qualifiedName;") as PsiJavaFile
        return dummy.importList?.importStatements?.single()
            ?: error("Could not build import statement for '$qualifiedName'")
    }

    /** Non-static import statements that name [packageName], on-demand or single-class. */
    private fun packageImports(file: PsiJavaFile): List<PsiImportStatement> {
        val statements = file.importList?.importStatements ?: return emptyList()
        return statements.filter { statement ->
            val qualifiedName = statement.qualifiedName ?: return@filter false
            if (statement.isOnDemand) {
                qualifiedName == packageName
            } else {
                StringUtil.getPackageName(qualifiedName) == packageName
            }
        }
    }

    companion object {
        /**
         * @return a guard when [movedFile] is a Java file whose package will not change by
         *   landing in [targetDirectory]; null when the move is a real package change (imports
         *   then legitimately change) or the file is not Java.
         *
         * A destination outside every source root has no package at all. The IDE then leaves
         * the file's `package` statement untouched, so the effective package is unchanged and
         * the guard applies.
         */
        fun forMove(movedFile: PsiFile, targetDirectory: PsiDirectory): JavaOnDemandImportGuard? {
            val javaFile = movedFile as? PsiJavaFile ?: return null
            val sourcePackage = javaFile.packageName.takeIf { it.isNotEmpty() } ?: return null
            val targetPackage = JavaDirectoryService.getInstance().getPackage(targetDirectory)?.qualifiedName
            if (targetPackage != null && targetPackage != sourcePackage) return null
            return JavaOnDemandImportGuard(sourcePackage)
        }
    }
}
