package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import junit.framework.TestCase

class GetDiagnosticsToolUnitTest : TestCase() {

    fun testRequestsEditorPreparationOnlyForFileAnalysisWithoutOpenEditor() {
        assertTrue(
            GetDiagnosticsTool.shouldPrepareEditorForFileAnalysis(
                filePath = "src/Broken.java",
                hasOpenTextEditor = false
            )
        )

        assertFalse(
            GetDiagnosticsTool.shouldPrepareEditorForFileAnalysis(
                filePath = "src/Broken.java",
                hasOpenTextEditor = true
            )
        )
    }

    fun testSkipsEditorPreparationForBuildAndTestOnlyDiagnostics() {
        assertFalse(
            GetDiagnosticsTool.shouldPrepareEditorForFileAnalysis(
                filePath = null,
                hasOpenTextEditor = false
            )
        )
    }
}
