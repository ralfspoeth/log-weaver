# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0] — 2026-09-18

First stable release. No API changes since 0.13; the version bump signals that
`@Log` / `@LogAll` semantics, the Maven-plugin goal, and the agent's manifest
contract are now considered stable under SemVer.

### Fixed
- **Empty varargs no longer leaves a trailing `", "` in the log message.** The
  synthetic per-class helper `$logweaver$va` now takes two arguments — the
  `Arrays.toString(...)` output *and* a leading separator — and drops both when
  the varargs array is empty. `foo(2)` with a `String... args` parameter now
  logs as `Cls.foo(2)`, not `Cls.foo(2, )`.
- Shade no longer warns about the `META-INF/MANIFEST.MF` overlap in
  `log-weaver-agent`: shading now filters both input manifests out, and the
  `ManifestResourceTransformer` writes the final one (with the agent's
  `Premain-Class` / `Agent-Class` entries).
- Shade no longer warns about `module-info.class` in `log-weaver-agent`:
  both `module-info.class` files (agent's own and the shaded-in core's) are
  filtered out, since a `-javaagent:` jar runs on the system class loader as
  an unnamed module regardless.

### Changed
- `README.md` now names the license explicitly (MIT, see `LICENSE`) instead of
  deferring to the parent POM.
- Parent POM declares `<licenses>` (MIT) and `<developers>` explicitly, so the
  effective POM Central sees at deploy time carries them without relying on
  the plumbum parent.

## [0.13] — Interfaces, records and sealed-hierarchy hidden cases

### Added
- `Scopes.sealedPermits` — the set of package-private permitted subtypes of a
  sealed type, collected while the classes directory is scanned. A scoped
  `@LogAll` now uses this to skip those cases.

### Changed
- A `@LogAll` inherited from `package-info` or `module-info` no longer reaches
  three kinds of type:
  1. **interfaces** — methods are abstract, `default` or `static`, and a
     logger field in an interface may only be `public static final` (JVMS 4.5);
  2. **records** — accessors, `equals`, `hashCode`, `toString` and the canonical
     constructor are compiler-generated;
  3. **package-private permitted subtypes of a sealed type** — an
     implementation detail the package deliberately kept to itself.
  A `@LogAll` written *on* the type still applies to it.

### Fixed
- **`ClassFormatError: Illegal field modifiers` when weaving an interface.**
  The synthetic logger field is now `public static final synthetic` inside an
  interface (was `private static final synthetic`, which the verifier rejects).
  Interfaces are also no longer swept up by a scoped `@LogAll`, so the code
  path is defensive only.

## [0.12] — `logReturn` defaults to `true`

### Changed
- `@Log.logReturn` now defaults to `true`. A bare `@Log` therefore emits a
  return log (parameters + boxed return value) rather than an entry log.
  Entry-only logging is still one attribute away: `@Log(logReturn = false)`.

## [0.11]

### Changed
- Small internal cleanups; no user-facing behavior changes.

## [0.10] — BOM

### Added
- `log-weaver-bom` module — a Bill of Materials importing `log-api`,
  `log-weaver-core`, `log-weaver-maven-plugin` and `log-weaver-agent` at one
  managed version. Consumers can then reference any of them without pinning a
  version.

## [0.9] — Bundled `log-api`

### Added
- `log-api` moved under this multi-module build. `@Log` and `@LogAll` now
  release in lockstep with the weaver, which is the only consumer that
  materially cares about their shape.

## [0.8] — Core + agent split

### Added
- `log-weaver-core` — the transformation engine, extracted so the same code
  drives the Maven plugin and the new agent. No build-tool dependencies.
- `log-weaver-agent` — a Java agent that runs the core as a
  `ClassFileTransformer` at class-load time. Shipped as a shaded jar with a
  `Premain-Class` manifest entry, so `-javaagent:log-weaver-agent-<version>.jar`
  is sufficient.
- Varargs support: a method's `String...` (or primitive `...`) parameter is
  logged as comma-separated elements via a per-class helper, not as the
  array's identity hash.

### Changed
- Module renamed from `log-weaver-maven-plugin` to `log-weaver`; the Maven
  plugin is now the `log-weaver-maven-plugin` submodule.

## [0.1–0.7]

Iterative development of the original `log-weaver-maven-plugin` — the initial
`@Log` / `@LogAll` support, the `weave` goal bound to `process-classes`, and
the invokedynamic-based `Supplier<String>` message plumbing for lazy formatting.

Tags for those releases carry the pre-rename artifact name
(`log-weaver-maven-plugin-0.4` through `log-weaver-maven-plugin-0.7`).

[1.0]: https://github.com/ralfspoeth/log-weaver/releases/tag/log-weaver-1.0
[0.13]: https://github.com/ralfspoeth/log-weaver/releases/tag/log-weaver-0.13
[0.12]: https://github.com/ralfspoeth/log-weaver/releases/tag/log-weaver-0.12
[0.11]: https://github.com/ralfspoeth/log-weaver/releases/tag/log-weaver-0.11
[0.10]: https://github.com/ralfspoeth/log-weaver/releases/tag/log-weaver-0.10
[0.9]: https://github.com/ralfspoeth/log-weaver/releases/tag/log-weaver-0.9
[0.8]: https://github.com/ralfspoeth/log-weaver/releases/tag/log-weaver-0.8
