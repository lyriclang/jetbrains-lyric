package dev.lyric.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpointType

/**
 * Where a breakpoint may stand: in a `.lyr` file, on any line — the same rule the language server
 * uses to decide a file is ours.
 *
 * Whether a line actually carries code is the debugger's answer, not the editor's: lldb binds a
 * breakpoint by the line table the compiler wrote and reports back whether it bound. Guessing here
 * would mean a second, worse line table beside the compiler's.
 *
 * No [com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider] is offered, which keeps the
 * condition field out of the breakpoint dialog: lldb would read a condition over the program's C
 * names, not its Lyric ones, and a field that takes Lyric's would promise what it does not do.
 */
class LyricLineBreakpointType :
    XLineBreakpointType<XBreakpointProperties<*>>("lyric-line", "Lyric Line Breakpoints") {

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        file.extension == "lyr"

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? =
        null
}

/**
 * Declared because the platform's DAP integration requires a type for exception breakpoints. A
 * panic is the adapter's one filter, `lyric-panic`, on unless the editor turns it off: the program
 * stops where it panics with nothing set here. So the type offers nothing to create — a breakpoint
 * of it could only repeat what the filter already does.
 */
class LyricExceptionBreakpointType : XBreakpointType<
    XBreakpoint<XBreakpointProperties<*>>,
    XBreakpointProperties<*>,
    >("lyric-exception", "Lyric Panics") {

    override fun getDisplayText(breakpoint: XBreakpoint<XBreakpointProperties<*>>): String =
        "Lyric panic"

    override fun isAddBreakpointButtonVisible(): Boolean = false

    override fun shouldShowInBreakpointsDialog(project: Project): Boolean = false
}
