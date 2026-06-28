package com.jetbrains.python.psi

import com.jetbrains.python.psi.types.TypeEvalContext

interface PyClass : PyElement {
    override fun getName(): String?
    fun getQualifiedName(): String?
    fun getSuperClasses(context: TypeEvalContext?): Array<PyClass>
    fun getAncestorClasses(context: TypeEvalContext?): List<PyClass>
    fun findMethodByName(name: String?, inherited: Boolean, context: TypeEvalContext?): PyFunction?
}
