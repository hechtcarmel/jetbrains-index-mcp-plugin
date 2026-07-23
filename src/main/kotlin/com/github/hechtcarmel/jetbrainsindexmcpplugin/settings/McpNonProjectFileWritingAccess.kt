package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension
import com.intellij.openapi.vfs.VirtualFile

class McpNonProjectFileWritingAccess : NonProjectFileWritingAccessExtension {
    override fun isWritable(file: VirtualFile): Boolean {
        return HeadlessModeManager.isEnabled
    }
}
