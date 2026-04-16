package com.jetbrains.python.psi

interface PyClass : PyElement {
    fun getName(): String?
    fun getQualifiedName(): String?
}
