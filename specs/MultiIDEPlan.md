# Multi-IDE Support Enhancement Plan

**Document Version**: 1.0
**Status**: Draft
**Date**: 2025-11-29

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Goals & Motivation](#2-goals--motivation)
3. [Current State Analysis](#3-current-state-analysis)
4. [Architecture Design](#4-architecture-design)
5. [Tool-by-Tool Analysis](#5-tool-by-tool-analysis)
6. [Language Handler Specifications](#6-language-handler-specifications)
7. [Implementation Plan](#7-implementation-plan)
8. [Technical Details](#8-technical-details)
9. [Testing Strategy](#9-testing-strategy)
10. [Future Extensions](#10-future-extensions)

---

## 1. Executive Summary

### Vision

Transform the IDE Index MCP Server from a Java-centric plugin into a truly **language-aware, multi-IDE plugin** that provides rich code intelligence for any language supported by JetBrains IDEs.

### Key Design Principle

> **Unified tools that adapt to file type, not separate tools per language.**

Instead of creating `ide_java_type_hierarchy`, `ide_py_type_hierarchy`, and `ide_js_type_hierarchy`, we maintain a single `ide_type_hierarchy` tool that:
1. Detects the language of the target element
2. Delegates to the appropriate language handler
3. Returns results in a unified format

### Expected Outcome

| IDE | Before | After |
|-----|--------|-------|
| IntelliJ IDEA | 11 tools (4 universal + 7 Java) | 11 tools (all language-aware) |
| PyCharm | 4 tools (universal only) | 11 tools (4 universal + 7 Python-aware) |
| WebStorm | 4 tools (universal only) | 11 tools (4 universal + 7 JS/TS-aware) |
| GoLand | 4 tools (universal only) | 11 tools (4 universal + 7 Go-aware) |

---

## 2. Goals & Motivation

### 2.1 Primary Goals

1. **Unified Tool Experience**: Same tool names work across all languages
2. **Automatic Language Detection**: Tools detect file/element language and adapt
3. **Graceful Degradation**: If a language handler isn't available, tools provide helpful feedback
4. **Result Unification**: All language handlers return consistent result formats

### 2.2 Non-Goals (For This Phase)

- Framework-specific tools (Django, React, Angular)
- Language-specific unique features (Python decorators, JS modules)
- Cross-language analysis (e.g., Python calling Java via Jython)

### 2.3 Motivation

**AI assistants should not need to know which IDE or language plugin is installed.** They should simply ask for "type hierarchy" and get results, regardless of whether the code is Java, Python, or TypeScript.

From the [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/intellij-platform.html):
> "A lot of the functionalities in the IntelliJ Platform are language and product agnostic. For example, code inspections work the same in Java as they do in Ruby; it is just the syntax trees and semantic information that is different."

---

## 3. Current State Analysis

### 3.1 Current Tool Classification

| Tool | Current State | Language Dependencies |
|------|---------------|----------------------|
| `ide_find_references` | Universal | Uses `ReferencesSearch` (language-agnostic) |
| `ide_find_definition` | Universal | Uses `PsiReference` (language-agnostic) |
| `ide_diagnostics` | Universal | Uses `DaemonCodeAnalyzer` (language-agnostic) |
| `ide_index_status` | Universal | Uses `DumbService` (language-agnostic) |
| `ide_type_hierarchy` | Java-only | Uses `ClassInheritorsSearch`, `PsiClass` |
| `ide_call_hierarchy` | Java-only | Uses `MethodReferencesSearch`, `PsiMethod` |
| `ide_find_implementations` | Java-only | Uses `ClassInheritorsSearch`, `OverridingMethodsSearch` |
| `ide_find_symbol` | Java-only | Uses `PsiShortNamesCache` |
| `ide_find_super_methods` | Java-only | Uses `PsiMethod.findSuperMethods()` |
| `ide_refactor_rename` | Java-only* | Uses `RefactoringFactory` (actually language-agnostic!) |
| `ide_refactor_safe_delete` | Java-only* | Uses `SafeDeleteProcessor` (actually language-agnostic!) |

*Note: Refactoring tools may already work for other languages but currently only load when Java plugin is present.

### 3.2 Current Architecture

```
ToolRegistry
    └── registerBuiltInTools()
            ├── registerUniversalTools()  → 4 tools
            └── registerJavaTools()       → 7 tools (reflection-loaded)
```

### 3.3 Key Insight: Some Tools Are Already Language-Agnostic

The IntelliJ Platform's refactoring infrastructure is designed to be language-agnostic. From [JetBrains support](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206110419-How-to-implement-go-to-to-implementation-):
> "You need to provide your query executor for the `DefinitionsScopedSearch` searcher."

This means we can potentially make the following tools work for all languages with minimal changes:
- `ide_refactor_rename` - Already uses language-agnostic `RefactoringFactory`
- `ide_refactor_safe_delete` - Already uses language-agnostic `SafeDeleteProcessor`

---

## 4. Architecture Design

### 4.1 Handler-Based Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         McpTool (Abstract)                          │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    LanguageHandlerRegistry                   │   │
│  │                                                              │   │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │   │
│  │  │JavaHandler   │ │PythonHandler │ │JSHandler     │  ...   │   │
│  │  │(if available)│ │(if available)│ │(if available)│        │   │
│  │  └──────────────┘ └──────────────┘ └──────────────┘        │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Execution Flow                                │
│                                                                      │
│  1. Tool receives request (file, line, column)                      │
│  2. Resolve PsiElement at location                                   │
│  3. Detect language: element.language or file.language              │
│  4. Find handler: registry.getHandler(language)                      │
│  5. Execute: handler.execute(element, arguments)                     │
│  6. Return unified result format                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 Core Interfaces

```kotlin
/**
 * Base interface for language-specific handlers.
 * Handlers are registered based on plugin availability.
 */
interface LanguageHandler<T> {
    /** The language ID this handler supports (e.g., "JAVA", "Python", "JavaScript") */
    val languageId: String

    /** Check if this handler can process the given element */
    fun canHandle(element: PsiElement): Boolean

    /** Check if the required plugin is available */
    fun isAvailable(): Boolean
}

/**
 * Handler for type hierarchy operations.
 */
interface TypeHierarchyHandler : LanguageHandler<TypeHierarchyResult> {
    fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyResult?
}

/**
 * Handler for finding implementations.
 */
interface ImplementationsHandler : LanguageHandler<List<ImplementationResult>> {
    fun findImplementations(element: PsiElement, project: Project): List<ImplementationResult>
}

/**
 * Handler for symbol search.
 */
interface SymbolSearchHandler : LanguageHandler<List<SymbolResult>> {
    fun searchSymbols(project: Project, pattern: String, scope: SearchScope): List<SymbolResult>
}
```

### 4.3 Language Detection Strategy

```kotlin
object LanguageDetector {
    /**
     * Detects the language of a PSI element.
     *
     * Strategy:
     * 1. Try element.language (most accurate)
     * 2. Fall back to containing file's language
     * 3. Fall back to file extension heuristics
     */
    fun detectLanguage(element: PsiElement): Language {
        // Primary: Element's own language
        val elementLanguage = element.language
        if (elementLanguage != Language.ANY) {
            return elementLanguage
        }

        // Secondary: Containing file's language
        element.containingFile?.language?.let { return it }

        // Tertiary: File extension (for edge cases)
        return Language.ANY
    }

    /**
     * For multi-language files (e.g., HTML with embedded JS),
     * we may need to check the injection host.
     */
    fun detectEffectiveLanguage(element: PsiElement): Language {
        // Check if element is in an injected context
        val injectionHost = InjectedLanguageManager.getInstance(element.project)
            .getInjectionHost(element)

        return if (injectionHost != null) {
            element.language // Use injected language
        } else {
            detectLanguage(element)
        }
    }
}
```

### 4.4 Result Unification

All handlers return unified result types that are language-agnostic:

```kotlin
/**
 * Unified type element representation across all languages.
 */
@Serializable
data class TypeElement(
    val name: String,
    val qualifiedName: String?,
    val file: String?,
    val line: Int?,
    val kind: TypeKind,  // CLASS, INTERFACE, ENUM, TRAIT, PROTOCOL, etc.
    val language: String  // "Java", "Python", "TypeScript", etc.
)

enum class TypeKind {
    CLASS,
    INTERFACE,
    ABSTRACT_CLASS,
    ENUM,
    TRAIT,        // Scala, Rust
    PROTOCOL,     // Swift, Python
    STRUCT,       // Go, C, Rust
    TYPE_ALIAS,
    OBJECT        // Kotlin object, JS object
}
```

---

## 5. Tool-by-Tool Analysis

### 5.1 Tools That Can Be Unified (Language-Aware)

| Tool | Unification Strategy | Complexity |
|------|---------------------|------------|
| `ide_type_hierarchy` | Language handlers with `ClassInheritorsSearch` equivalents | Medium |
| `ide_call_hierarchy` | Language handlers with method reference search | Medium |
| `ide_find_implementations` | Use `DefinitionsScopedSearch` + language handlers | Medium |
| `ide_find_symbol` | Aggregate results from language-specific indexes | Low |
| `ide_find_super_methods` | Language handlers for method override chains | Medium |

### 5.2 Tools That Are Already Mostly Universal

| Tool | Current State | Enhancement Needed |
|------|---------------|-------------------|
| `ide_refactor_rename` | Uses `RefactoringFactory` | Just remove Java-only guard |
| `ide_refactor_safe_delete` | Uses `SafeDeleteProcessor` | Just remove Java-only guard |

### 5.3 Detailed Tool Analysis

#### ide_type_hierarchy

**Goal**: Get supertypes and subtypes of a class/interface.

| Language | Handler Class | Key APIs |
|----------|---------------|----------|
| Java | `JavaTypeHierarchyHandler` | `PsiClass`, `ClassInheritorsSearch` |
| Python | `PythonTypeHierarchyHandler` | `PyClass`, `PyClassInheritorsSearch` |
| JavaScript/TS | `JavaScriptTypeHierarchyHandler` | `JSClass`, `TypeScriptClass` |
| Go | `GoTypeHierarchyHandler` | `GoTypeSpec`, inheritor search |
| Kotlin | Uses Java handler | `KtClass` → light `PsiClass` |

**Python-specific considerations**:
- Multiple inheritance (MRO - Method Resolution Order)
- Protocols (PEP 544) are like interfaces
- Abstract base classes (ABCs)

**JavaScript/TypeScript-specific considerations**:
- TypeScript interfaces vs classes
- Mixins and composition patterns
- Prototype chain (less relevant for modern TS)

#### ide_find_implementations

**Goal**: Find all implementations of an interface/abstract method.

From [JetBrains Support](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206110419-How-to-implement-go-to-to-implementation-):
> "You need to provide your query executor for the `DefinitionsScopedSearch` searcher."

This is a core platform feature with language-specific query executors:

| Language | Implementation |
|----------|---------------|
| Java | `JavaClassInheritorsSearcher`, `OverridingMethodsSearch` |
| Python | `PyClassInheritorsSearch` extension point |
| JavaScript | Via `DefinitionsScopedSearch` |

#### ide_find_symbol

**Goal**: Search for classes/functions/variables by name.

**Strategy**: Aggregate results from all available language indexes.

```kotlin
fun searchSymbols(project: Project, pattern: String): List<SymbolResult> {
    val results = mutableListOf<SymbolResult>()

    // Java symbols (if available)
    JavaPluginDetector.ifJavaAvailable {
        results += searchJavaSymbols(project, pattern)
    }

    // Python symbols (if available)
    PythonPluginDetector.ifPythonAvailable {
        results += searchPythonSymbols(project, pattern)
    }

    // JavaScript/TypeScript symbols (if available)
    JavaScriptPluginDetector.ifJavaScriptAvailable {
        results += searchJavaScriptSymbols(project, pattern)
    }

    return results.distinctBy { it.qualifiedName }.sortedBy { it.name }
}
```

---

## 6. Language Handler Specifications

### 6.1 Plugin Detection

```kotlin
// util/PluginDetector.kt

object JavaPluginDetector {
    val isAvailable: Boolean by lazy {
        PluginManagerCore.getPlugin(PluginId.getId("com.intellij.java"))?.isEnabled == true
    }
}

object PythonPluginDetector {
    val isAvailable: Boolean by lazy {
        // Pythonid = Professional, PythonCore = Community
        PluginManagerCore.getPlugin(PluginId.getId("Pythonid"))?.isEnabled == true ||
        PluginManagerCore.getPlugin(PluginId.getId("PythonCore"))?.isEnabled == true
    }
}

object JavaScriptPluginDetector {
    val isAvailable: Boolean by lazy {
        PluginManagerCore.getPlugin(PluginId.getId("JavaScript"))?.isEnabled == true
    }
}

object GoPluginDetector {
    val isAvailable: Boolean by lazy {
        PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.go"))?.isEnabled == true
    }
}
```

### 6.2 Plugin IDs Reference

| Language | Plugin ID | Bundled With |
|----------|-----------|--------------|
| Java | `com.intellij.java` | IntelliJ IDEA, Android Studio |
| Kotlin | `org.jetbrains.kotlin` | IntelliJ IDEA, Android Studio |
| Python (Pro) | `Pythonid` | PyCharm Professional |
| Python (CE) | `PythonCore` | PyCharm Community |
| JavaScript | `JavaScript` | WebStorm, IntelliJ Ultimate |
| TypeScript | `JavaScript` | Same plugin as JS |
| Go | `org.jetbrains.plugins.go` | GoLand |
| PHP | `com.jetbrains.php` | PhpStorm |
| Ruby | `org.jetbrains.plugins.ruby` | RubyMine |
| Rust | `org.rust.lang` | RustRover, plugin for others |
| C/C++ | `com.intellij.cidr.lang` | CLion |

### 6.3 Python Handler Example

```kotlin
// tools/handlers/python/PythonTypeHierarchyHandler.kt

class PythonTypeHierarchyHandler : TypeHierarchyHandler {

    override val languageId = "Python"

    override fun isAvailable(): Boolean = PythonPluginDetector.isAvailable

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && element.language.id == "Python"
    }

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyResult? {
        // Use reflection to avoid compile-time dependency
        val pyClass = findPyClass(element) ?: return null

        return TypeHierarchyResult(
            element = pyClassToTypeElement(pyClass),
            supertypes = getSuperClasses(pyClass).map { pyClassToTypeElement(it) },
            subtypes = getSubClasses(pyClass, project).map { pyClassToTypeElement(it) }
        )
    }

    private fun findPyClass(element: PsiElement): Any? {
        // Reflection-based access to PyClass
        val pyClassClass = Class.forName("com.jetbrains.python.psi.PyClass")
        return PsiTreeUtil.getParentOfType(element, pyClassClass as Class<PsiElement>)
    }

    private fun getSuperClasses(pyClass: Any): List<Any> {
        // Call PyClass.getSuperClasses() via reflection
        val method = pyClass.javaClass.getMethod("getSuperClasses", TypeEvalContext::class.java)
        // ... implementation
    }

    private fun getSubClasses(pyClass: Any, project: Project): List<Any> {
        // Use PyClassInheritorsSearch via reflection
        // ... implementation
    }
}
```

### 6.4 JavaScript/TypeScript Handler Example

```kotlin
// tools/handlers/javascript/JavaScriptTypeHierarchyHandler.kt

class JavaScriptTypeHierarchyHandler : TypeHierarchyHandler {

    override val languageId = "JavaScript"

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isAvailable

    override fun canHandle(element: PsiElement): Boolean {
        if (!isAvailable()) return false
        val langId = element.language.id
        return langId == "JavaScript" || langId == "TypeScript" ||
               langId == "ECMAScript 6" || langId == "JSX Harmony"
    }

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyResult? {
        val jsClass = findJSClass(element) ?: return null

        return TypeHierarchyResult(
            element = jsClassToTypeElement(jsClass),
            supertypes = getSuperClasses(jsClass).map { jsClassToTypeElement(it) },
            subtypes = findSubClasses(jsClass, project).map { jsClassToTypeElement(it) }
        )
    }

    // TypeScript-specific: Also handle interfaces
    private fun findJSClass(element: PsiElement): Any? {
        // Try JSClass first, then TypeScriptClass, then TypeScriptInterface
        // ... reflection-based implementation
    }
}
```

---

## 7. Implementation Plan

### Phase 1: Refactor Existing Architecture (Week 1)

**Goal**: Introduce handler-based architecture without breaking existing functionality.

1. **Create handler interfaces**
   - `LanguageHandler<T>` base interface
   - `TypeHierarchyHandler`, `ImplementationsHandler`, `SymbolSearchHandler`, etc.

2. **Create handler registry**
   - `LanguageHandlerRegistry` for discovering and managing handlers
   - Automatic handler registration based on plugin availability

3. **Refactor Java tools to use handlers**
   - Extract Java-specific logic from `TypeHierarchyTool` into `JavaTypeHierarchyHandler`
   - Tool becomes a thin wrapper that delegates to handlers

4. **Make refactoring tools universal**
   - `ide_refactor_rename` and `ide_refactor_safe_delete` likely already work
   - Remove Java-only guards, test with other languages

### Phase 2: Add Python Support (Week 2)

**Goal**: Full Python support in PyCharm.

1. **Create `PythonPluginDetector`**

2. **Implement Python handlers**
   - `PythonTypeHierarchyHandler` using `PyClass`, `PyClassInheritorsSearch`
   - `PythonImplementationsHandler`
   - `PythonSymbolSearchHandler` using `PyClassNameIndex`, `PyFunctionNameIndex`
   - `PythonCallHierarchyHandler`
   - `PythonSuperMethodsHandler`

3. **Add Python test fixtures**

4. **Update documentation**

### Phase 3: Add JavaScript/TypeScript Support (Week 3)

**Goal**: Full JS/TS support in WebStorm.

1. **Create `JavaScriptPluginDetector`**

2. **Implement JavaScript handlers**
   - `JavaScriptTypeHierarchyHandler` (handles both JS and TS)
   - `JavaScriptImplementationsHandler`
   - `JavaScriptSymbolSearchHandler`
   - `JavaScriptCallHierarchyHandler`

3. **Handle TypeScript-specific features**
   - Interface implementations
   - Type aliases

4. **Add JS/TS test fixtures**

### Phase 4: Additional Languages (Optional, Week 4+)

**Goal**: Support for Go, PHP, Ruby based on demand.

1. **Go support for GoLand**
   - `GoTypeHierarchyHandler`
   - `GoImplementationsHandler`

2. **PHP support for PhpStorm**

3. **Ruby support for RubyMine**

---

## 8. Technical Details

### 8.1 Build Configuration

```kotlin
// build.gradle.kts

intellijPlatform {
    // Build against IntelliJ for compilation
    // (Java classes needed at compile time)
    pluginConfiguration {
        ideaVersion {
            type = IntelliJPlatformType.IntellijIdeaCommunity
        }
    }

    // Verify against multiple IDEs
    pluginVerification {
        ides {
            recommended()
            create(IntelliJPlatformType.PyCharmCommunity, "2025.1.3")
            create(IntelliJPlatformType.WebStorm, "2025.1.3")
            create(IntelliJPlatformType.GoLand, "2025.1.3")
        }
    }
}
```

### 8.2 Plugin.xml Configuration

```xml
<!-- plugin.xml -->
<idea-plugin>
    <!-- Required: Platform APIs -->
    <depends>com.intellij.modules.platform</depends>

    <!-- Optional: Language-specific features -->
    <depends optional="true" config-file="java-features.xml">com.intellij.modules.java</depends>
    <depends optional="true" config-file="python-features.xml">Pythonid</depends>
    <depends optional="true" config-file="python-ce-features.xml">PythonCore</depends>
    <depends optional="true" config-file="javascript-features.xml">JavaScript</depends>
    <depends optional="true" config-file="go-features.xml">org.jetbrains.plugins.go</depends>
</idea-plugin>
```

### 8.3 PSI Class Mappings

| Concept | Java | Python | JavaScript/TS | Go |
|---------|------|--------|---------------|-----|
| Class | `PsiClass` | `PyClass` | `JSClass`/`TypeScriptClass` | `GoTypeSpec` |
| Interface | `PsiClass.isInterface()` | `PyClass` (protocol) | `TypeScriptInterface` | `GoTypeSpec` (interface) |
| Method | `PsiMethod` | `PyFunction` | `JSFunction` | `GoFunctionDeclaration` |
| Field | `PsiField` | `PyTargetExpression` | `JSField` | `GoFieldDefinition` |
| Function | N/A | `PyFunction` | `JSFunction` | `GoFunctionDeclaration` |
| Inheritors Search | `ClassInheritorsSearch` | `PyClassInheritorsSearch` | Via `DefinitionsScopedSearch` | Via stubs |

### 8.4 Reflection Loading Pattern

To avoid compile-time dependencies on language-specific classes:

```kotlin
abstract class ReflectionBasedHandler {

    protected fun loadClass(className: String): Class<*>? {
        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected fun invokeMethod(obj: Any, methodName: String, vararg args: Any): Any? {
        return try {
            val method = obj.javaClass.methods.find { it.name == methodName }
            method?.invoke(obj, *args)
        } catch (e: Exception) {
            null
        }
    }

    protected inline fun <reified T> findParentOfType(element: PsiElement): T? {
        return PsiTreeUtil.getParentOfType(element, T::class.java)
    }
}
```

---

## 9. Testing Strategy

### 9.1 Unit Tests

- Test handler registration/discovery
- Test language detection
- Test result unification

### 9.2 Integration Tests

For each language, create test fixtures:

```
src/test/testData/
├── java/
│   ├── hierarchy/
│   │   ├── Animal.java
│   │   ├── Dog.java
│   │   └── expected_hierarchy.json
│   └── implementations/
│       └── ...
├── python/
│   ├── hierarchy/
│   │   ├── animal.py
│   │   ├── dog.py
│   │   └── expected_hierarchy.json
│   └── ...
└── javascript/
    ├── hierarchy/
    │   ├── Animal.ts
    │   ├── Dog.ts
    │   └── expected_hierarchy.json
    └── ...
```

### 9.3 Manual Testing Matrix

| Tool | IntelliJ | PyCharm | WebStorm | GoLand |
|------|----------|---------|----------|--------|
| ide_type_hierarchy | Java ✓ Kotlin ✓ | Python ✓ | TS ✓ JS ✓ | Go ✓ |
| ide_call_hierarchy | Java ✓ | Python ✓ | TS ✓ | Go ✓ |
| ide_find_implementations | Java ✓ | Python ✓ | TS ✓ | Go ✓ |
| ... | ... | ... | ... | ... |

---

## 10. Future Extensions

### 10.1 Framework-Specific Enhancements

Once base language support is complete, consider:

| Framework | Potential Tools |
|-----------|-----------------|
| Django | `ide_django_models`, `ide_django_views` |
| React | `ide_react_components`, `ide_react_hooks` |
| Spring | `ide_spring_beans`, `ide_spring_endpoints` |
| FastAPI | `ide_fastapi_routes` |

### 10.2 Cross-Language Analysis

Future possibilities:
- Finding usages across language boundaries (e.g., Python calling C extensions)
- Polyglot project support (e.g., Kotlin + Java + Groovy)

### 10.3 Language Server Protocol (LSP) Integration

Consider integrating with LSP servers for languages not natively supported by JetBrains plugins.

---

## Appendix A: References

- [IntelliJ Platform SDK - About](https://plugins.jetbrains.com/docs/intellij/about.html)
- [IntelliJ Platform SDK - Custom Language Support](https://plugins.jetbrains.com/docs/intellij/custom-language-support.html)
- [IntelliJ Platform SDK - PSI Helpers](https://plugins.jetbrains.com/docs/intellij/psi-helper-and-utilities.html)
- [JetBrains Support - Go To Implementation](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206110419-How-to-implement-go-to-to-implementation-)
- [JetBrains Support - Multi-IDE Language Support](https://intellij-support.jetbrains.com/hc/en-us/community/posts/4937400658450-how-to-target-multiple-IDEs-with-language-support)
- [IntelliJ Platform Explorer - Extension Points](https://plugins.jetbrains.com/intellij-platform-explorer)

---

## Appendix B: Document History

| Version | Date | Description |
|---------|------|-------------|
| 1.0 | 2025-11-29 | Initial plan document |
