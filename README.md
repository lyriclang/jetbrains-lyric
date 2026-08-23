# Lyric for JetBrains IDEs

The thin plugin: it recognises `.lyr`, renders the bundled TextMate grammar,
starts `lyrls`, runs a program through the toolchain's driver, and debugs it — either by launching
it through `lyrdbg` or by attaching to a host that is already running one.
Everything language-aware —
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

## Debugging

The same configuration under **Debug** starts `lyrdbg`, the toolchain's debug adapter, and the
IDE's own Debug Adapter Protocol client drives it: breakpoints in the gutter, step over, into and
out, the call stack with source lines, the variables panel with locals and globals under their
names, and hover evaluation. None of it is implemented here — it is the same adapter the VS Code
extension talks to, one client further.

The adapter compiles the program itself, in the **debug shape**: with the source map, with debug
info, with the optimizations off. What runs is the file in the editor, not an optimized build in
which half the frames no longer exist.

Three things the debugger does not do, stated rather than discovered:

- **Breakpoint conditions and log expressions are not offered**, because the adapter evaluates
  NAMES, not expressions: `v.x` and `player.pos.y` are answered, `v.x + 1` is not. A condition
  field would promise an evaluator that does not exist.
- **Exception breakpoints do not exist.** A Lyric panic ends the program rather than stopping it;
  the message and the backtrace land in the console and the exit code is 101.
- **The standard library is not steppable**, and the global initializer runs before the debugger
  attaches — both are properties of the adapter, described in the toolchain's debugging guide.

When a session misbehaves, the whole DAP conversation can be written to disk: set
`dap.message.trace.dir` in **Help | Find Action | Registry** to a folder, and the requests and
responses land there as files.

## Attaching to a running host

A **Lyric Attach** configuration debugs a program that is already running inside a host — a game, an
editor, anything embedding the VM. There the adapter is not a process this plugin starts: it lives
inside the host, beside the program it debugs, and serves the protocol over a socket the host
opened. The configuration is the host and the port; everything else the host already knows.

Nothing is compiled and nothing is started. A breakpoint set here binds against the program as it
runs and takes effect the next time execution reaches that line — which is the point, because the
bug worth stopping at is rarely the one at startup. Disconnecting gives the program back: the
breakpoints go and a parked thread is released, so the host runs on without a debugger holding it.

It needs a host built against the toolchain's `DapServer`, from **2.9.0** on. The configuration has
no Run button, because there is nothing here to run.

## Requirements

- A **commercial** JetBrains IDE, **2026.1 or newer** — the platform's LSP API does not exist in
  the Community editions, and the features this plugin relies on (rename arrived in 2026.1,
  signature help and workspace symbols in 2025.3, folding and inlay hints in 2025.2) set the
  baseline. CLion, IntelliJ IDEA, Rider, GoLand, PyCharm and the rest all qualify.
- The Lyric toolchain. `lyrls`, `lyric` and `lyrdbg` are found on `PATH`, or under the directory
  named in **Settings | Languages & Frameworks | Lyric** — point it at the folder the release
  archive unpacks to, and all three are configured at once. Debugging by launching needs a
  toolchain of **2.3.0 or newer**, the release `lyrdbg` first shipped in; attaching needs
  **2.9.0 or newer** on the host's side, where the adapter actually runs.

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

```bash
./gradlew verifyPlugin
```

The zip lands in `build/distributions/`. `verifyPlugin` runs the JetBrains plugin verifier against
both ends of the supported range — the 2026.1 baseline and the current release — and downloads
those IDEs the first time. It is what stands in for tests here: the platform's DAP client is
marked experimental, so the day it changes shape, that job is what says so.

The TextMate grammar under `textmate/` is a working
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
14. Set a breakpoint in the gutter of a `.lyr` line and press *Debug*: it hits, and the line is
    highlighted before it runs.
15. A breakpoint on a blank line or a declaration slides down to the next executable line.
16. Step over, into and out — one step is one source line; a step into a stdlib call runs through
    it.
17. The call stack names each frame's function and line; selecting a frame shows its locals.
18. Expand a struct, an enum payload and an array in the variables panel; module-level `let`s
    stand under *Globals*.
19. Hover a name in the editor while stopped — its value appears. `v.x` in the debug console
    answers; `v.x + 1` says it is not a known name.
20. Let the program panic — the session ends, the backtrace is in the console, exit code 101.
21. Check the breakpoint dialog: Lyric line breakpoints carry no condition field, and no Lyric
    exception category is offered.
22. Against a host serving `DapServer` on a port: a **Lyric Attach** configuration connects, and
    the configuration offers Debug but no Run.
23. A breakpoint set while the host runs hits the next time the program reaches that line — with
    no restart of anything.
24. Stop the session: the host keeps running, and its next frame is not parked by a breakpoint
    nobody is reading any more.
