# Felidae IntelliJ IDEA Plugin

IntelliJ IDEA language support for Felidae `.fx` files. Felidae compiles a program to JVM class files and runs them;
the plugin delegates diagnostics, running and debugging to the `felidae` command.

## Features

- Registers `.fx` as Felidae files
- Syntax highlighting for comments, strings, atoms, numbers, keywords (`def`, `define`, `class`, `private`, `static`, `new`, `import`, `package`,
  ...), types (`Integer`, `List<...>`, `Optional<...>`, ...), operators and the `db` / `console` / `system` libraries
- Diagnostics from `felidae --check-json`: syntax errors and type errors (the compiler's own analysis)
- **Run** (`felidae file.fx`) and **Debug** of a file or of its `main()`: the gutter icon next to `def main()` runs on click
  and has Debug / Check in its menu; **Run | Debug Felidae File** (Ctrl+Alt+F11)
- **Debugging** with IntelliJ's own JVM debugger (needs the bundled Java plugin): line breakpoints in `.fx` files,
  stepping, the call stack, and variables by their Felidae names — see "Debugging" below
- Brace matching for `()`, `{}` and `[]`, folding, structure view (functions and classes), commenter
- Live templates for functions, classes, `new`, mixfix operators, queries, `Link`, `try`, `switch`, ... (the same set
  as the VS Code snippets)
- Quick Documentation (Ctrl+Q), completion and parameter info for builtins, class members (`count`, `where`, `join`, ...)
  and the file's own functions and classes, backed by the shared `docs/builtin-docs.json`
- Go to Declaration (Ctrl+B) for functions and classes
- **Run Felidae Query...**: evaluates an expression against the current file (a generated program imports the file)
- A **Settings | Tools | Felidae** page for the felidae command

The plugin intentionally does not implement Felidae semantic validation in Java. It delegates file checks to
`felidae --check-json` so IntelliJ IDEA, VS Code and the compiler stay aligned. There is no parser or PSI for `.fx`
files yet: highlighting, folding and completion read the text.

## The felidae command

Build it with `./gradlew installDist` in the Felidae repository (JDK 25). The plugin looks for
`<project>/build/java/cli/install/felidae/bin/felidae` (`felidae.bat` on Windows), then `dist/bin`, `release/bin` and
`bin` under the project, then the `FELIDAE_PATH` environment variable, then `PATH`. A path set under
**Settings | Tools | Felidae** (relative to the project unless absolute) wins over all of these. Run and Check use the
same setting. Programs that keep facts need an `init.fx` beside them, as for any run.

## Debugging

**Debug** runs `felidae --debug --port N file.fx`: the compiler writes the source file name, a line for every
statement and the Felidae names of parameters and bindings into the generated classes, and starts the program's JVM
suspended with a JDWP agent. IntelliJ's Java debugger attaches to that port (`FelidaeDebugRunner`), and three small
pieces teach it about `.fx` files:

- `FelidaeLineBreakpointType` accepts line breakpoints in Felidae files;
- `FelidaePositionManager` maps a JVM location (source name, line) to the `.fx` file and back, and finds the classes
  of a file, also those loaded later;
- `felidae-java.xml` registers them (an optional dependency on the Java plugin: the rest of the plugin works in IDEs
  without it).

The embedded fact store and list helpers have no line numbers, so stepping never enters them. Methods with named
parameters appear as `name$param` in the JVM (`twice$k`). Not done yet: renderers that show atoms (a JDK `Optional`
holding the spelling) and Felidae objects the way `print` shows them, and expression evaluation in Felidae syntax
(the evaluator is Java's).

## Run

```sh
bash ./gradlew runIde
```

## Build

```sh
bash ./gradlew buildPlugin
```

The packaged plugin is written under `build/distributions/`. Install it from IntelliJ IDEA with **Settings | Plugins |
Install Plugin from Disk...**. `build/`, `.gradle/` and `.intellijPlatform/` are generated and ignored by git.

`src/main/resources/builtin-docs.json` is copied from `docs/builtin-docs.json` of the Felidae repository (this plugin is
its submodule `dependencies/intellij-idea-extension`) when the plugin is built; set `FELIDAE_DOCS` to use another copy.
