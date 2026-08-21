package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolParameterInfo
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWhiteSpace

/**
 * Builds a fully resolved signature from Java PSI.
 *
 * Every type name here comes from `PsiType.getCanonicalText()`, which is the *resolved*
 * qualified name — `java.util.List<com.example.model.Request>` for a parameter written as
 * `List<Request>`. That is the whole reason this tool exists: source text, which is all
 * `ide_find_definition` can return, keeps the short names the author happened to import.
 *
 * **This class touches Java-plugin PSI directly and must only be reached behind
 * `PluginDetectors.java.isAvailable`** (see [SymbolSignatureResolver]). Class loading is lazy,
 * so guarding the call site is enough to keep IDEs without the Java plugin from ever resolving
 * these types — the same arrangement [JavaMemberResolver] uses.
 */
internal object JavaSignatureExtractor {

    /**
     * Modifiers checked when a declaration has no textual modifier list to read.
     *
     * Kotlin light methods and classes answer `hasModifierProperty` without exposing modifier
     * keywords as PSI children, and so does an interface method that is implicitly `public
     * abstract`. Order is source order, not declaration order in this list's absence.
     */
    private val IMPLICIT_MODIFIERS = listOf(
        PsiModifier.PUBLIC,
        PsiModifier.PROTECTED,
        PsiModifier.PRIVATE,
        PsiModifier.ABSTRACT,
        PsiModifier.SEALED,
        PsiModifier.NON_SEALED,
        PsiModifier.DEFAULT,
        PsiModifier.STATIC,
        PsiModifier.FINAL,
        PsiModifier.SYNCHRONIZED,
        PsiModifier.NATIVE,
        PsiModifier.STRICTFP,
        PsiModifier.TRANSIENT,
        PsiModifier.VOLATILE,
    )

    /**
     * @return the resolved signature, or null when [element] is not a Java declaration this
     *   extractor understands — the caller then falls back to the language's own renderer.
     */
    fun extract(element: PsiElement): ResolvedSignature? = when (element) {
        is PsiMethod -> extractMethod(element)
        is PsiField -> extractField(element)
        is PsiClass -> extractClass(element)
        // PsiField is a PsiVariable too, so this branch is parameters and local variables only.
        is PsiVariable -> extractVariable(element)
        else -> null
    }

    private fun extractMethod(method: PsiMethod): ResolvedSignature {
        val modifiers = modifiersOf(method)
        val typeParameters = typeParametersOf(method)
        val parameters = method.parameterList.parameters.map {
            SymbolParameterInfo(name = it.name, type = it.type.canonicalText)
        }
        // A constructor has no return type; reporting one would be a lie, not a default.
        val returnType = if (method.isConstructor) null else method.returnType?.canonicalText
        val thrownTypes = method.throwsList.referencedTypes.map { it.canonicalText }

        val signature = buildString {
            appendPrefix(modifiers)
            if (typeParameters.isNotEmpty()) append("<${typeParameters.joinToString(", ")}> ")
            if (returnType != null) append("$returnType ")
            append(method.name)
            append(parameters.joinToString(", ", "(", ")") { "${it.type} ${it.name.orEmpty()}".trim() })
            if (thrownTypes.isNotEmpty()) append(" throws ${thrownTypes.joinToString(", ")}")
        }

        return ResolvedSignature(
            signature = signature,
            source = SignatureSources.JAVA_PSI,
            parameters = parameters,
            returnType = returnType,
            typeParameters = typeParameters.takeIf { it.isNotEmpty() },
            thrownTypes = thrownTypes.takeIf { it.isNotEmpty() },
            modifiers = modifiers.takeIf { it.isNotEmpty() },
            visibility = visibilityOf(method),
            containingDeclaration = containingDeclarationOf(method)
        )
    }

    private fun extractField(field: PsiField): ResolvedSignature {
        val modifiers = modifiersOf(field)
        val type = field.type.canonicalText
        return ResolvedSignature(
            signature = buildString {
                appendPrefix(modifiers)
                append("$type ${field.name}")
            },
            source = SignatureSources.JAVA_PSI,
            returnType = type,
            modifiers = modifiers.takeIf { it.isNotEmpty() },
            visibility = visibilityOf(field),
            containingDeclaration = containingDeclarationOf(field)
        )
    }

    private fun extractVariable(variable: PsiVariable): ResolvedSignature? {
        val name = variable.name ?: return null
        val modifiers = modifiersOf(variable)
        val type = variable.type.canonicalText
        return ResolvedSignature(
            signature = buildString {
                appendPrefix(modifiers)
                append("$type $name")
            },
            source = SignatureSources.JAVA_PSI,
            returnType = type,
            modifiers = modifiers.takeIf { it.isNotEmpty() },
            containingDeclaration = SymbolSignatureResolver.containingDeclarationFromAstPath(variable)
        )
    }

    private fun extractClass(psiClass: PsiClass): ResolvedSignature? {
        // A type parameter is a PsiClass; rendering it as `class T` would be nonsense.
        if (psiClass is PsiTypeParameter) return null
        val name = psiClass.qualifiedName ?: psiClass.name ?: return null
        val modifiers = modifiersOf(psiClass)
        val typeParameters = typeParametersOf(psiClass)
        val extends = psiClass.extendsListTypes.map { it.canonicalText }
        val implements = psiClass.implementsListTypes.map { it.canonicalText }

        val signature = buildString {
            appendPrefix(modifiers)
            append("${classKeyword(psiClass)} $name")
            if (typeParameters.isNotEmpty()) append("<${typeParameters.joinToString(", ")}>")
            if (extends.isNotEmpty()) append(" extends ${extends.joinToString(", ")}")
            if (implements.isNotEmpty()) append(" implements ${implements.joinToString(", ")}")
        }

        return ResolvedSignature(
            signature = signature,
            source = SignatureSources.JAVA_PSI,
            typeParameters = typeParameters.takeIf { it.isNotEmpty() },
            modifiers = modifiers.takeIf { it.isNotEmpty() },
            visibility = visibilityOf(psiClass),
            containingDeclaration = containingDeclarationOf(psiClass)
        )
    }

    private fun classKeyword(psiClass: PsiClass): String = when {
        psiClass.isAnnotationType -> "@interface"
        psiClass.isInterface -> "interface"
        psiClass.isEnum -> "enum"
        psiClass.isRecord -> "record"
        else -> "class"
    }

    private fun StringBuilder.appendPrefix(modifiers: List<String>) {
        if (modifiers.isNotEmpty()) append("${modifiers.joinToString(" ")} ")
    }

    /**
     * Modifiers as written, falling back to the platform's answer when nothing is written.
     *
     * Reading the modifier list's own children keeps modifiers the platform models as text —
     * `sealed`, `non-sealed` — without this file having to name every `PsiModifier` constant,
     * and preserves source order. Light elements have no such children, hence the fallback.
     */
    private fun modifiersOf(owner: PsiModifierListOwner): List<String> {
        val modifierList = owner.modifierList ?: return emptyList()
        val written = modifierList.children
            .filter { it !is PsiWhiteSpace && it !is PsiComment && it !is PsiAnnotation }
            .map { it.text }
            .filter { it.isNotBlank() }
        if (written.isNotEmpty()) return written
        return IMPLICIT_MODIFIERS.filter { owner.hasModifierProperty(it) }
    }

    private fun typeParametersOf(owner: PsiElement): List<String> {
        val typeParameters = when (owner) {
            is PsiMethod -> owner.typeParameters
            is PsiClass -> owner.typeParameters
            else -> return emptyList()
        }
        return typeParameters.map { parameter ->
            val bounds = parameter.extendsListTypes.map { it.canonicalText }
            val name = parameter.name ?: "?"
            if (bounds.isEmpty()) name else "$name extends ${bounds.joinToString(" & ")}"
        }
    }

    /**
     * @return `public`, `protected`, `private`, or `package-private` — the last being a real
     *   visibility with no keyword, so it is reported by name rather than as an absence.
     */
    private fun visibilityOf(owner: PsiModifierListOwner): String = when {
        owner.hasModifierProperty(PsiModifier.PUBLIC) -> "public"
        owner.hasModifierProperty(PsiModifier.PROTECTED) -> "protected"
        owner.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
        else -> "package-private"
    }

    private fun containingDeclarationOf(member: PsiMember): String? {
        member.containingClass?.let { return it.qualifiedName ?: it.name }
        // A top-level type has no containing class; its package is the meaningful container.
        return (member.containingFile as? PsiJavaFile)?.packageName?.takeIf { it.isNotEmpty() }
    }
}
