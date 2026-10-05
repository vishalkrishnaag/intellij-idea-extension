# Felidae IntelliJ IDEA Plugin

IntelliJ IDEA language support for Felidae `.fx` files, backed by the
the Felidae interpreter's AST analysis for diagnostics and Celidae for fact graph inspection.

## Features

- Registers `.fx` as Felidae files
- Basic syntax highlighting for comments, strings, numbers, keywords, operators, and core library calls
- Diagnostics from `felidae --check-json`, including C++ AST analyzer warnings
- Celidae visual analytics action using `celidae --inspect-graph`
- Brace matching for `()`, `{}`, and `[]`
- File type, action, and tool-window icons
- Live templates for `main`, facts, methods, fallback rules, lambdas, `throw`, and fact queries
- Quick Documentation (Ctrl+Q) for stdlib calls, backed by the same content the VS Code extension shows
- Completion for stdlib module calls and in-scope facts/methods/globals/bindings
- A gutter icon above `main(...)` to run (click) or check/visualize (right-click)
- Go to Declaration (Ctrl+B / Ctrl+Click) for facts, methods, and stdlib calls
- A "Run Felidae Query..." action, mirroring the VS Code extension's Run Query command
- A Settings > Tools > Felidae page for the felidae/celidae executable paths

The plugin intentionally does not implement Felidae semantic validation in Java.
It delegates file checks to felidae so IntelliJ IDEA, VS Code, and the
runtime stay aligned. Celidae is a separate tool dedicated to fact-relationship
visualization (ER diagrams, graphs, tree diagrams, statistical views) and has
no diagnostics support of its own.

The plugin uses `felidae --check-json` for diagnostics, so it stays lightweight
and does not duplicate language semantics in Java. (The interpreter has no
`--lsp` mode.)

## Interpreter and platforms

Checks and execution use the same interpreter setting. User programs launch
through Command Prompt on Windows and /bin/sh on Linux/macOS. Interpreter paths
in Settings are relative to the project directory unless absolute. Use
felidae.exe on Windows and felidae on Linux/macOS. When the setting is empty the
plugin uses FELIDAE_PATH, then looks in the project's `dist/bin`, `build/release`
and `build/debug` (including `build/debug/x64/Debug`) folders, then on PATH. When
nothing is found, the console lists these places.

This plugin provides Run and Check actions; it does not implement an IntelliJ
debug adapter. Interactive stepping is currently available through VS Code or
the interpreter's --debug terminal protocol.

## Executable discovery

By default the plugin looks for:

```text
<project-root>/dist/bin/felidae
<project-root>/release/bin/felidae
<project-root>/build/release/dist/bin/felidae
<project-root>/build/release/felidae
```

You can override this with:

```powershell
$env:FELIDAE_PATH="C:\path\to\felidae.exe"
```

Alternatively, set explicit paths under **Settings | Tools | Felidae** — those
take priority over both environment variables and auto-detection.

## Run

```powershell
.\gradlew.bat runIde
```

Use **Tools | Check Felidae File** for diagnostics and
**Tools | Visualize Felidae Data Graph** for the runtime graph snapshot.

## Build

```powershell
.\gradlew.bat buildPlugin
```

The packaged plugin is written under:

```text
build/distributions/
```

Install it from IntelliJ IDEA with **Settings | Plugins | Install Plugin from
Disk...**. Development builds may also create jars under `build/libs/`, but the
Gradle distribution artifact is the expected install package.
