package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import junit.framework.TestCase
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RenameSymbolToolRoutingUnitTest : TestCase() {

    fun testRenameModeTreatsOmittedLineAndColumnAsFileRename() {
        val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
            put("file", JsonPrimitive("src/File.cs"))
            put("newName", JsonPrimitive("Renamed.cs"))
        })

        assertTrue(mode.isFileRename)
        assertNull(mode.line)
        assertNull(mode.column)
        assertNull(mode.error)
    }

    fun testRenameModeTreatsZeroLineAndColumnAsInvalidPosition() {
        val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
            put("file", JsonPrimitive("src/File.cs"))
            put("line", JsonPrimitive(0))
            put("column", JsonPrimitive(0))
            put("newName", JsonPrimitive("Renamed.cs"))
        })

        assertFalse(
            "Explicit line=0,column=0 must NOT silently become a destructive file rename",
            mode.isFileRename
        )
        assertNull(mode.line)
        assertNull(mode.column)
        assertNotNull(mode.error)
        assertTrue(mode.error!!.contains("positive integers"))
    }

    fun testRenameModeTreatsNegativeLineAndColumnAsInvalidPosition() {
        val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
            put("file", JsonPrimitive("src/File.cs"))
            put("line", JsonPrimitive(-1))
            put("column", JsonPrimitive(-3))
            put("newName", JsonPrimitive("Renamed.cs"))
        })

        assertFalse(mode.isFileRename)
        assertNotNull(mode.error)
        assertTrue(mode.error!!.contains("positive integers"))
    }

    fun testRenameModeTreatsBlankLineAndColumnStringsAsFileRename() {
        val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
            put("file", JsonPrimitive("src/File.cs"))
            put("line", JsonPrimitive("   "))
            put("column", JsonPrimitive(""))
            put("newName", JsonPrimitive("Renamed.cs"))
        })

        assertTrue(mode.isFileRename)
        assertNull(mode.line)
        assertNull(mode.column)
        assertNull(mode.error)
    }

    fun testRenameModeTreatsPositiveLineAndColumnAsSymbolRename() {
        val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
            put("file", JsonPrimitive("src/File.cs"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(8))
            put("newName", JsonPrimitive("RenamedSymbol"))
        })

        assertFalse(mode.isFileRename)
        assertEquals(12, mode.line)
        assertEquals(8, mode.column)
        assertNull(mode.error)
    }

    fun testRenameModeRejectsBothPresentWithNonPositiveCoordinateAsInvalidPosition() {
        listOf(
            buildJsonObject {
                put("line", JsonPrimitive(12))
                put("column", JsonPrimitive(0))
            },
            buildJsonObject {
                put("line", JsonPrimitive(0))
                put("column", JsonPrimitive(8))
            }
        ).forEach { coordinatesOnly ->
            val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
                put("file", JsonPrimitive("src/File.cs"))
                put("newName", JsonPrimitive("RenamedSymbol"))
                coordinatesOnly.forEach { (key, value) -> put(key, value) }
            })

            assertFalse(mode.isFileRename)
            assertNotNull(mode.error)
            assertTrue(
                "Both fields present but one non-positive should be an invalid-position error",
                mode.error!!.contains("positive integers")
            )
        }
    }

    fun testRenameModeRejectsExactlyOneCoordinatePresent() {
        listOf(
            buildJsonObject {
                put("line", JsonPrimitive(12))
            },
            buildJsonObject {
                put("column", JsonPrimitive(8))
            }
        ).forEach { coordinatesOnly ->
            val mode = RenameSymbolTool().resolveRenameMode(buildJsonObject {
                put("file", JsonPrimitive("src/File.cs"))
                put("newName", JsonPrimitive("RenamedSymbol"))
                coordinatesOnly.forEach { (key, value) -> put(key, value) }
            })

            assertFalse(mode.isFileRename)
            assertEquals(
                "Both 'line' and 'column' must be provided for symbol rename, or both omitted for file rename.",
                mode.error
            )
        }
    }

    fun testRiderFrontendFallbackRejectsContainerLikeTargets() {
        assertTrue(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass(null))
        assertTrue(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass("com.intellij.psi.impl.file.PsiDirectoryImpl"))
        assertTrue(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass("com.jetbrains.rider.ideaInterop.fileTypes.csharp.psi.impl.CSharpNamespaceDeclaration"))
        assertTrue(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass("com.intellij.psi.impl.source.PsiPackageImpl"))
        assertFalse(RenameSymbolTool.isUnsupportedRiderFrontendFallbackTargetClass("com.jetbrains.rider.ideaInterop.fileTypes.csharp.psi.impl.CSharpClassDeclaration"))
    }

    fun testRiderFrontendFallbackZeroChangeSummaryFailsClosed() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "ModelDescriptions",
            newName = "SimpleModelDesc",
            relatedRenamesCount = 0,
            affectedFiles = emptyList(),
            changesCount = 0,
            riderFallbackStatus = "unsupported"
        )

        assertFalse(summary.success)
        assertEquals("unsupported_context", summary.status)
        assertEquals(0, summary.changesCount)
        assertTrue(summary.affectedFiles.isEmpty())
        assertFalse(summary.message.contains("Successfully renamed"))
    }

    fun testBlockedRiderFrontendFallbackMapsEditorRequirementToNeedsActiveEditor() {
        val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
            oldName = "WidgetService",
            actionReason = "active editor is required for Rider rename lane"
        )

        assertFalse(result.success)
        assertEquals("needs_active_editor", result.status)
        assertTrue(result.message.contains("active editor is required"))
    }

    fun testBlockedRiderFrontendFallbackMapsConflictLikeReasonsToConflict() {
        listOf(
            "multiple rename handlers would require chooser UI",
            "production handler invoke would show modal UI",
            "rename preview would require user interaction"
        ).forEach { reason ->
            val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
                oldName = "WidgetService",
                actionReason = reason
            )

            assertFalse("reason=$reason should be non-success", result.success)
            assertEquals("reason=$reason should map to conflict", "conflict", result.status)
        }
    }

    fun testBlockedRiderFrontendFallbackMapsOtherFailClosedReasonsToUnsupportedContext() {
        val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
            oldName = "WidgetService",
            actionReason = "experimental action fallback disabled"
        )

        assertFalse(result.success)
        assertEquals("unsupported_context", result.status)
        assertTrue(result.message.contains("experimental action fallback disabled"))
    }

    fun testRiderFrontendFallbackChangedSummaryPreservesSuccess() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "SimpleTypeModelDescription",
            newName = "SimpleModelDesc",
            relatedRenamesCount = 1,
            affectedFiles = listOf("src/File.cs"),
            changesCount = 1,
            riderFallbackStatus = "unsupported",
            mutationCheck = RenameSymbolTool.verifyRiderFrontendMutation(
                beforeName = "SimpleTypeModelDescription",
                afterName = "SimpleModelDesc",
                newName = "SimpleModelDesc",
                beforeFileText = "class SimpleTypeModelDescription {}",
                afterFileText = "class SimpleTypeModelDescription {}"
            )
        )

        assertTrue(summary.success)
        assertNull(summary.status)
        assertEquals(1, summary.changesCount)
        assertEquals(listOf("src/File.cs"), summary.affectedFiles)
        assertTrue(summary.message.contains("Successfully renamed 'SimpleTypeModelDescription' to 'SimpleModelDesc'"))
    }

    fun testRiderFrontendFallbackChangedSummaryFailsClosedWhenMutationWasNotVerified() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "SimpleTypeModelDescription",
            newName = "SimpleModelDesc",
            relatedRenamesCount = 1,
            affectedFiles = listOf("src/File.cs"),
            changesCount = 1,
            riderFallbackStatus = "unsupported",
            mutationCheck = RenameSymbolTool.verifyRiderFrontendMutation(
                beforeName = "SimpleTypeModelDescription",
                afterName = "SimpleTypeModelDescription",
                newName = "SimpleModelDesc",
                beforeFileText = "class SimpleTypeModelDescription {}",
                afterFileText = "class SimpleTypeModelDescription {}"
            )
        )

        assertFalse(summary.success)
        assertEquals("no_op", summary.status)
        assertEquals(0, summary.changesCount)
        assertTrue(summary.affectedFiles.isEmpty())
        assertTrue(summary.message.contains("no real source mutation was verified"))
    }

    fun testGenericRenameSummaryRemainsSuccessWithoutRiderFallbackMutationGate() {
        val summary = RenameSymbolTool.summarizeFrontendRenameResult(
            oldName = "Service",
            newName = "CustomerService",
            relatedRenamesCount = 0,
            affectedFiles = listOf("src/Service.kt"),
            changesCount = 1,
            riderFallbackStatus = null,
            mutationCheck = RenameSymbolTool.verifyRiderFrontendMutation(
                beforeName = "Service",
                afterName = "Service",
                newName = "CustomerService",
                beforeFileText = "class Service {}",
                afterFileText = "class Service {}"
            )
        )

        assertTrue(summary.success)
        assertNull(summary.status)
        assertEquals(1, summary.changesCount)
        assertEquals(listOf("src/Service.kt"), summary.affectedFiles)
    }

    fun testRiderFrontendAutomationRoutingAcceptsAllDotNetSymbolStrategies() {
        assertTrue(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "none"))
        assertTrue(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "ask"))
        assertTrue(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "accessors_and_tests"))
        assertTrue(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = false, relatedRenamingStrategy = "all"))
        assertFalse(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.cs", isFileRename = true, relatedRenamingStrategy = "none"))
        assertFalse(RenameSymbolTool.shouldUseRiderFrontendRenameAutomation("src/Service.kt", isFileRename = false, relatedRenamingStrategy = "none"))
    }

    fun testRiderDialogAutomationDisablesRelatedSymbolsCheckboxOnlyForNoneStrategy() {
        assertTrue(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox("Rename related symbols"))
        assertTrue(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox("Rename Related Symbols in comments"))
        assertTrue(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox("Also update related symbols"))
        assertFalse(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox("Search in comments and strings"))
        assertFalse(RenameSymbolTool.shouldDisableRelatedSymbolsCheckbox(null))
    }

    fun testDotNetFileRenameDeclaredTypeContractAcceptsFileOnlyRename() {
        val verification = RenameSymbolTool.verifyDotNetFileRenameDeclaredTypeIdentity(
            beforeFileText = "namespace Demo; public class Service {}",
            afterFileText = "namespace Demo; public class Service {}"
        )

        assertNull(verification)
    }

    fun testDotNetFileRenameDeclaredTypeContractRejectsTypeRename() {
        val verification = RenameSymbolTool.verifyDotNetFileRenameDeclaredTypeIdentity(
            beforeFileText = "namespace Demo; public class Service {}",
            afterFileText = "namespace Demo; public class CustomerService {}"
        )

        assertNotNull(verification)
        assertEquals("failed", verification!!.status)
        assertTrue(verification.warnings.any { it.contains("declared type identity", ignoreCase = true) })
    }
}
