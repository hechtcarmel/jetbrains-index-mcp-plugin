package com.jetbrains.python.psi

interface PyFunction : PyElement {
    override fun getName(): String?
    fun getQualifiedName(): String?
}
