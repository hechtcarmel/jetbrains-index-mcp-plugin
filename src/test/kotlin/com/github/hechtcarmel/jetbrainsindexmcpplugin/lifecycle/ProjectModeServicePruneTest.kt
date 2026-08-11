package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import java.util.concurrent.ConcurrentHashMap

class ProjectModeServicePruneTest : McpPlatformTestCase() {

    fun testLoadStatePrunesNonExistentPaths() {
        val service = ProjectModeService()
        val existingPath = System.getProperty("user.home")
        val ghostPath = "/tmp/does-not-exist-lifecycle-test-${System.nanoTime()}"

        val state = ProjectModeService.State(
            closedProjectPaths = ConcurrentHashMap.newKeySet<String>().apply {
                add(existingPath)
                add(ghostPath)
            },
            managedProjectPaths = ConcurrentHashMap.newKeySet<String>().apply {
                add(existingPath)
                add(ghostPath)
            }
        )

        service.loadState(state)

        assertTrue("Existing path should be kept in managedProjectPaths",
            service.isManaged(existingPath))
        assertFalse("Ghost path should be pruned from managedProjectPaths",
            service.isManaged(ghostPath))
        assertFalse("Ghost path should be pruned from closedProjectPaths",
            service.wasClosedByUs(ghostPath))
    }

    fun testLoadStateKeepsAllExistingPaths() {
        val service = ProjectModeService()
        val path1 = System.getProperty("user.home")
        val path2 = System.getProperty("java.io.tmpdir").trimEnd('/')

        val state = ProjectModeService.State(
            closedProjectPaths = ConcurrentHashMap.newKeySet<String>().apply {
                add(path1)
                add(path2)
            },
            managedProjectPaths = ConcurrentHashMap.newKeySet<String>().apply {
                add(path1)
                add(path2)
            }
        )

        service.loadState(state)

        assertTrue("path1 should be kept", service.isManaged(path1))
        assertTrue("path2 should be kept", service.isManaged(path2))
    }
}
