package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.JsonRpcHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.RepoScopeRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorSseSessionManager
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class RepoScopedAgentWorkspaceIntegrationTest : BasePlatformTestCase() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val httpClient = HttpClient.newHttpClient()

    private lateinit var registry: ToolRegistry
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var server: KtorMcpServer
    private var port: Int = 0

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        registry = ToolRegistry().also { it.registerBuiltInTools() }
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        port = ServerSocket(0).use { it.localPort }
        server = KtorMcpServer(
            port = port,
            jsonRpcHandler = JsonRpcHandler(registry),
            sseSessionManager = KtorSseSessionManager(),
            coroutineScope = coroutineScope
        )
        assertEquals(KtorMcpServer.StartResult.Success, server.start())
    }

    override fun tearDown() {
        try {
            server.stop()
            RepoScopeRegistry.getInstance().replaceOpenRoots(emptyList())
        } finally {
            coroutineScope.cancel()
            super.tearDown()
        }
    }

    fun testAttachConfigConflictAndDetachForNestedWorkspaceTopology() = runBlocking {
        val parent = Files.createTempDirectory("repo-scoped-workspace")
        val shippingRepo = parent.resolve("master-project/submodules/shipping-repo").toFile()
        assertTrue(shippingRepo.mkdirs() || shippingRepo.isDirectory)
        val repoPath = shippingRepo.path.replace('\\', '/')

        val attach = callTool(
            ToolNames.ATTACH_REPO_TO_WORKSPACE,
            buildJsonObject {
                put(ParamNames.REPO_PATH, repoPath)
            }
        )
        assertFalse("Attach should succeed", attach.isError)
        assertTrue("Attached repo should become a workspace content root", ProjectUtils.getModuleContentRoots(project).contains(repoPath))

        val config = callTool(ToolNames.GET_REPO_SCOPED_CLIENT_CONFIG, buildJsonObject { })
        val scopedServers = json.parseToJsonElement(config.text()).jsonObject["scopedServers"]!!.jsonArray.map { it.jsonObject }
        val shippingServer = scopedServers.firstOrNull { it["repoId"]?.jsonPrimitive?.content == "shipping-repo" }
        assertNotNull("Client config should publish the shipping repo scoped route", shippingServer)
        assertTrue(
            "Published scoped URL should target the repo route",
            shippingServer!!["streamableHttpUrl"]?.jsonPrimitive?.content?.endsWith("/index-mcp/repos/shipping-repo/streamable-http") == true
        )

        val scopedPing = postScoped("shipping-repo", """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertEquals(200, scopedPing.statusCode())

        val conflict = postScoped(
            "shipping-repo",
            json.encodeToString(
                JsonRpcRequest.serializer(),
                JsonRpcRequest(
                    id = JsonPrimitive(2),
                    method = "tools/call",
                    params = buildJsonObject {
                        put("name", ToolNames.INDEX_STATUS)
                        put("arguments", buildJsonObject {
                            put(ParamNames.PROJECT_PATH, parent.resolve("other-repo").toString().replace('\\', '/'))
                        })
                    }
                )
            )
        )
        val conflictResponse = json.decodeFromString<JsonRpcResponse>(conflict.body())
        val conflictResult = json.decodeFromJsonElement(ToolCallResult.serializer(), conflictResponse.result!!)
        assertTrue("Conflicting project_path should be rejected on repo scoped route", conflictResult.isError)
        assertTrue(conflictResult.text().contains("repo_scope_conflict"))

        val detach = callTool(
            ToolNames.DETACH_REPO_FROM_WORKSPACE,
            buildJsonObject {
                put(ParamNames.REPO_ID, "shipping-repo")
            }
        )
        assertFalse("Detach should succeed", detach.isError)
        assertFalse("Detached repo should leave workspace content roots", ProjectUtils.getModuleContentRoots(project).contains(repoPath))

        val afterDetach = postScoped("shipping-repo", """{"jsonrpc":"2.0","id":3,"method":"ping"}""")
        assertEquals("Detached repo route should no longer be published", 404, afterDetach.statusCode())
    }

    fun testRepoScopedReadFileUsesScopedRootWithDuplicatePaths() = runBlocking {
        withDuplicatePathTopology {
            val readResult = scopedToolCall(
                repoId = "beta-repo",
                toolName = ToolNames.READ_FILE,
                arguments = buildJsonObject {
                    put(ParamNames.FILE, "README.md")
                }
            )
            assertFalse("Scoped read_file should succeed", readResult.isError)
            assertTrue("read_file should read beta repo content", readResult.text().contains("BETA_ONLY"))
            assertFalse("read_file should not read sibling repo content", readResult.text().contains("ALPHA_ONLY"))
        }
    }

    fun testRepoScopedOpenFileUsesScopedRootWithDuplicatePaths() = runBlocking {
        withDuplicatePathTopology {
            val openResult = scopedToolCall(
                repoId = "beta-repo",
                toolName = ToolNames.OPEN_FILE,
                arguments = buildJsonObject {
                    put(ParamNames.FILE, "README.md")
                }
            )
            assertFalse("Scoped open_file should succeed", openResult.isError)
            assertTrue("open_file result should stay repo-relative", openResult.text().contains("\"file\":\"README.md\""))
        }
    }

    fun testRepoScopedOpenFileNotificationReturnsAcceptedWithDuplicatePaths() = runBlocking {
        withDuplicatePathTopology {
            val response = postScoped(
                "beta-repo",
                json.encodeToString(
                    JsonRpcRequest.serializer(),
                    JsonRpcRequest(
                        id = null,
                        method = "tools/call",
                        params = buildJsonObject {
                            put("name", ToolNames.OPEN_FILE)
                            put("arguments", buildJsonObject {
                                put(ParamNames.FILE, "README.md")
                            })
                        }
                    )
                )
            )

            assertEquals("Scoped open_file notification should return 202", 202, response.statusCode())
            assertTrue("Scoped open_file notification should have an empty body", response.body().isBlank())
        }
    }

    fun testRepoScopedFileStructureUsesScopedRootWithDuplicatePaths() = runBlocking {
        withDuplicatePathTopology {
            val structureResult = scopedToolCall(
                repoId = "beta-repo",
                toolName = ToolNames.FILE_STRUCTURE,
                arguments = buildJsonObject {
                    put(ParamNames.FILE, "docs/outline.md")
                }
            )
            assertFalse("Scoped file_structure should succeed", structureResult.isError)
            assertTrue("file_structure should inspect beta repo markdown", structureResult.text().contains("BETA_ONLY"))
            assertFalse("file_structure should not inspect alpha repo markdown", structureResult.text().contains("ALPHA_ONLY"))
        }
    }

    fun testRepoScopedFindFileUsesScopedRootWithDuplicatePaths() = runBlocking {
        withDuplicatePathTopology {
            val findResult = scopedToolCall(
                repoId = "beta-repo",
                toolName = ToolNames.FIND_FILE,
                arguments = buildJsonObject {
                    put(ParamNames.QUERY, "README.md")
                    put("pageSize", 10)
                }
            )
            assertFalse("Scoped find_file should succeed", findResult.isError)
            assertTrue("find_file should include scoped repo README", findResult.text().contains("\"path\":\"README.md\""))
            assertFalse("find_file should not include alpha repo path", findResult.text().contains("alpha-repo"))
        }
    }

    private suspend fun callTool(toolName: String, arguments: kotlinx.serialization.json.JsonObject): ToolCallResult {
        val request = JsonRpcRequest(
            id = JsonPrimitive(10),
            method = "tools/call",
            params = buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            }
        )
        val responseJson = JsonRpcHandler(registry).handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertNull("Tool call should not return JSON-RPC error", response.error)
        return json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
    }

    private suspend fun attachRepo(repoPath: Path) {
        val result = callTool(
            ToolNames.ATTACH_REPO_TO_WORKSPACE,
            buildJsonObject {
                put(ParamNames.REPO_PATH, repoPath.toString().replace('\\', '/'))
            }
        )
        assertFalse("Attach should succeed for $repoPath", result.isError)
    }

    private suspend fun createDuplicatePathTopology(): DuplicatePathTopology {
        val parent = Files.createTempDirectory("repo-safe-scope")
        val alpha = createRepoWithDuplicateFiles(parent, "alpha-repo", "ALPHA_ONLY")
        val beta = createRepoWithDuplicateFiles(parent, "beta-repo", "BETA_ONLY")

        attachRepo(alpha)
        attachRepo(beta)
        DumbService.getInstance(project).waitForSmartMode()
        return DuplicatePathTopology(alpha = alpha, beta = beta)
    }

    private suspend fun withDuplicatePathTopology(block: suspend () -> Unit) {
        val topology = createDuplicatePathTopology()
        try {
            block()
        } finally {
            closeOpenFilesUnder(topology.beta, topology.alpha)
            detachRepo("beta-repo")
            detachRepo("alpha-repo")
        }
    }

    private fun closeOpenFilesUnder(vararg roots: Path) {
        val normalizedRoots = roots.map { it.toAbsolutePath().normalize().toString().replace('\\', '/') }
        ApplicationManager.getApplication().invokeAndWait {
            val editorManager = FileEditorManager.getInstance(project)
            editorManager.openFiles
                .filter { openFile ->
                    val filePath = openFile.path.replace('\\', '/')
                    normalizedRoots.any { root -> filePath == root || filePath.startsWith("$root/") }
                }
                .forEach { editorManager.closeFile(it) }
        }
    }

    private suspend fun detachRepo(repoId: String) {
        val result = callTool(
            ToolNames.DETACH_REPO_FROM_WORKSPACE,
            buildJsonObject {
                put(ParamNames.REPO_ID, repoId)
            }
        )
        assertFalse("Detach should succeed for $repoId", result.isError)
    }

    private suspend fun scopedToolCall(
        repoId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject
    ): ToolCallResult {
        val request = JsonRpcRequest(
            id = JsonPrimitive(20),
            method = "tools/call",
            params = buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            }
        )
        val response = postScoped(repoId, json.encodeToString(JsonRpcRequest.serializer(), request))
        assertEquals("Scoped tool call should return HTTP 200", 200, response.statusCode())
        val rpcResponse = json.decodeFromString<JsonRpcResponse>(response.body())
        assertNull("Scoped tool call should not return JSON-RPC error", rpcResponse.error)
        return json.decodeFromJsonElement(ToolCallResult.serializer(), rpcResponse.result!!)
    }

    private fun createRepoWithDuplicateFiles(parent: Path, repoName: String, marker: String): Path {
        val repo = parent.resolve(repoName)
        Files.createDirectories(repo.resolve("docs"))
        Files.writeString(repo.resolve("README.md"), "$marker readme\n")
        Files.writeString(repo.resolve("docs/outline.md"), "# $marker\n")
        return repo
    }

    private data class DuplicatePathTopology(
        val alpha: Path,
        val beta: Path
    )

    private suspend fun postScoped(repoId: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:$port${McpConstants.MCP_ENDPOINT_PATH}/repos/$repoId/streamable-http")
        )
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    private fun ToolCallResult.text(): String =
        (content.firstOrNull() as? com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock.Text)?.text.orEmpty()
}
