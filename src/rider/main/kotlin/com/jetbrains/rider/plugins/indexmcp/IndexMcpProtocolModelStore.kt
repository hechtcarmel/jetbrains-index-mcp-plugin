package com.jetbrains.rider.plugins.indexmcp

import com.intellij.openapi.project.Project
import com.jetbrains.rider.plugins.indexmcp.model.IndexMcpModel
import java.util.concurrent.ConcurrentHashMap

object IndexMcpProtocolModelStore {
    private val models = ConcurrentHashMap<Project, IndexMcpModel>()

    @JvmStatic
    fun put(project: Project, model: IndexMcpModel) {
        models[project] = model
    }

    @JvmStatic
    fun get(project: Project): Any? = models[project]

    @JvmStatic
    fun remove(project: Project) {
        models.remove(project)
    }
}
