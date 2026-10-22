package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RenameModuleBehaviorTest : McpPlatformTestCase() {

    fun testRenameModuleUpdatesDirectoryAndPomFiles() = runBlocking {
        writeProjectFile("parent-project/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.test</groupId>
              <artifactId>parent-project</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
              <modules>
                <module>old-module</module>
                <module>other-module</module>
              </modules>
            </project>
        """.trimIndent())

        writeProjectFile("parent-project/old-module/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.test</groupId>
                <artifactId>parent-project</artifactId>
                <version>1.0</version>
              </parent>
              <artifactId>old-module</artifactId>
            </project>
        """.trimIndent())

        writeProjectFile("parent-project/other-module/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.test</groupId>
                <artifactId>parent-project</artifactId>
                <version>1.0</version>
              </parent>
              <artifactId>other-module</artifactId>
              <dependencies>
                <dependency>
                  <groupId>io.test</groupId>
                  <artifactId>old-module</artifactId>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent())

        val modulePath = "${project.basePath}/parent-project/old-module"
        val result = RenameModuleTool().execute(project, buildJsonObject {
            put("modulePath", modulePath)
            put("newName", "new-module")
        })

        assertToolSucceeded("Rename should succeed", result)
        val text = toolText(result)
        assertTrue("Should mention new-module, was: $text", text.contains("new-module"))

        // Directory renamed
        assertTrue("New directory should exist", projectFileExists("parent-project/new-module/pom.xml"))
        assertFalse("Old directory should not exist", projectFileExists("parent-project/old-module/pom.xml"))

        // Parent pom updated
        val parentPom = readProjectFileVfs("parent-project/pom.xml")
        assertTrue("Parent pom should reference new-module", parentPom.contains("<module>new-module</module>"))
        assertFalse("Parent pom should not reference old-module", parentPom.contains("<module>old-module</module>"))

        // Module's own pom updated
        val modulePomPath = java.nio.file.Path.of(project.basePath!!, "parent-project/new-module/pom.xml")
        val modulePom = java.nio.file.Files.readString(modulePomPath)
        assertTrue("Module pom should have new artifactId", modulePom.contains("<artifactId>new-module</artifactId>"))
        assertFalse("Module pom should not have old artifactId", modulePom.contains("<artifactId>old-module</artifactId>"))

        // Sibling dependency updated
        val siblingPom = readProjectFileVfs("parent-project/other-module/pom.xml")
        assertTrue("Sibling dependency should reference new-module", siblingPom.contains("<artifactId>new-module</artifactId>"))
        assertFalse("Sibling dependency should not reference old-module as artifactId",
            siblingPom.contains("<artifactId>old-module</artifactId>"))
    }

    fun testRenameModuleWithNoPomReturnsError() = runBlocking {
        writeProjectFile("no-pom-dir/README.md", "just a readme")

        val result = RenameModuleTool().execute(project, buildJsonObject {
            put("modulePath", "${project.basePath}/no-pom-dir")
            put("newName", "renamed")
        })

        assertToolFailed("Should fail for dir without pom.xml", result)
        assertTrue("Error should mention pom.xml", toolText(result).contains("pom.xml"))
    }

    fun testRenameModuleWithBlankNameReturnsError() = runBlocking {
        writeProjectFile("some-module/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.test</groupId>
              <artifactId>some-module</artifactId>
              <version>1.0</version>
            </project>
        """.trimIndent())

        val result = RenameModuleTool().execute(project, buildJsonObject {
            put("modulePath", "${project.basePath}/some-module")
            put("newName", "")
        })

        assertToolFailed("Should fail for blank name", result)
    }

    fun testRenameModuleWithSlashInNameReturnsError() = runBlocking {
        writeProjectFile("some-module/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.test</groupId>
              <artifactId>some-module</artifactId>
              <version>1.0</version>
            </project>
        """.trimIndent())

        val result = RenameModuleTool().execute(project, buildJsonObject {
            put("modulePath", "${project.basePath}/some-module")
            put("newName", "path/traversal")
        })

        assertToolFailed("Should fail for name with slash", result)
    }

    fun testRenameRootModuleWithNoParentPom() = runBlocking {
        writeProjectFile("root-project/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.casehub</groupId>
              <artifactId>casehub-blocks-parent</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
              <modules>
                <module>blocks-core</module>
              </modules>
            </project>
        """.trimIndent())

        writeProjectFile("root-project/blocks-core/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.casehub</groupId>
                <artifactId>casehub-blocks-parent</artifactId>
                <version>1.0</version>
              </parent>
              <artifactId>casehub-blocks-core</artifactId>
            </project>
        """.trimIndent())

        val result = RenameModuleTool().execute(project, buildJsonObject {
            put("modulePath", "${project.basePath}/root-project")
            put("newName", "agency-project")
        })

        assertToolSucceeded("Root rename should succeed", result)

        assertTrue("New directory should exist", projectFileExists("agency-project/pom.xml"))
        assertFalse("Old directory should not exist", projectFileExists("root-project/pom.xml"))

        val rootPom = java.nio.file.Files.readString(
            java.nio.file.Path.of(project.basePath!!, "agency-project/pom.xml"))
        assertTrue("Root artifactId should be updated",
            rootPom.contains("<artifactId>agency-project</artifactId>"))
        assertFalse("Old artifactId should be gone",
            rootPom.contains("<artifactId>casehub-blocks-parent</artifactId>"))

        // Child's <parent><artifactId> should be updated to the new name
        val childPom = java.nio.file.Files.readString(
            java.nio.file.Path.of(project.basePath!!, "agency-project/blocks-core/pom.xml"))
        assertTrue("Child parent artifactId should be updated",
            childPom.contains("<artifactId>agency-project</artifactId>"))
        assertFalse("Child parent should not reference old name",
            childPom.contains("<artifactId>casehub-blocks-parent</artifactId>"))
        // Child's own artifactId should be unchanged
        assertTrue("Child own artifactId should be preserved",
            childPom.contains("<artifactId>casehub-blocks-core</artifactId>"))

        val text = toolText(result)
        assertTrue("Should report parentPomUpdated false", text.contains("\"parentPomUpdated\":false"))
        assertTrue("Should report childParentUpdates", text.contains("\"childParentUpdates\":1"))
    }

    fun testRenameMultiModuleProjectUpdatesAllChildren() = runBlocking {
        writeProjectFile("workspace/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.casehub</groupId>
              <artifactId>casehub-blocks-parent</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
              <modules>
                <module>blocks-core</module>
                <module>blocks-spring</module>
              </modules>
            </project>
        """.trimIndent())

        writeProjectFile("workspace/blocks-core/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.casehub</groupId>
                <artifactId>casehub-blocks-parent</artifactId>
                <version>1.0</version>
              </parent>
              <artifactId>casehub-blocks-core</artifactId>
            </project>
        """.trimIndent())

        writeProjectFile("workspace/blocks-core/src/main/java/io/casehub/blocks/BlocksService.java", """
            package io.casehub.blocks;
            public class BlocksService {
                public String name() { return "blocks"; }
            }
        """.trimIndent())

        writeProjectFile("workspace/blocks-spring/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.casehub</groupId>
                <artifactId>casehub-blocks-parent</artifactId>
                <version>1.0</version>
              </parent>
              <artifactId>casehub-blocks-spring</artifactId>
              <dependencies>
                <dependency>
                  <groupId>io.casehub</groupId>
                  <artifactId>casehub-blocks-core</artifactId>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent())

        // Step 1: Rename blocks-core → agency-core
        val result1 = RenameModuleTool().execute(project, buildJsonObject {
            put("modulePath", "${project.basePath}/workspace/blocks-core")
            put("newName", "agency-core")
        })
        assertToolSucceeded("Child rename should succeed", result1)

        // Verify directory renamed
        assertTrue("agency-core dir should exist", projectFileExists("workspace/agency-core/pom.xml"))
        assertFalse("blocks-core dir should be gone", projectFileExists("workspace/blocks-core/pom.xml"))

        // Verify parent pom module entry updated
        val parentPom = readProjectFileVfs("workspace/pom.xml")
        assertTrue("Parent should list agency-core", parentPom.contains("<module>agency-core</module>"))
        assertFalse("Parent should not list blocks-core", parentPom.contains("<module>blocks-core</module>"))

        // Verify sibling dependency updated
        val springPom = readProjectFileVfs("workspace/blocks-spring/pom.xml")
        assertTrue("Spring dep should reference agency-core",
            springPom.contains("<artifactId>agency-core</artifactId>"))

        // Verify Java file survived the directory rename
        val javaPath = java.nio.file.Path.of(project.basePath!!,
            "workspace/agency-core/src/main/java/io/casehub/blocks/BlocksService.java")
        assertTrue("Java file should exist in renamed dir", java.nio.file.Files.exists(javaPath))
        val javaContent = java.nio.file.Files.readString(javaPath)
        assertTrue("Java content should be preserved", javaContent.contains("public class BlocksService"))
    }

    fun testRenameChildModuleUpdatesChildParentArtifactId() = runBlocking {
        writeProjectFile("proj/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.test</groupId>
              <artifactId>my-parent</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
              <modules>
                <module>child-a</module>
              </modules>
            </project>
        """.trimIndent())

        writeProjectFile("proj/child-a/pom.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.test</groupId>
                <artifactId>my-parent</artifactId>
                <version>1.0</version>
              </parent>
              <artifactId>child-a</artifactId>
            </project>
        """.trimIndent())

        val result = RenameModuleTool().execute(project, buildJsonObject {
            put("modulePath", "${project.basePath}/proj/child-a")
            put("newName", "child-b")
        })

        assertToolSucceeded("Rename should succeed", result)

        // Parent ref in child pom should still point to my-parent (not renamed)
        val childPom = java.nio.file.Files.readString(
            java.nio.file.Path.of(project.basePath!!, "proj/child-b/pom.xml"))
        assertTrue("Parent artifactId should be preserved",
            childPom.contains("<artifactId>my-parent</artifactId>"))
        assertTrue("Child artifactId should be updated",
            childPom.contains("<artifactId>child-b</artifactId>"))
    }
}
