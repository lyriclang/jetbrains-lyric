package dev.lyric.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpointType

/**
 * Where a breakpoint may stand: in a `.lyr` file, on any line — the same rule the language server
 * uses to decide a file is ours, and the same one the adapter applies when it binds.
 *
 * Whether a line actually carries code is the debugger's answer, not the editor's: `lyrdbg` slides
 * a breakpoint down to the next executable line and reports back whether it bound. Guessing here
 * would mean a second, worse line table beside the compiler's.
 *
 * No [com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider] is offered, which is what keeps
 * the condition and log-expression fields out of the breakpoint dialog. The debugger evaluates
 * NAMES, not expressions; a condition field would promise an evaluator that does not exist.
 */
class LyricLineBreakpointType :
    XLineBreakpointType<XBreakpointProperties<*>>("lyric-line", "Lyric Line Breakpoints") {

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        file.extension == "lyr"

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? =
        null
}

/**
 * Declared because the platform's DAP integration requires a type for exception breakpoints, and
 * deliberately impossible to create: a Lyric panic ends the program, it does not stop it. The
 * adapter reports no stop with reason `exception`, so a breakpoint of this kind could never hit.
 *
 * Hiding it from the dialog is the honest form. Registering nothing is not an option — the
 * description the descriptor hands over takes two type classes, both non-null.
 */
class LyricExceptionBreakpointType : XBreakpointType<
    XBreakpoint<XBreakpointProperties<*>>,
    XBreakpointProperties<*>,
    >("lyric-exception", "Lyric Exceptions") {

    override fun getDisplayText(breakpoint: XBreakpoint<XBreakpointProperties<*>>): String =
        "Lyric exception"

    override fun isAddBreakpointButtonVisible(): Boolean = false

    override fun shouldShowInBreakpointsDialog(project: Project): Boolean = false
}
