package com.jetbrains.rider.plugins.indexmcp

import com.intellij.openapi.project.Project
import com.jetbrains.rd.ide.model.IndexMcpModel
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project store of the live [IndexMcpModel] published by the Rider rd protocol.
 *
 * The [get] accessor returns [Any] (not [IndexMcpModel]) so reflection consumers in the
 * IntelliJ Community plugin module — which compiles without a Rider classpath — can
 * load and call this object without resolving the rdgen-generated model type.
 */
object IndexMcpProtocolModelStore {
    private val models = ConcurrentHashMap<Project, IndexMcpModel>()

    @JvmStatic
    fun put(project: Project, model: IndexMcpModel) {
        models[project] = model
    }

    /**
     * Returns the model as [Any] so the reflection bridge in `RiderDotNetHandlers`
     * (compiled against IC, no Rider classes) can call this without classloader errors.
     */
    @JvmStatic
    fun get(project: Project): Any? = models[project]

    @JvmStatic
    fun remove(project: Project) {
        models.remove(project)
    }
}
