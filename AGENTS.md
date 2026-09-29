# Hoshi Reader Android Agent Guide

This is an Android-only app. Hoshi Reader Android is a native Android / Kotlin / Jetpack Compose project. It is developed in focused vertical slices of user-visible behavior.

## Working Principles

- Before starting a feature slice or behavior fix, summarize the interaction, state flow, and edge-case behavior first.
- Fix issues at the root cause; do not layer patches. Align behavior and state flow first, and add only the minimal adaptation truly required.
- For Android platform capabilities, permissions, SAF, WebView, Media3, WorkManager, Google/Jetpack APIs, packaging, installation, or background behavior, follow the current recommendations in the official Android / Google / Jetpack documentation.

## Workspace and Planning Artifacts

- Do not create or use Git worktrees unless the user explicitly requests it; work directly in the provided workspace and branch.
- Do not create, force-track, or commit design/plan documents (including `docs/superpowers/specs` and `docs/superpowers/plans`), and do not create separate documentation commits for them, unless the user explicitly requests it; keep plans in the conversation or built-in plan state by default.

## Architecture Baseline

### Recommended Android Patterns

- UI: Jetpack Compose and Material 3.
- Navigation: use the current Navigation3 typed route / back-stack pattern with `AppShell`, `NavDisplay`, and route keys. Top-level tabs use independent Nav3 back stacks, each holding its own `rememberSaveableStateHolderNavEntryDecorator()` and `rememberViewModelStoreNavEntryDecorator()`; do not replace correct tab/route lifecycles with an Activity-scoped ViewModel scope.
- State: ViewModels expose immutable UI state via `StateFlow`; screen UI is organized as state down / events up.
- Data: repositories own files, database, dictionary, EPUB, network, and dispatcher boundaries for blocking work; use Kotlin coroutines for async work and never block the main thread.
- DI: the production dependency graph uses Hilt; prefer constructor injection for new dependencies and wire them through Hilt modules/qualifiers; ViewModels use `@HiltViewModel`; do not add hand-written app containers or spread object-graph creation logic across Composables. The existing `HoshiUiDependencies` is only a lazy Compose transition bridge and must not be used to manually build the object graph.
- Platform: choose Android APIs according to platform semantics and official documentation.
- Settings: small settings and preferences use a DataStore-backed repository; do not read or write DataStore directly from Composables.
- Background work: tasks that must run reliably across processes, restarts, or after leaving the visible state use WorkManager; worker dependencies use Hilt worker injection, not hand-written lookup.
- EPUB import: import through the Android Storage Access Framework into app-specific storage.

### Hoshi Project Constraints

- JSON: Kotlin Serialization or Moshi.
- Storage: existing sidecar JSON must remain compatible.
- Reader: keep WebView-based reading and lookup; prefer `WebViewAssetLoader` or the repository's existing secure loading path for local WebView resources; do not enable broad file-URL access such as `allowUniversalAccessFromFileURLs`.
- Reader JS/CSS: long-lived reader web code lives in `app/src/main/assets/hoshi-web`; do not add large Kotlin string scripts. Keep only small typed commands, argument escaping, asset loading, dynamic config population, and bridge calls on the Kotlin side.
- Reader popup coordinates: `hoshiPopupGeometry` in `popup.js` is the sole entry point for popup coordinate conversion, scroll position, and element alignment under CSS `zoom`. Popup code must not mix unscaled layout coordinates such as `offsetTop` with visual scroll coordinates such as `scrollTop` / `getBoundingClientRect()`, and must not add separate zoom compensation.
- Reader web shared semantics: `reader-text-semantics.js` is the shared entry point for text normalization, matchable/raw counting, and matchable-character decisions across the three reader modes; `reader-dom-text.js` is the shared live-DOM ruby/text normalization entry point for paginated/continuous; `reader-media-semantics.js` is the shared image-setup entry point across the three reader modes, including SVG image aspect-ratio correction, large-image block marking, blur wrappers, tap-to-native-image bridges, and scoped setup; `reader-layout-semantics.js` is the shared entry point across the three reader modes for cleaning up out-of-bounds publisher inline-blocks and empty struts after fonts and source images are ready. VN may use the current screen scope without waiting for image load, but must not reintroduce a VN-private equivalent of image setup. `reader-vn-content-stream.js` and `reader-vn-range-map.js` are VN-specific runtime primitives owning the VN source stream, ruby-aware text entries, structure IDs, standalone media units, as well as raw/highlight ranges, matchable Sasayaki ranges, and source-to-clone offset registration for rendered VN screens. VN is a special paginated mode; do not reintroduce VN-private text-offset, range, media-classification, or clone-offset implementations, and do not name VN-only logic as shared reader core. Do not connect the paginated/continuous page/scroll runtime to a VN content stream instance or VN range map without a separate proposal and validation plan first. VN lookup text, full sentences, and in-chapter normalized offsets must be resolved from the source stream; the current screen clone is only responsible for tap hit-testing, popup anchoring, and the currently visible range, and its truncated clone text must not be used for lookup or card-creation semantics.

## Source-of-Truth Documents

- `docs/VALIDATION.md`: required build/test commands, emulator data-safety rules, test data, and the long-lived manual validation matrix.
- `docs/CHANGELOG.md`: records user-visible app changes only. User-visible changes require an `[Unreleased]` update.
- `docs/ARCHITECTURE.md`: current Android architecture facts.
- `docs/ARCHITECTURE_REFACTORING.md`: future architecture debt and refactoring direction.
- `docs/PERFORMANCE.md`: baseline measurements, hot-spot localization, and validation workflow for Reader/WebView and other performance issues.

Update a document only when the task changes that document's source-of-truth content.

## Lessons Learned

- When an agent mistake leads to a verified correct practice that may recur in future sessions, record the minimal actionable rule in the corresponding source-of-truth document.
- Only repo-level rules needed by every session belong in `AGENTS.md`; validation steps belong in `docs/VALIDATION.md`; current architecture facts belong in `docs/ARCHITECTURE.md`; future refactoring direction belongs in `docs/ARCHITECTURE_REFACTORING.md`.
- Recorded lessons must be concrete, actionable, and unambiguous; do not write one-off investigation notes, long logs, task status, temporary workarounds, or details that apply only to the current issue.
- Before recording, check whether an equivalent rule already exists; if so, update the existing rule instead of adding a duplicate.

## User-Visible UI

- This project does not target Android accessibility services or screen readers (including TalkBack). Unless the user explicitly requests it, do not add or require `contentDescription`, Compose accessibility semantics, TalkBack selection state or announcements, accessibility focus/traversal order, or other screen-reader adaptations during design, implementation, review, or acceptance; a change must not be judged defective solely because those adaptations are missing. Existing accessibility-specific code is not required compatibility behavior and may be removed after confirming that ordinary user interaction is unaffected, to reduce code size and maintenance burden.
- All user-visible UI strings must use Android localized resources.
- Compose uses `stringResource()` / `pluralStringResource()`.
- Visible messages emitted from non-UI layers should use `UiText` or an equivalent resource reference and must not hold a `Context`.
- When adding or changing visible copy, update both `app/src/main/res/values/strings.xml` and `app/src/main/res/values-zh-rCN/strings.xml`. Keep placeholders, plural quantities, CDATA/escaping, and `translatable="false"` semantics consistent.
- For new or replacement icons, use Material Icons, Material vector assets, or explicit brand assets; do not hand-draw, invent, or temporarily splice together icons.
- User-visible errors should use existing dialogs, Snackbars, or explicit error states; do not render raw exception text directly into primary UI content.

## Domain Boundaries

- Domain facts and current module boundaries follow `docs/ARCHITECTURE.md`; future split direction follows `docs/ARCHITECTURE_REFACTORING.md`.
- Before changing dictionary, reader, parser, Anki, sync, audio, or Sasayaki, check the corresponding current architecture boundary; do not bypass existing bridge, repository, backend, or controller boundaries without recording the gap first.
- For reader, parser, sync, audio, and other user-visible behavior, follow the current Android behavior and architecture boundaries; use the official Android/Google/Jetpack approach for Android platform mechanisms.

## Testing and Submission

- Before declaring implementation complete, validate according to `docs/VALIDATION.md`.
- Do not run connected Android instrumentation tasks that clear, reinstall, or uninstall app data unless the user explicitly allows a one-shot device.
- Unit-test fixtures must not depend on local `testdata/` files ignored by `.gitignore`; samples needed by CI must be tracked or constructed by the tests themselves.
- Prefer behavior, API, and state-flow tests; do not write tests for production source-string implementation details. Only structured configuration such as manifest, resources, Gradle, permission, or provider declarations may be asserted after parsing the structure.
- Commit messages use Conventional Commits.
- After development is complete and required validation has passed, commit the changes for this task; skip committing only when the user explicitly asks not to commit or to keep changes uncommitted.
- The changelog records only user-perceivable app changes; do not record CI, agent workflow, build scripts, dependency management, or internal refactoring.
- Follow-up adjustments to unreleased features in `[Unreleased]` should be merged into the original Added/Changed entry; only issues users could encounter in a released version get a Fixed entry.
- If implementing or fixing a GitHub issue, reference the issue in the user-visible changelog entry when applicable, and use a closing keyword in the commit message.
- Small low-risk issue fixes may land directly on `main`; larger features, cross-module refactors, or high-risk changes use a `codex/` branch.
