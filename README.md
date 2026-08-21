# Lyric for JetBrains IDEs

The thin plugin: it recognises `.lyr`, renders the bundled TextMate grammar,
starts `lyrls`, and runs a program through the toolchain's driver. Everything language-aware —
diagnostics across the project, completion, the
jump, references in both directions, rename, semantic highlighting, signature help, folding,
inlay hints — is the IDE's own LSP integration consuming the same server every other editor
talks to. There is deliberately **no PSI implementation**: that would be a second frontend in
Kotlin with a permanent lag behind the grammar, which is the kind of parallel mechanism this
project does not build.

## Running a program

A **Lyric** run configuration names a program (`.lyr` or a built `.lyrbc`), the arguments its
`main` receives, and the directory to run in. The editor's context menu on a `.lyr` file offers
one directly — *Run 'main.lyr'* — so the dialog is only needed to add arguments.

What runs is `lyric run`, the driver: the one command that compiles and executes in one step, and
the same command a terminal would use on that file. A compiler diagnostic in the console carries
its `file:line:column` as a link into the editor.

## Requirements

- A **commercial** JetBrains IDE, **2026.1 or newer** — the platform's LSP API does not exist in
  the Community editions, and the features this plugin relies on (rename arrived in 2026.1,
  signature help and workspace symbols in 2025.3, folding and inlay hints in 2025.2) set the
  baseline. CLion, IntelliJ IDEA, Rider, GoLand, PyCharm and the rest all qualify.
- The Lyric toolchain. `lyrls` and `lyric` are found on `PATH`, or under the directory named in
  **Settings | Languages & Frameworks | Lyric** — point it at the folder the release archive
  unpacks to, and both are configured at once.

## Installing

Every release of **this repository** carries a `jetbrains-lyric-<version>.zip`. The plugin is
versioned on its own cadence — the toolchain it talks to lives in
[lyriclang/lyric](https://github.com/lyriclang/lyric) and releases separately. (Toolchain
releases v1.8.0 through v1.9.1 carried the zip beside the archives; from here on it is found
here.) In the IDE: **Settings | Plugins | ⚙ | Install Plugin from Disk…** and pick the zip. The
plugin is not on the Marketplace; the release asset is the distribution.

## Building

```bash
./gradlew buildPlugin
```

The zip lands in `build/distributions/`. The TextMate grammar under `textmate/` is a working
copy: its canonical home is `tooling/textmate` in
[lyriclang/lyric](https://github.com/lyriclang/lyric), where the toolchain's test suite pins it
against the lexer, and the `grammar-sync` CI job keeps the copy identical.

## The manual checklist

The plugin's structure is verified by the build (`verifyPluginStructure`); what an IDE does with
it is not — headless IDE tests cost more harness than this plugin has code. After a change, in a
2026.1+ IDE with the toolchain on PATH:

1. Install the zip, restart, open a folder with a `lyric.json`.
2. Open a `.lyr` file — highlighting appears (TextMate), and after a moment the squiggles of a
   deliberate error (server).
3. Break a `pub fn` another file imports — the error appears in the importing file too.
4. Hover a stdlib call — signature plus documentation.
5. `Ctrl+B` on an imported name — lands in the declaring file, on the name.
6. `Shift+F6` on a declared function — every use and the import clause follow; renaming
   `println` is refused with the reason.
7. Type `(` after a function name — the signature popup names the declared parameters.
8. Collapse a function via the gutter; the closing brace stays visible.
9. An unannotated `let` shows its inferred type inline.
10. Right-click a `.lyr` file with a `main` → *Run 'file.lyr'*: the program's output appears in
    the Run tool window, and the last line names the exit code.
11. Break the file and run again — the `file:line:column` of the diagnostic opens the editor at
    that position when clicked.
12. Add `alpha "two words"` under *Arguments* in the configuration — `main(args)` sees two
    arguments, not one and not none.
13. Point *Program* at a `.lyrbc` built by `lyric build` — it runs without compiling.
