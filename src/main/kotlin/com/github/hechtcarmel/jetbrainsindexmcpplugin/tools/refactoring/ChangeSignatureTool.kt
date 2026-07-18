package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChangeSignatureTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<ChangeSignatureTool>()
    }

    override val name = ToolNames.CHANGE_SIGNATURE

    override val description = """
        Change a method's signature and automatically update all callers, overrides, and implementations.

        Can modify: method name, return type, visibility, parameters (add, remove, reorder, change types).
        New parameters get a default value inserted at all call sites.

        Examples:
        - Add parameter: {"file": "src/Service.java", "line": 15, "column": 10, "newParameters": [{"oldIndex": 0, "name": "id", "type": "String"}, {"oldIndex": -1, "name": "validate", "type": "boolean", "defaultValue": "true"}]}
        - Change return type: {"file": "src/Service.java", "line": 15, "column": 10, "newReturnType": "Optional<User>"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file containing the method. REQUIRED.")
        .lineAndColumn(required = true)
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
        .build()

    @Serializable
    data class ChangeSignatureResult(
        val success: Boolean,
        val file: String,
        val message: String,
        val affectedFiles: List<String> = emptyList(),
        val changesCount: Int = 0
    )

    private data class SignaturePreparation(
        val method: PsiMethod,
        val relativePath: String
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")

        val newName = arguments[ParamNames.NEW_NAME]?.jsonPrimitive?.content
        val newReturnType = arguments[ParamNames.NEW_RETURN_TYPE]?.jsonPrimitive?.content
        val newVisibility = arguments[ParamNames.NEW_VISIBILITY]?.jsonPrimitive?.content
        val newParametersJson = arguments[ParamNames.NEW_PARAMETERS]?.jsonArray
        val generateDelegate = arguments[ParamNames.GENERATE_DELEGATE]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        if (newName == null && newReturnType == null && newVisibility == null && newParametersJson == null) {
            return createErrorResult("At least one change is required: newName, newReturnType, newVisibility, or newParameters.")
        }

        if (newVisibility != null && newVisibility !in listOf("public", "protected", "private", "package-private", "package-local")) {
            return createErrorResult("Invalid visibility: '$newVisibility'. Must be: public, protected, private, or package-private.")
        }

        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")

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

        val prep = suspendingReadAction {
            prepareChange(project, virtualFile, filePath, line, column)
        }

        return when {
            prep.isFailure -> createErrorResult(prep.exceptionOrNull()?.message ?: "Failed to prepare change")
            else -> {
                val p = prep.getOrThrow()
                applyChange(
                    project, p, newName, newReturnType, newVisibility, newParametersJson,
                    generateDelegate, changeSignatureProcessorClass, javaChangeInfoImplClass, parameterInfoImplClass
                )
            }
        }
    }

    private fun prepareChange(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        filePath: String,
        line: Int,
        column: Int
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

        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            ?: return Result.failure(Exception("No method found at line $line, column $column. Position the cursor on a method name."))

        val relativePath = ProjectUtils.getToolFilePath(project, virtualFile)
        return Result.success(SignaturePreparation(method, relativePath))
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
        parameterInfoImplClass: Class<*>
    ): ToolCallResult {
        return try {
            val method = prep.method

            data class ChangeModel(
                val effectiveName: String,
                val effectiveReturnType: Any?,
                val effectiveVisibility: String,
                val paramInfos: Any,
                val changeInfo: Any
            )

            val modelResult = suspendingReadAction {
                val factory = JavaPsiFacade.getElementFactory(project)

                val effectiveName = newName ?: method.name
                val canonicalTypesClass = Class.forName("com.intellij.refactoring.util.CanonicalTypes")
                val createMethod = canonicalTypesClass.getMethod("createTypeWrapper", PsiType::class.java)

                val effectiveReturnType = if (newReturnType != null) {
                    val psiType = factory.createTypeFromText(newReturnType, method)
                    createMethod.invoke(null, psiType)
                } else {
                    if (method.returnType != null) createMethod.invoke(null, method.returnType) else null
                }

                val effectiveVisibility = when (newVisibility) {
                    "public" -> PsiModifier.PUBLIC
                    "protected" -> PsiModifier.PROTECTED
                    "private" -> PsiModifier.PRIVATE
                    "package-private", "package-local" -> PsiModifier.PACKAGE_LOCAL
                    else -> when {
                        method.hasModifierProperty(PsiModifier.PUBLIC) -> PsiModifier.PUBLIC
                        method.hasModifierProperty(PsiModifier.PROTECTED) -> PsiModifier.PROTECTED
                        method.hasModifierProperty(PsiModifier.PRIVATE) -> PsiModifier.PRIVATE
                        else -> PsiModifier.PACKAGE_LOCAL
                    }
                }

                val paramInfos = if (newParametersJson != null) {
                    buildParameterInfos(method, newParametersJson, parameterInfoImplClass, factory)
                        .getOrThrow()
                } else {
                    buildCurrentParameterInfos(method, parameterInfoImplClass)
                }

                val changeInfoClass = Class.forName("com.intellij.refactoring.changeSignature.JavaChangeInfo")
                val thrownExceptionInfoClass = Class.forName("com.intellij.refactoring.changeSignature.ThrownExceptionInfo")
                val thrownExceptions = try {
                    val javaThrownExceptionInfoClass = Class.forName("com.intellij.refactoring.changeSignature.JavaThrownExceptionInfo")
                    val existingThrows = method.throwsList.referenceElements
                    if (existingThrows.isEmpty()) {
                        java.lang.reflect.Array.newInstance(thrownExceptionInfoClass, 0)
                    } else {
                        val arr = java.lang.reflect.Array.newInstance(thrownExceptionInfoClass, existingThrows.size)
                        for ((i, ref) in existingThrows.withIndex()) {
                            val psiType = ref.resolve()?.let { resolved ->
                                if (resolved is PsiClass) {
                                    JavaPsiFacade.getElementFactory(project).createType(resolved)
                                } else null
                            } ?: PsiElementFactory.getInstance(project).createTypeFromText(ref.qualifiedName, method)
                            val info = javaThrownExceptionInfoClass.getConstructor(Integer.TYPE, PsiType::class.java)
                                .newInstance(i, psiType)
                            java.lang.reflect.Array.set(arr, i, info)
                        }
                        arr
                    }
                } catch (e: Exception) {
                    LOG.warn("Could not preserve throws declarations, falling back to empty: ${e.message}")
                    java.lang.reflect.Array.newInstance(thrownExceptionInfoClass, 0)
                }
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

                constructor.newInstance(
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
            }

            val changeInfo = modelResult
            val changeInfoClass = Class.forName("com.intellij.refactoring.changeSignature.JavaChangeInfo")
            val affectedFiles = mutableSetOf<String>()

            edtAction {
                val docManager = FileDocumentManager.getInstance()
                val unsavedBefore = docManager.unsavedDocuments.toSet()

                val processor = changeSignatureProcessorClass
                    .getConstructor(Project::class.java, changeInfoClass)
                    .newInstance(project, changeInfo) as com.intellij.refactoring.BaseRefactoringProcessor

                processor.setPreviewUsages(false)
                processor.run()

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

                docManager.saveAllDocuments()
            }

            createJsonResult(ChangeSignatureResult(
                success = true,
                file = prep.relativePath,
                message = "Changed signature of '${method.name}'",
                affectedFiles = affectedFiles.toList(),
                changesCount = affectedFiles.size
            ))
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            createErrorResult("Change signature failed: ${cause.message}")
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
