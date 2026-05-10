# log-weaver

A Maven plugin that weaves `java.lang.System.Logger` calls into compiled classes,
driven by the annotations defined in
[log-api](https://github.com/ralfspoeth/log-api) (`@Log`, `@LogAll`).

The goal is to keep production code free of `if (logger.isLoggable(...))`
boilerplate while still emitting structured, lazy log records — without pulling
in a third-party logging facade.

## How it works

`log-weaver` runs as a Maven plugin in the `process-classes` phase, after
`maven-compiler-plugin` has produced `*.class` files. For every class under
`${project.build.outputDirectory}`:

1. It scans `module-info.class` and every `package-info.class` once for
   `@LogAll` configuration (these become "scope defaults").
2. For each regular class it parses the bytecode via the JDK's
   `java.lang.classfile` API and looks for `@Log` on methods plus an effective
   `@LogAll` for the class.
3. If anything matches, the class is rewritten in place:
   - A synthetic `private static final System.Logger $logweaver$LOGGER` field
     is added (named after the class' fully-qualified name).
   - Either a fresh `<clinit>` is generated, or the existing one is prepended
     with `$logweaver$LOGGER = System.getLogger("<fqcn>");`.
   - Each annotated method gets exactly **one** parameter-aware log call —
     entry vs. return is mutually exclusive:
     - `logReturn = false` (the default): a single entry log before the body,
       capturing the (boxed) parameters.
     - `logReturn = true`: a single return log before each `XRETURN`,
       capturing the parameters and (for non-void methods) the return value.
   - On top of that, the body is **always** wrapped in a `Throwable` catch
     that calls `logger.log(exceptionLevel, t.getMessage(), t)` and re-throws.
     `exceptionLevel` defaults to `WARNING`; setting it to `OFF` keeps the
     catch in place but lets the JDK discard the log call.
   - The `@Log` annotation is stripped after weaving, so a re-run is a no-op.

Lazy formatting is preserved: the parameter-aware log call builds a
`Supplier<String>` through `invokedynamic`
(`LambdaMetafactory.metafactory`) and calls
`Logger.log(Level, Supplier<String>)`. The `Supplier` captures the (boxed)
parameters and only formats the message when the underlying handler decides
the record is loggable.

## Requirements

- JDK 25 (the plugin uses the stabilised `java.lang.classfile` API).
- Maven 3.9+.
- `log-api` on the **runtime classpath** — `log-weaver` reads its annotation
  defaults reflectively at plugin startup, so any future change to
  `@Log`/`@LogAll` defaults takes effect automatically.

## Quick start

Add the plugin to the project that should be woven, plus the `log-api`
dependency for the annotations themselves:

```xml
<dependencies>
    <dependency>
        <groupId>io.github.ralfspoeth</groupId>
        <artifactId>log-api</artifactId>
        <version>0.5</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>io.github.ralfspoeth</groupId>
            <artifactId>log-weaver-maven-plugin</artifactId>
            <version>0.5</version>
            <executions>
                <execution>
                    <goals>
                        <goal>weave</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

The default execution binds to `process-classes`, so a plain `mvn package`
is enough.

## The annotations

### `@Log` (per method)

```java
@Log                                          // entry log (params) at INFO;
                                              // exception handler at WARNING
String greet(String name) { … }

@Log(level = ERROR, logReturn = true)         // return log (params + result)
                                              // at ERROR; no entry log
int compute(int x) { … }

@Log(exceptionLevel = OFF)                    // entry log; the catch is still
                                              // installed but Level.OFF makes
                                              // the JDK drop the log record
void fireAndForget() { … }
```

Entry vs. return is mutually exclusive: `logReturn = true` *replaces* the
entry log with a return log rather than emitting both. The exception
handler is always installed.

The values are resolved at weave-time by reading the annotation class'
`getDefaultValue()`s, so the table above mirrors whatever `log-api` ships.

### `@LogAll` (per type, package, or module)

```java
// On a class — apply to every public method whose name starts with "do":
@LogAll(modifiers = Modifier.PUBLIC, methodPattern = "do.*")
public class Service { … }

// On a package — applies to every class in this package
//   (file: package-info.java)
@LogAll(level = DEBUG)
package com.example.app;

// On a module — applies to every package in this module
//   (file: module-info.java)
@LogAll
module com.example.app { … }
```

`@LogAll` drives the **entry log** at its declared level. The
always-on exception handler is installed too, at the `@Log` default
level (WARNING). To switch to return-logging on a specific method, or to
override the exception level, place an explicit `@Log` on that method.

#### Resolution order

For each method:

1. A method-level `@Log` wins outright.
2. Otherwise the most-specific `@LogAll` that **matches** the method applies:
   class-level → package-info → module-info.
3. Otherwise the method is left alone.

A method "matches" a `@LogAll` when:
- it is not `<init>` or `<clinit>`,
- it is not `abstract`/`native`/synthetic/bridge,
- its access flags satisfy `modifiers` (the value `0` means "any visibility",
  otherwise OR-semantics: at least one of the requested bits must be set),
- its name matches `methodPattern` (a `java.util.regex` pattern; default `.*`).

## Generated artifacts

For `pkg.Cls.foo(int, String) -> int` with `@Log(logReturn = true)`,
the woven class contains:

| element | name | shape |
|---|---|---|
| field | `$logweaver$LOGGER` | `private static final System.Logger` |
| helper | `lambda$logweaver$foo$<hash>` | `(Integer, String, Integer) -> String` (return message: params + boxed result) |

For the same method declared as `@Log` (logReturn defaulting to false), the
single helper would instead be `(Integer, String) -> String` and would
format the entry message.

Helper names include a hash of the method descriptor so overloads stay
distinct, and so a re-run can detect "already woven" methods and skip them.
There is exactly one helper per woven method.

The default messages are synthesized as
`<SimpleClassName>.<method>(%s, …)` for entry logs and
`<SimpleClassName>.<method>(%s, …) -> %s` (or `-> void`) for return logs —
there is no per-method "message" attribute on `@Log`.

## Idempotency

Two layers protect against double-weaving:

- The `@Log` annotation is stripped after a successful weave.
- The synthetic helper name is deterministic, so even if a class is woven and
  then has `@Log` re-added at source level, the second weaver pass will see
  the existing helper and skip the method.

Running `mvn process-classes` twice in a row produces byte-identical
`.class` files.

## Building & testing

```sh
mvn clean verify
```

Tests build their input classes directly through `java.lang.classfile`, so
the plugin is self-contained and does not need a separate fixture project.
Surefire is configured to redirect test output to files (see `pom.xml`),
which makes interleaved log lines from concurrent fixtures readable.

## Project layout

```
src/main/java/io/github/ralfspoeth/log/weaver/LogWeaver.java
    – the @Mojo "weave"; does the entire transformation in one pass.
src/test/java/io/github/ralfspoeth/log/weaver/LogWeaverTest.java
    – JUnit 5 tests covering @Log, @LogAll, return logging,
      exception handling, scope resolution, and idempotency.
```

## License

See the parent POM (`io.github.ralfspoeth:plumbum`) for license details.
