# Test support

## `src/test/kotlin/com/jetbrains/python/**` — why these fakes exist, and why the set is exactly this

`PythonDefinitionResolver` (production) has no compile-time dependency on PyCharm. It resolves
Python PSI types reflectively and uses `Class.forName(...).isInstance(...)`:

```kotlin
private val pyCallExpressionClass by lazy { loadClass("com.jetbrains.python.psi.PyCallExpression") }
private val pyReferenceExpressionClass by lazy { loadClass("com.jetbrains.python.psi.PyReferenceExpression") }
private val pyResolveContextClass by lazy { loadClass("com.jetbrains.python.psi.resolve.PyResolveContext") }
```

When those classes are absent, `loadClass` returns null and the entire Python refinement path is
a no-op. So the only way to test that logic — which decides whether Python "go to definition"
lands on the function or on its containing package directory — is to have classes at those exact
FQNs on the test classpath.

The set is therefore deliberately minimal:

| Fake | Needed by |
|---|---|
| `PyElement` | base interface for the others |
| `PyExpression` | `getQualifier()` return type |
| `PyReferenceExpression` | `pyReferenceExpressionClass.isInstance(...)` |
| `PyCallExpression` | `pyCallExpressionClass.isInstance(...)` |
| `resolve/PyResolveContext` | `pyResolveContextClass` |

Used by `util/PsiUtilsPythonFallbackUnitTest.kt`.

### `PyClass` must never be added back

`PluginDetectors.python` declares `fallbackClass = "com.jetbrains.python.psi.PyClass"`, and
`PluginDetector` resolves availability via `Class.forName(fallbackClass)`. A test-tree class at
that FQN makes `PluginDetectors.python.isAvailable` return **true in every test**, cached
`by lazy` on an `object` for the whole fork.

That is not hypothetical. It used to be the case, and the consequences were:

- `PythonHandlers.register()` passed its gate and registered six handlers, while the real index
  classes it depends on (`PyClassNameIndex`, `PyClassInheritorsSearch`, `PyFile`) were absent —
  a state no shipped IDE can reach.
- The stubs *defined* the API the tests validated against, and they had already drifted from
  PyCharm's real API (`PyResolveContext.defaultContext()` taking no arguments, where the real one
  takes a `TypeEvalContext`).
- `PyStaticCallHierarchyUtil.getCallers()` ignored its `element` argument entirely, so the call
  hierarchy test proved only that the handler forwards a predetermined map. A wrong-element bug
  was undetectable.

`PluginDetectorLeakUnitTest` asserts `PluginDetectors.python.isAvailable == false`, and
`scripts/check-pr.sh` checks that no test class sits at any detector's fallback FQN.

### For anything else, don't do this

Fake PSI types belong in the test's own package, duck-typed against only the shape the
production reflection requires. See `handlers/php/PhpSymbolReferenceHandlerUnitTest.kt` and the
`QualifiedNamedElement` interface in `handlers/python/PythonSymbolReferenceHandlerUnitTest.kt`.

## `McpPlatformTestCase`

Base class for tests that drive MCP tools end to end. Provides on-disk fixture creation
(`writeProjectFile`), source-root registration, tool-result helpers, and both-direction
refactoring assertions (`assertRenamedInFile`). Cleans up files the test created in `tearDown`,
because the light fixture reuses one project directory across all test methods in a class.

**Never build fixtures with `myFixture.addFileToProject`** — it writes to the in-memory
`TempFileSystem`, which `LocalFileSystem` (the only filesystem the production resolvers consult)
cannot see. See the class KDoc.
