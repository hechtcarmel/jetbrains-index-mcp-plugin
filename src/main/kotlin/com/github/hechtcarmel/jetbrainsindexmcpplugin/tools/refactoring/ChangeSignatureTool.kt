package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ResolvedSymbolInfo
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.changeSignature.OverriderMethodUsageInfo
import com.intellij.usageView.UsageInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.annotations.TestOnly

class ChangeSignatureTool : AbstractMcpTool() {

    /**
     * Test hook replacing the `processor.run()` call, so tests can reproduce the production
     * abort paths where `BaseRefactoringProcessor.run()` returns normally without applying
     * anything (read-only files, conflict dialogs, dumb mode). In unit-test mode the platform
     * converts those aborts into exceptions before `run()` returns, so they cannot be
     * triggered for real.
     */
    @TestOnly
    internal var processorRunHook: (() -> Unit)? = null

    /** Lets behavior tests prove that incomplete reflective discovery fails closed. */
    @TestOnly
    internal var previewUsageSearchHook: (() -> Unit)? = null

    override val name = ToolNames.CHANGE_SIGNATURE

    override val description = """
        Change a method's signature and automatically update all callers, overrides, and implementations.

        Can modify: method name, return type, visibility, parameters (add, remove, reorder, change types).
        New parameters get a default value inserted at all call sites.

        Examples:
        - By handle: {"symbolId": "<opaque-id>", "newName": "renamed"}
        - Add parameter: {"file": "src/Service.java", "line": 15, "column": 10, "newParameters": [{"oldIndex": 0, "name": "id", "type": "String"}, {"oldIndex": -1, "name": "validate", "type": "boolean", "defaultValue": "true"}]}
        - Change return type: {"file": "src/Service.java", "line": 15, "column": 10, "newReturnType": "Optional<User>"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .languageAndSymbol(required = false)
        .file(required = false, description = "Path to file containing the method. Required with line+column; omit when symbolId is used.")
        .lineAndColumn(required = false)
        .stringProperty(ParamNames.NEW_NAME, "New method name. Omit to keep current name.")
        .stringProperty(ParamNames.NEW_RETURN_TYPE, "New return type as a string (e.g., 'void', 'Optional<User>'). Omit to keep current.")
        .stringProperty(ParamNames.NEW_VISIBILITY, "New visibility: 'public', 'protected', 'private', or 'package-private'. Omit to keep current.")
        .property(ParamNames.NEW_PARAMETERS, kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("array"))
            put("description", kotlinx.serialization.json.JsonPrimitive("New parameter list. Each entry: {oldIndex (int, -1 for new), name (string), type (string), defaultValue (string, optional for new params)}. Omit to keep current parameters."))
            put("items", kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            })
        })
        .booleanProperty(ParamNames.GENERATE_DELEGATE, "Generate a delegation method with the old signature. Default: false.")
        .booleanProperty(
            ParamNames.DRY_RUN,
            "Resolve and validate the method, then discover usages/conflicts without modifying files. Default: false."
        )
        .build()

    @Serializable
    data class ChangeSignatureResult(
        val success: Boolean,
        val file: String,
        val message: String,
        val affectedFiles: List<String> = emptyList(),
        val changesCount: Int = 0,
        val updatedSymbol: ResolvedSymbolInfo? = null
    )

    private data class SignaturePreparation(
        val method: PsiMethod,
        val relativePath: String,
        val writable: Boolean
    )

    private data class SignatureState(
        val name: String,
        val returnTypeText: String?,
        val visibility: String,
        val parameters: List<Pair<String, String>>
    )

    /**
     * Everything needed to check, after the processor ran, whether the requested change
     * actually reached the PSI. `BaseRefactoringProcessor.run()` returns normally on abort paths
     * (read-only target file, unwritable elements), so without this check an aborted
     * refactoring would still be reported as applied.
     */
    private data class SignatureVerification(
        val pointer: SmartPsiElementPointer<PsiElement>,
        val before: SignatureState,
        val targetName: String?,
        val targetReturnTypeText: String?,
        val targetVisibility: String?,
        val targetParameters: List<Pair<String, String>>?
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val startedAtNanos = System.nanoTime()
        val dryRun = arguments[ParamNames.DRY_RUN]?.jsonPrimitive?.booleanOrNull == true
        val requestedSymbolId = optionalStringArg(arguments, ParamNames.SYMBOL_ID)
        val lookupMode = resolveLookupMode(arguments, allowSymbolId = true)

        val newName = arguments[ParamNames.NEW_NAME]?.jsonPrimitive?.content
        val newReturnType = arguments[ParamNames.NEW_RETURN_TYPE]?.jsonPrimitive?.content
        val newVisibility = arguments[ParamNames.NEW_VISIBILITY]?.jsonPrimitive?.content
        val newParametersJson = arguments[ParamNames.NEW_PARAMETERS]?.jsonArray
        val generateDelegate = arguments[ParamNames.GENERATE_DELEGATE]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        if (!dryRun && generateDelegate && hasNewParameterWithoutDefault(newParametersJson)) {
            return createErrorResult(
                "Generating a delegate requires an explicit non-blank defaultValue for every new required parameter."
            )
        }

        if (newName == null && newReturnType == null && newVisibility == null && newParametersJson == null) {
            return createErrorResult("At least one change is required: newName, newReturnType, newVisibility, or newParameters.")
        }

        if (newVisibility != null && newVisibility !in listOf("public", "protected", "private", "package-private", "package-local")) {
            return createErrorResult("Invalid visibility: '$newVisibility'. Must be: public, protected, private, or package-private.")
        }

        val changeSignatureProcessorClass = try {
            Class.forName("com.intellij.refactoring.changeSignature.ChangeSignatureProcessor")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("Change signature not available — requires Java plugin.")
        }

        val javaChangeInfoImplClass = try {
            Class.forName("com.intellij.refactoring.changeSignature.JavaChangeInfoImpl")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("JavaChangeInfoImpl not available — requires Java plugin.")
        }

        val parameterInfoImplClass = try {
            Class.forName("com.intellij.refactoring.changeSignature.ParameterInfoImpl")
        } catch (_: ClassNotFoundException) {
            return createErrorResult("ParameterInfoImpl not available — requires Java plugin.")
        }

        if (!dryRun) syncProjectForRefactoring(project)

        // VFS discovery can refresh a newly created external file and must precede the read
        // action. PSI resolution and writable checks stay inside the subsequent read action.
        val coordinateFile = if (lookupMode == LookupModeState.POSITION) {
            optionalStringArg(arguments, ParamNames.FILE)?.let { resolveFile(project, it) }
        } else null

        val prep = suspendingReadAction {
            when (lookupMode) {
                LookupModeState.SYMBOL_ID,
                LookupModeState.SYMBOL -> prepareChangeBySemanticTarget(
                    project,
                    arguments,
                    requireWritable = !dryRun
                )

                LookupModeState.POSITION -> {
                    val filePath = optionalStringArg(arguments, ParamNames.FILE)
                        ?: return@suspendingReadAction Result.failure<SignaturePreparation>(
                            IllegalArgumentException("Missing required parameter: ${ParamNames.FILE}")
                        )
                    val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
                        ?: return@suspendingReadAction Result.failure<SignaturePreparation>(
                            IllegalArgumentException("Missing required parameter: ${ParamNames.LINE}")
                        )
                    val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
                        ?: return@suspendingReadAction Result.failure<SignaturePreparation>(
                            IllegalArgumentException("Missing required parameter: ${ParamNames.COLUMN}")
                        )
                    val virtualFile = coordinateFile
                        ?: return@suspendingReadAction Result.failure<SignaturePreparation>(
                            IllegalArgumentException("File not found: $filePath")
                        )
                    if (!dryRun && !virtualFile.isWritable) {
                        return@suspendingReadAction Result.failure<SignaturePreparation>(
                            IllegalArgumentException("File is read-only and cannot be modified: ${virtualFile.path}")
                        )
                    }
                    prepareChange(project, virtualFile, filePath, line, column, requireWritable = !dryRun)
                }

                LookupModeState.CONFLICT -> Result.failure(
                    IllegalArgumentException(
                        if (requestedSymbolId != null) {
                            ErrorMessages.SYMBOL_ID_AND_OTHER_TARGET_EXCLUSIVE
                        } else {
                            ErrorMessages.LANGUAGE_SYMBOL_AND_OTHER_TARGET_EXCLUSIVE
                        }
                    )
                )

                LookupModeState.MISSING -> Result.failure(
                    IllegalArgumentException("Missing required parameter: ${ParamNames.FILE}")
                )
            }
        }

        return when {
            prep.isFailure -> createErrorResult(prep.exceptionOrNull()?.message ?: "Failed to prepare change")
            else -> {
                val p = prep.getOrThrow()
                if (dryRun) {
                    previewChange(
                        project = project,
                        prep = p,
                        newName = newName,
                        newReturnType = newReturnType,
                        newVisibility = newVisibility,
                        newParametersJson = newParametersJson,
                        generateDelegate = generateDelegate,
                        javaChangeInfoImplClass = javaChangeInfoImplClass,
                        parameterInfoImplClass = parameterInfoImplClass,
                        requestedSymbolId = requestedSymbolId,
                        startedAtNanos = startedAtNanos
                    )
                } else {
                    applyChange(
                        project, p, newName, newReturnType, newVisibility, newParametersJson,
                        generateDelegate, changeSignatureProcessorClass, javaChangeInfoImplClass,
                        parameterInfoImplClass, requestedSymbolId
                    )
                }
            }
        }
    }

    private fun prepareChangeBySemanticTarget(
        project: Project,
        arguments: JsonObject,
        requireWritable: Boolean
    ): Result<SignaturePreparation> {
        val element = resolveElementFromArguments(project, arguments, allowSymbolId = true).getOrElse {
            return Result.failure(it)
        }
        val method = resolveSignatureMethod(element, allowParent = false)
            ?: return Result.failure(
                IllegalArgumentException(
                    "Target does not identify a Java method or Kotlin function. Select a method declaration."
                )
            )
        return prepareMethod(project, method, requireWritable)
    }

    private fun prepareMethod(
        project: Project,
        method: PsiMethod,
        requireWritable: Boolean
    ): Result<SignaturePreparation> {
        // Kotlin overrides may select a base declaration in a different source file.
        val virtualFile = method.navigationElement.containingFile?.virtualFile
            ?: return Result.failure(
                IllegalArgumentException(
                    "Target has no editable source file"
                )
            )
        if (requireWritable && !virtualFile.isWritable) {
            return Result.failure(IllegalArgumentException("File is read-only and cannot be modified: ${virtualFile.path}"))
        }
        return Result.success(
            SignaturePreparation(
                method,
                ProjectUtils.getToolFilePath(project, virtualFile),
                virtualFile.isWritable
            )
        )
    }

    private fun prepareChange(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        filePath: String,
        line: Int,
        column: Int,
        requireWritable: Boolean
    ): Result<SignaturePreparation> {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return Result.failure(Exception("Cannot resolve PSI for: $filePath"))

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return Result.failure(Exception("Cannot get document for: $filePath"))

        if (line < 1 || line > document.lineCount) {
            return Result.failure(Exception("Line $line is out of range (file has ${document.lineCount} lines)"))
        }

        val offset = document.getLineStartOffset(line - 1) + (column - 1).coerceAtLeast(0)
        val element = psiFile.findElementAt(offset)
            ?: return Result.failure(Exception("No element found at line $line, column $column"))

        val method = resolveSignatureMethod(element, allowParent = true)
            ?: return Result.failure(Exception("No method found at line $line, column $column. Position the cursor on a method name."))

        return prepareMethod(project, method, requireWritable)
    }

    /** Kotlin's change-signature integration accepts JavaChangeInfo backed by a light method. */
    private fun resolveSignatureMethod(element: PsiElement, allowParent: Boolean): PsiMethod? {
        val javaMethod = if (allowParent) PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
            else element as? PsiMethod
        val source = (javaMethod ?: element).navigationElement
        val functionClass = try {
            Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
        } catch (_: ClassNotFoundException) {
            return javaMethod
        }
        val function = if (allowParent) generateSequence(source) { it.parent }.firstOrNull(functionClass::isInstance)
            else source.takeIf(functionClass::isInstance)
        if (function == null) return javaMethod
        val lightMethod = PsiUtils.toLightMethodsStrict(function).firstOrNull() ?: return null
        // Resolve the base off the EDT instead of entering Kotlin's interactive target chooser.
        return lightMethod.findDeepestSuperMethods().firstOrNull() ?: lightMethod
    }

    private suspend fun previewChange(
        project: Project,
        prep: SignaturePreparation,
        newName: String?,
        newReturnType: String?,
        newVisibility: String?,
        newParametersJson: kotlinx.serialization.json.JsonArray?,
        generateDelegate: Boolean,
        javaChangeInfoImplClass: Class<*>,
        parameterInfoImplClass: Class<*>,
        requestedSymbolId: String?,
        startedAtNanos: Long
    ): CallToolResult {
        return try {
            val (changeInfo, verification) = suspendingReadAction {
                buildChangeInfo(
                    project,
                    prep.method,
                    newName,
                    newReturnType,
                    newVisibility,
                    newParametersJson,
                    generateDelegate,
                    javaChangeInfoImplClass,
                    parameterInfoImplClass
                )
            }
            val warnings = mutableListOf<String>()
            val affectedFiles = linkedSetOf(prep.relativePath)
            var usages = emptyArray<UsageInfo>()
            var conflicts = emptyList<String>()
            var discoveryComplete = true

            try {
                previewUsageSearchHook?.invoke()
                usages = RefactoringScopeGuard.computeUsagesOffEdtStrict(project) {
                    RefactoringScopeGuard.findChangeSignatureUsagesReflectivelyStrict(changeInfo)
                }
                // ChangeSignatureProcessorBase delegates extension conflicts through
                // ActionUtil.underModalProgress. It must not be entered while this coroutine
                // owns a read lock: the modal worker may itself need read/write access and the
                // EDT then deadlocks waiting for that worker. Running it on EDT without an
                // enclosing read action matches the platform processor's own call path.
                val conflictResult = edtAction {
                    RefactoringScopeGuard.collectChangeSignatureConflictsReflectively(
                        changeInfo,
                        usages
                    )
                }
                // Conflict extensions receive a mutable Ref<Array<UsageInfo>>, but Java's
                // processor restores this original usage snapshot before apply. The helper keeps
                // preview metadata aligned with that actual edit scope.
                usages = conflictResult.usages
                val scopeMetadata = suspendingReadAction {
                    val usageFiles = usages.mapNotNullTo(linkedSetOf()) { usage ->
                        usage.virtualFile?.let { ProjectUtils.getToolFilePath(project, it) }
                    }
                    usageFiles to RefactoringScopeGuard.readOnlyFilesIn(project, usages)
                }
                conflicts = conflictResult.conflicts
                affectedFiles.addAll(scopeMetadata.first)
                warnings.addAll(conflicts)
                if (scopeMetadata.second.isNotEmpty()) {
                    warnings.add(RefactoringScopeGuard.blockedMessage(scopeMetadata.second))
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                discoveryComplete = false
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                warnings.add(
                    "Usage/conflict discovery failed: ${cause.message ?: cause.javaClass.simpleName}. " +
                        "The preview is not safe to apply."
                )
            }

            if (!prep.writable) {
                warnings.add("Target file is read-only; the change signature operation cannot be applied.")
            }
            val safetyWarnings = applicabilityWarnings(
                verification,
                usages,
                newParametersJson,
                generateDelegate
            )
            warnings.addAll(safetyWarnings)
            val target = suspendingReadAction {
                val source = PsiUtils.resolveNavigationTarget(prep.method)
                val matchingId = requestedSymbolId?.takeIf { id ->
                    SymbolIdRegistry.getInstance().resolve(project, id).getOrNull()
                        ?.let(PsiUtils::resolveNavigationTarget) == source
                }
                // A preview of a base declaration must not rebind an override's live handle.
                resolvedSymbolInfo(project, source, matchingId)
            }
            val hasReadOnlyScope = warnings.any { it.startsWith("Blocked by read-only files") }
            createJsonResult(
                refactoringPreview(
                    canApply = discoveryComplete && prep.writable && !hasReadOnlyScope &&
                        conflicts.isEmpty() && safetyWarnings.isEmpty(),
                    target = target,
                    plannedChange = buildJsonObject {
                        put("operation", "changeSignature")
                        put("before", signatureStateJson(verification.before))
                        put("requested", buildJsonObject {
                            newName?.let { put("newName", it) }
                            newReturnType?.let { put("newReturnType", it) }
                            newVisibility?.let { put("newVisibility", it) }
                            newParametersJson?.let { put("newParameters", it) }
                            put("generateDelegate", generateDelegate)
                        })
                    },
                    affectedFiles = affectedFiles,
                    usageCount = usages.size,
                    conflictCount = conflicts.size,
                    warnings = warnings,
                    startedAtNanos = startedAtNanos
                )
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            createErrorResult(
                "Change signature preview failed: ${cause.message}",
                ToolNames.DIAGNOSTICS
            )
        }
    }

    private fun signatureStateJson(state: SignatureState): JsonObject = buildJsonObject {
        put("name", state.name)
        state.returnTypeText?.let { put("returnType", it) }
        put("visibility", state.visibility)
        put("parameters", buildJsonArray {
            for ((name, type) in state.parameters) {
                add(buildJsonObject {
                    put("name", name)
                    put("type", type)
                })
            }
        })
    }

    private fun buildChangeInfo(
        project: Project,
        method: PsiMethod,
        newName: String?,
        newReturnType: String?,
        newVisibility: String?,
        newParametersJson: kotlinx.serialization.json.JsonArray?,
        generateDelegate: Boolean,
        javaChangeInfoImplClass: Class<*>,
        parameterInfoImplClass: Class<*>
    ): Pair<Any, SignatureVerification> {
        val factory = JavaPsiFacade.getElementFactory(project)
        val effectiveName = newName ?: method.name
        val canonicalTypesClass = Class.forName("com.intellij.refactoring.util.CanonicalTypes")
        val createMethod = canonicalTypesClass.getMethod("createTypeWrapper", PsiType::class.java)

        val requestedReturnPsiType = newReturnType?.let { factory.createTypeFromText(it, method) }
        val effectiveReturnType = if (requestedReturnPsiType != null) {
            createMethod.invoke(null, requestedReturnPsiType)
        } else {
            method.returnType?.let { createMethod.invoke(null, it) }
        }
        val effectiveVisibility = when (newVisibility) {
            "public" -> PsiModifier.PUBLIC
            "protected" -> PsiModifier.PROTECTED
            "private" -> PsiModifier.PRIVATE
            "package-private", "package-local" -> PsiModifier.PACKAGE_LOCAL
            else -> currentVisibility(method)
        }
        val paramInfos = if (newParametersJson != null) {
            buildParameterInfos(method, newParametersJson, parameterInfoImplClass, factory).getOrThrow()
        } else {
            buildCurrentParameterInfos(method, parameterInfoImplClass)
        }

        val thrownExceptions = extractThrownExceptions(method)
        val canonicalTypeClass = Class.forName("com.intellij.refactoring.util.CanonicalTypes\$Type")
        val constructor = javaChangeInfoImplClass.getConstructor(
            String::class.java,
            PsiMethod::class.java,
            String::class.java,
            canonicalTypeClass,
            paramInfos.javaClass,
            thrownExceptions.javaClass,
            Boolean::class.java,
            Set::class.java,
            Set::class.java
        )
        val changeInfo = constructor.newInstance(
            effectiveVisibility,
            method,
            effectiveName,
            effectiveReturnType,
            paramInfos,
            thrownExceptions,
            generateDelegate,
            emptySet<PsiMethod>(),
            emptySet<PsiMethod>()
        )
        val verification = SignatureVerification(
            pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method.navigationElement),
            before = captureSignatureState(method),
            targetName = newName,
            targetReturnTypeText = requestedReturnPsiType?.canonicalText,
            targetVisibility = if (newVisibility != null) effectiveVisibility else null,
            targetParameters = newParametersJson?.map { parameterJson ->
                val parameter = parameterJson.jsonObject
                val parameterName = parameter["name"]!!.jsonPrimitive.content
                val parameterType = factory.createTypeFromText(
                    parameter["type"]!!.jsonPrimitive.content,
                    method
                )
                parameterName to parameterType.canonicalText
            }
        )
        return changeInfo to verification
    }

    /**
     * Keeps Java's existing throws contract when an unrelated part of a signature changes.
     *
     * The Java plugin owns the concrete exception-info representation. In particular, current
     * platform versions use a `PsiClassType` constructor rather than the historical `PsiType`
     * one, so synthesising these objects reflectively is version-fragile. Use its public helper
     * instead while keeping the Java-plugin dependency optional at class-load time.
     *
     * Do not substitute an empty array when this fails: applying that fallback silently removes
     * source-level throws declarations. Unwrap cancellation and errors so normal IDE control flow
     * and fatal failures retain their usual semantics.
     */
    private fun extractThrownExceptions(method: PsiMethod): Any {
        val javaThrownExceptionInfoClass = Class.forName(
            "com.intellij.refactoring.changeSignature.JavaThrownExceptionInfo"
        )
        val extractMethod = javaThrownExceptionInfoClass.getMethod(
            "extractExceptions",
            PsiMethod::class.java
        )
        return try {
            requireNotNull(extractMethod.invoke(null, method)) {
                "JavaThrownExceptionInfo.extractExceptions returned null"
            }
        } catch (exception: java.lang.reflect.InvocationTargetException) {
            throw exception.cause ?: exception
        }
    }

    private fun instantiateProcessor(
        project: Project,
        changeSignatureProcessorClass: Class<*>,
        changeInfo: Any
    ): BaseRefactoringProcessor {
        val changeInfoClass = Class.forName("com.intellij.refactoring.changeSignature.JavaChangeInfo")
        return changeSignatureProcessorClass
            .getConstructor(Project::class.java, changeInfoClass)
            .newInstance(project, changeInfo) as BaseRefactoringProcessor
    }

    private suspend fun applyChange(
        project: Project,
        prep: SignaturePreparation,
        newName: String?,
        newReturnType: String?,
        newVisibility: String?,
        newParametersJson: kotlinx.serialization.json.JsonArray?,
        generateDelegate: Boolean,
        changeSignatureProcessorClass: Class<*>,
        javaChangeInfoImplClass: Class<*>,
        parameterInfoImplClass: Class<*>,
        requestedSymbolId: String?
    ): CallToolResult {
        return try {
            val method = prep.method

            val (changeInfo, verification) = suspendingReadAction {
                buildChangeInfo(
                    project,
                    method,
                    newName,
                    newReturnType,
                    newVisibility,
                    newParametersJson,
                    generateDelegate,
                    javaChangeInfoImplClass,
                    parameterInfoImplClass
                )
            }

            val affectedFiles = mutableSetOf<String>()
            var notSaved: List<String> = emptyList()

            edtAction {
                val docManager = FileDocumentManager.getInstance()
                val unsavedBefore = docManager.unsavedDocuments.toSet()

                val processor = instantiateProcessor(project, changeSignatureProcessorClass, changeInfo)

                processor.setPreviewUsages(false)

                // Pre-check the full refactoring scope for read-only files (issue #310):
                // run() would route them through ReadonlyStatusHandler's modal dialog,
                // blocking the EDT in headless MCP sessions. Strict usage discovery
                // propagates failures. The search runs off
                // the EDT (issue #357): Kotlin call sites of the changed method are
                // searched through the Kotlin plugin, whose K2 Analysis API forbids
                // resolution on the EDT.
                val usages = RefactoringScopeGuard.computeUsagesOffEdtStrict(project) {
                    RefactoringScopeGuard.findChangeSignatureUsagesReflectivelyStrict(changeInfo)
                }
                val readOnlyInScope = ReadAction.compute<List<String>, RuntimeException> {
                    RefactoringScopeGuard.readOnlyFilesIn(project, usages)
                }
                if (readOnlyInScope.isNotEmpty()) {
                    throw Exception(RefactoringScopeGuard.blockedMessage(readOnlyInScope))
                }
                val conflicts = RefactoringScopeGuard.collectChangeSignatureConflictsReflectively(
                    changeInfo,
                    usages
                ).conflicts
                val warnings = applicabilityWarnings(
                    verification,
                    usages,
                    newParametersJson,
                    generateDelegate
                )
                if (conflicts.isNotEmpty() || warnings.isNotEmpty()) {
                    throw Exception(
                        "Change signature cannot be applied safely headlessly: " +
                            (conflicts + warnings).joinToString(" ") +
                            " Run the same request with dryRun=true and resolve the reported issues before applying."
                    )
                }

                val hook = processorRunHook
                if (hook != null) hook() else processor.run()

                PsiDocumentManager.getInstance(project).commitAllDocuments()

                val unsavedAfter = docManager.unsavedDocuments.toSet()
                val changedDocs = unsavedAfter - unsavedBefore
                for (doc in changedDocs) {
                    val vf = docManager.getFile(doc)
                    if (vf != null) {
                        affectedFiles.add(ProjectUtils.getToolFilePath(project, vf))
                    }
                }
                affectedFiles.add(prep.relativePath)

                notSaved = saveChangedDocuments(project, unsavedBefore)
            }
            if (notSaved.isNotEmpty()) return createErrorResult(changesNotSavedMessage(notSaved))

            val requestedChangeApplied = suspendingReadAction {
                anyRequestedAspectApplied(verification)
            }

            if (!requestedChangeApplied) {
                createErrorResult(
                    "Change signature did not apply — the IDE aborted the refactoring " +
                        "(read-only file, unwritable elements, or indexing in progress)."
                )
            } else {
                val updatedSymbol = suspendingReadAction {
                    val originalTarget = requestedSymbolId?.let {
                        SymbolIdRegistry.getInstance().resolve(project, it).getOrNull()
                    }
                    if (originalTarget != null) {
                        resolvedSymbolInfo(project, originalTarget, requestedSymbolId)
                    } else {
                        verification.pointer.element?.let { resolvedSymbolInfo(project, it) }
                    }
                }
                createJsonResult(ChangeSignatureResult(
                    success = true,
                    file = prep.relativePath,
                    message = "Changed signature of '${verification.targetName ?: verification.before.name}'",
                    affectedFiles = affectedFiles.toList(),
                    changesCount = affectedFiles.size,
                    updatedSymbol = updatedSymbol
                ))
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            createErrorResult("Change signature failed: ${cause.message}", ToolNames.DIAGNOSTICS)
        }
    }

    private fun buildParameterInfos(
        method: PsiMethod,
        parametersJson: kotlinx.serialization.json.JsonArray,
        parameterInfoImplClass: Class<*>,
        factory: PsiElementFactory
    ): Result<Any> {
        val infos = mutableListOf<Any>()

        for (paramJson in parametersJson) {
            val obj = paramJson.jsonObject
            val oldIndex = obj["oldIndex"]?.jsonPrimitive?.int
                ?: return Result.failure(Exception("Each parameter must have 'oldIndex' (int, -1 for new)"))
            val name = obj["name"]?.jsonPrimitive?.content
                ?: return Result.failure(Exception("Each parameter must have 'name'"))
            val typeStr = obj["type"]?.jsonPrimitive?.content
                ?: return Result.failure(Exception("Each parameter must have 'type'"))
            val defaultValue = obj["defaultValue"]?.jsonPrimitive?.content ?: ""

            val type = try {
                factory.createTypeFromText(typeStr, method)
            } catch (e: Exception) {
                return Result.failure(Exception("Invalid parameter type: '$typeStr'. ${e.message}"))
            }

            val info = parameterInfoImplClass.getConstructor(
                Int::class.java, String::class.java, PsiType::class.java, String::class.java
            ).newInstance(oldIndex, name, type, defaultValue)

            infos.add(info)
        }

        val array = java.lang.reflect.Array.newInstance(parameterInfoImplClass, infos.size)
        infos.forEachIndexed { i, info -> java.lang.reflect.Array.set(array, i, info) }
        return Result.success(array)
    }

    private fun currentVisibility(method: PsiMethod): String = when {
        method.hasModifierProperty(PsiModifier.PUBLIC) -> PsiModifier.PUBLIC
        method.hasModifierProperty(PsiModifier.PROTECTED) -> PsiModifier.PROTECTED
        method.hasModifierProperty(PsiModifier.PRIVATE) -> PsiModifier.PRIVATE
        else -> PsiModifier.PACKAGE_LOCAL
    }

    private fun hasNewParameterWithoutDefault(
        parametersJson: kotlinx.serialization.json.JsonArray?
    ): Boolean = parametersJson?.any { parameterJson ->
        val parameter = parameterJson.jsonObject
        parameter["oldIndex"]?.jsonPrimitive?.int == -1 &&
            parameter["type"]?.jsonPrimitive?.contentOrNull?.trim()?.endsWith("...") != true &&
            parameter["defaultValue"]?.jsonPrimitive?.contentOrNull?.isNotBlank() != true
    } == true

    private fun applicabilityWarnings(
        verification: SignatureVerification,
        usages: Array<UsageInfo>,
        newParametersJson: kotlinx.serialization.json.JsonArray?,
        generateDelegate: Boolean
    ): List<String> {
        val warnings = mutableListOf<String>()
        // Java exposes OverriderMethodUsageInfo for declarations whose signatures move with the
        // base method. Every other usage may need an inserted argument at an actual call site.
        val hasOverriderUsages = usages.any { it is OverriderMethodUsageInfo<*> }
        val needsInsertedArguments = generateDelegate || usages.any { it !is OverriderMethodUsageInfo<*> }
        if (needsInsertedArguments && hasNewParameterWithoutDefault(newParametersJson)) {
            warnings.add(
                if (generateDelegate) {
                    "Generating a delegate requires an explicit non-blank defaultValue for every new required parameter."
                } else {
                    "A new parameter has no explicit non-blank defaultValue while call sites exist; " +
                        "the IDE would leave required arguments missing, producing non-compiling callers."
                }
            )
        }

        val narrowsVisibility = hasOverriderUsages &&
            verification.targetVisibility?.let { requestedVisibility ->
                val requestedRank = visibilityRank(requestedVisibility)
                val currentRank = visibilityRank(verification.before.visibility)
                requestedRank >= 0 && currentRank >= 0 && requestedRank < currentRank
            } == true
        if (narrowsVisibility) {
            warnings.add(
                "Narrowing visibility while overriding methods exist may require an interactive confirmation."
            )
        }

        val changesReturnTypeWithOverriders = hasOverriderUsages &&
            verification.targetReturnTypeText?.let { it != verification.before.returnTypeText } == true
        if (changesReturnTypeWithOverriders) {
            warnings.add(
                "Changing the return type while overriding methods exist may require an interactive " +
                    "covariant-overrider choice. This guard conservatively rejects all return-type changes " +
                    "with overriders, including ones whose narrower return types could safely be retained."
            )
        }
        return warnings
    }

    private fun visibilityRank(visibility: String): Int = when (visibility) {
        PsiModifier.PRIVATE -> 0
        PsiModifier.PACKAGE_LOCAL, "package-private", "package-local" -> 1
        PsiModifier.PROTECTED -> 2
        PsiModifier.PUBLIC -> 3
        else -> -1
    }

    private fun captureSignatureState(method: PsiMethod): SignatureState = SignatureState(
        name = method.name,
        returnTypeText = method.returnType?.canonicalText,
        visibility = currentVisibility(method).let {
            if (it == PsiModifier.PACKAGE_LOCAL) "package-private" else it
        },
        parameters = method.parameterList.parameters.map { it.name to it.type.canonicalText }
    )

    /**
     * Conservative post-run check: an aspect counts as applied when the method now matches the
     * request, or when it changed from the pre-run state at all (so any partial or
     * differently-rendered application still counts). Only when every requested aspect is
     * provably untouched — the signature of a silently aborted refactoring — is the run treated
     * as failed. A pointer that no longer resolves means the refactoring restructured the PSI,
     * which also counts as applied.
     */
    private fun anyRequestedAspectApplied(verification: SignatureVerification): Boolean {
        val source = verification.pointer.element ?: return true
        // Kotlin light methods cache the old signature. Resolve a fresh JVM view from the source.
        val method = resolveSignatureMethod(source, allowParent = false) ?: return true
        val after = captureSignatureState(method)
        val before = verification.before
        val aspects = listOfNotNull(
            verification.targetName?.let {
                after.name == it || after.name != before.name
            },
            verification.targetReturnTypeText?.let {
                after.returnTypeText == it || after.returnTypeText != before.returnTypeText
            },
            verification.targetVisibility?.let {
                visibilityRank(after.visibility) == visibilityRank(it) || after.visibility != before.visibility
            },
            verification.targetParameters?.let {
                after.parameters == it || after.parameters != before.parameters
            }
        )
        return aspects.isEmpty() || aspects.any { it }
    }

    private fun buildCurrentParameterInfos(method: PsiMethod, parameterInfoImplClass: Class<*>): Any {
        val params = method.parameterList.parameters
        val array = java.lang.reflect.Array.newInstance(parameterInfoImplClass, params.size)
        params.forEachIndexed { i, param ->
            val info = parameterInfoImplClass.getConstructor(
                Int::class.java, String::class.java, PsiType::class.java, String::class.java
            ).newInstance(i, param.name, param.type, "")
            java.lang.reflect.Array.set(array, i, info)
        }
        return array
    }
}
