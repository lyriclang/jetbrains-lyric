# Lyric for JetBrains IDEs

The thin plugin for [Lyric](https://github.com/lyriclang/lyric) 5: it recognises `.lyr`,
renders the bundled TextMate grammar, starts the toolchain's language server `lyric lsp`, runs a
package, a script or the package's tests, and debugs them through `lyric dap`. From 2.0.0 it
speaks to Lyric 5's one binary; Lyric 4's `lyrls` and `lyrdbg` belong to
[Lyric-Script](https://github.com/lyriclang/lyric-script), and 1.4.0 is the release for them.

Everything language-aware — diagnostics across the package, quick fixes, formatting, completion,
the jump, references, rename, semantic highlighting, signature help, folding, inlay hints, the
lenses above a test — is the IDE's own LSP integration consuming the same server every other
editor talks to. There is deliberately **no PSI implementation**: that would be a second frontend
in Kotlin with a permanent lag behind the grammar.

## Running

A **Lyric** run configuration names a **package's directory** — the one its `lyric.toml` stands
in — or a `.lyr` file of its own, the arguments the program receives and the directory it runs
in; with a **test** named, it runs the package's tests whose names hold it instead. The editor's
context menu on a `.lyr` file offers one for the file's package directly.

What runs is the toolchain: `lyric run -C <package>`, `lyric run <file>`, or `lyric test -f
<test>` — the command a terminal would use. A finding in the console carries its
`file:line:column` as a link into the editor.

**Test lenses**: the server puts **Run test** and **Debug test** above every `@Test` and
doc-test; a click makes a configuration for that test and runs or debugs it.

## Debugging

The same configuration under **Debug** starts `lyric dap`, and the IDE's Debug Adapter Protocol
client drives it. The adapter builds what the configuration names in the debug profile and hands
it to **lldb-dap**: breakpoints in the gutter, stepping, the call stack by the functions' Lyric
names at their Lyric lines, the variables by their Lyric names — a string as its text, an array
by its elements, an optional and an enum by what they hold. A **panic** stops the program where
it happens.

**Lyric Attach** attaches to a running program by its process id — lldb's attach, through the same
adapter. It has no Run button, because there is nothing to run.

The debugger needs **lldb-dap** — LLVM's, or Xcode's on macOS — on the `PATH` (`lldb-dap`,
`lldb-dap-<version>`) or where `LYRIC_LLDB_DAP` names it. On Windows it is measured, not
promised. Breakpoint conditions are not offered: lldb reads a condition over the program's C
names, not its Lyric ones.

When a session misbehaves, the whole DAP conversation can be written to disk: set
`dap.message.trace.dir` in **Help | Find Action | Registry** to a folder.

## Requirements

- A **commercial** JetBrains IDE, **2026.1 or newer** — the platform's LSP API does not exist in
  the Community editions, and its code lenses, rename, signature help, folding and inlay hints
  set the baseline. CLion, IntelliJ IDEA, Rider, GoLand, PyCharm and the rest all qualify.
- The Lyric 5 toolchain: `lyric` on the `PATH`, or under the directory named in **Settings |
  Languages & Frameworks | Lyric**. Until 5.0 is released, the binary of the dev channel
  ([the `dev` release](https://github.com/lyriclang/lyric/releases/tag/dev)) is `lyric5`; the
  plugin takes it from that directory where no `lyric` stands there.

## Installing

Every release of **this repository** carries a `jetbrains-lyric-<version>.zip`. The plugin is
versioned on its own cadence — the toolchain it talks to lives in
[lyriclang/lyric](https://github.com/lyriclang/lyric) and releases separately. In the IDE:
**Settings | Plugins | ⚙ | Install Plugin from Disk…** and pick the zip. The plugin is not on the
Marketplace; the release asset is the distribution.

## Building

```bash
./gradlew buildPlugin
```

```bash
./gradlew verifyPlugin
```

The zip lands in `build/distributions/`. `verifyPlugin` runs the JetBrains plugin verifier against
both ends of the supported range — the 2026.1 baseline and the current release. It is what stands
in for tests here: the platform's DAP client is marked experimental, so the day it changes shape,
that job is what says so.

The TextMate grammar under `textmate/syntaxes` is the file
[tree-sitter-lyric](https://github.com/lyriclang/tree-sitter-lyric) makes from its grammar, and
the `grammar-sync` CI job keeps it identical.

## The manual checklist

The plugin's structure is verified by the build (`verifyPluginStructure`); what an IDE does with
it is not — headless IDE tests cost more harness than this plugin has code. After a change, in a
2026.1+ IDE with the toolchain on PATH (or named in the settings) and lldb-dap installed:

1. Install the zip, restart, open a folder with a `lyric.toml`.
2. Open a `.lyr` file — highlighting appears (TextMate), and after a moment the squiggles of a
   deliberate error (server).
3. Break a `pub fn` another module imports — the error appears in the importing file too.
4. A lint's finding offers its quick fix (`Alt+Enter`); taking it changes the line.
5. **Code | Reformat File** formats as `lyric fmt` does.
6. Hover a std call — signature plus documentation. `Ctrl+B` on an imported name lands in the
   declaring file, on the name.
7. `Shift+F6` on a declared function — every use and the import clause follow.
8. An unannotated `let` shows its inferred type inline.
9. Above a `@Test`, **Run test** runs it: its line in the Run tool window, the exit code last.
10. Right-click a `.lyr` file → *Run*: the package's program runs; the configuration names the
    package's directory.
11. Break the file and run again — the `file:line:column` of the finding opens the editor there.
12. Add `alpha "two words"` under *Arguments* — the program sees two arguments.
13. Set a breakpoint in the gutter and press *Debug*: it hits at that line, and the frame is named
    `app.main.main`.
14. Step over, into and out; the call stack names each frame by its Lyric name.
15. The variables panel shows the locals by their Lyric names; a string shows its text, a struct
    its fields.
16. **Debug test** above a `@Test` stops at a breakpoint inside that test.
17. Let the program panic under the debugger — it stops where it panics.
18. Check the breakpoint dialog: Lyric line breakpoints carry no condition field.
19. A **Lyric Attach** configuration with a running program's id attaches, and offers Debug but
    no Run.
