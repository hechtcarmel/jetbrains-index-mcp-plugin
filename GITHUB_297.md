# Review notes — PR #297 (record component symbol lookup)

Verdict up front: the diagnosis is right, the fix is the right shape and in the right
place, and I want this merged — but not quite yet. CI hasn't executed the new test at
all (first-time contributor, the workflow run is still waiting for approval), and there
are two small things in the diff that should be cleaned up while it's open. Details
below.

## What's going on, and why the fix is correct

Symbol-mode lookup (`language` + `symbol`) for a record member, e.g.
`example.Config#name`, lands in `JavaSymbolReferenceHandler.resolveMemberByName`, which
tries `findFieldByName` before methods. On a record that returns the *synthetic*
backing field IntelliJ fabricates per component (`RecordAugmentProvider` wraps each one
in a `LightRecordField`), and a reference search keyed on that field is guaranteed to
come back empty — for two independent reasons, not one:

1. Accessor call sites like `config.name()` resolve to the light accessor *method*,
   never to the field, so they are not references to the search target.
2. `LightRecordField.getUseScope()` is a `LocalSearchScope` of the containing class,
   and `ReferencesSearch` intersects the requested scope with the target's use scope.
   The search physically cannot leave the record's own file, whatever scope the tool
   passes.

So the `0 usages, totalIsExact: true` in the bug report wasn't flakiness or an indexing
hiccup; it's structural. And it's the worst kind of wrong answer this tool can give —
an agent reading "zero usages, and that count is exact" on a symbol that has live
callers will conclude it's dead code. Same family as the false-result batch we fixed in
5.0.2.

Resolving to the `PsiRecordComponent` instead is exactly what the platform itself
considers canonical, which I confirmed in intellij-community rather than assuming:

- `JavaRecordComponentSearcher` (registered in the Java plugin since 2020.1) is keyed
  on the component and fans out to the accessor method usages, field references inside
  the record body, and compact-constructor parameter references. That union is what the
  IDE's Find Usages shows in the screenshot in the PR description.
- JetBrains' own platform test (`FindUsagesJava14Test`) passes
  `record.recordComponents[0]` straight into `ReferencesSearch.search`.
- The IDE's target-element evaluator normalizes light record members back to the
  component (`LightRecordMember.getRecordComponent()`), which is also the same
  direction our own rename tool already substitutes via
  `JavaPsiRecordUtil.getRecordComponentForAccessor`.

The blast radius is comfortably narrow. The new branch only fires for record classes
with an exact component-name match; records can't collide a component with another
same-name field (the implicit private field already occupies the name), and the other
tools that share this resolver (find_definition, call_hierarchy, find_super_methods,
find_implementations) treat a record component no worse than they treated the synthetic
field. `UsageViewUtil.getType` on a real `PsiRecordComponent` yields exactly
"record component", so the `kind` assertion in the test is correct too — the pre-fix
`"kind": "field"` in the bug report came from `JavaElementKind` checking `PsiField`
before `PsiRecordComponent`, which only ever sees the light field.

Checklist-wise this one is easy: changelog entry is under `[Unreleased]` / `Fixed` with
no version section, no schema or result-model changes so no golden-file regeneration,
and the static checks in `scripts/check-pr.sh` all pass on the diff.

## What needs to change before merge

1. **CI must actually run.** Nobody but the author has executed
   `FindUsagesRecordComponentBehaviorTest` — approve the workflow run and wait for
   green. (I couldn't get the platform test tier to run on the machine I reviewed from,
   so the executable proof has to come from CI.) Everything in this fix checks out on
   paper; I still want to see the suite say so.

2. **Reword the comment in `resolveMemberByName`.** "For records, first go over the
   component over the backing field (ReferencesSearch finds 0 on fields)" is garbled,
   and "finds 0 on fields" overstates — ReferencesSearch is perfectly fine on ordinary
   fields; the problem is specific to the synthetic record backing field. This comment
   is the only in-code record of a genuinely subtle platform behavior, so the wording
   matters. Suggestion:

   ```kotlin
   // Records: prefer the PsiRecordComponent over the synthetic backing field.
   // Accessor calls resolve to the light accessor method, and the light field's
   // use scope is local to the record, so ReferencesSearch on the field finds
   // nothing outside the record body.
   ```

3. **Drop the stray import in the test:**
   `import com.intellij.platform.ide.progress.ModalTaskOwner.project`. That's an IDE
   auto-import accident — it has nothing to do with the test and only compiles because
   the fixture's `project` property shadows it. It will send the next reader down a
   modality rabbit hole for no reason.

Take-or-leave polish, wouldn't block on either: the private helper is named
`testRecordComponentSymbol`, which reads like a JUnit 3 test method at first glance (it
isn't picked up — private, takes a parameter — but a name like
`assertRecordComponentUsagesFound` avoids the double take). And its failure message
says "for symbol-based lookup" even though the position-based variant runs through the
same helper.

## Observations, nothing requested

- The position-based test variant passes on main too — position lookup already walks up
  from the identifier to the component. Only the symbol-based variant is red without
  the fix. That's fine (it pins the adjacent path against regressions), just worth
  knowing when reading the test.
- The paren form `example.Config#name()` takes the method-resolution path and returns
  the light accessor. That still finds the accessor call sites — method search handles
  light methods — it just misses field references inside the record body and reports
  `kind: "method"`. Not broken, so out of scope here, but a small follow-up could pin
  it with a test and, if we want full parity, substitute the component there too using
  the same `JavaPsiRecordUtil` mapping the rename tool uses.
- Component search does not return record deconstruction patterns
  (`case Config(String n, int t)`) — the platform's searcher is word-based and pattern
  bindings are positional, so there's nothing textual to match. Platform limitation,
  not something this PR can address.

Once CI is green and items 2–3 are in, this should merge. Good find — record-heavy
codebases (config properties, DTOs) hit this constantly, and the failure mode was the
quietly-confident wrong answer we've been systematically hunting down.
