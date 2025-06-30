package io.quarkus.qute.debug;

import io.quarkus.qute.debug.agent.RemoteStackFrame;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.Thread;

import java.util.List;

public interface Debugger {

    /**
     * Returns the remote debugger state.
     *
     * @return the remote debugger state.
     */
    DebuggerState getState(long threadId) ;

    void pause(long threadId) ;

    void resume(long threadId) ;


    org.eclipse.lsp4j.debug.Breakpoint[] setBreakpoints(SourceBreakpoint[] sourceBreakpoints, Source source);

    Breakpoint getBreakpoint(String templateId, int line) ;

    Thread[] getThreads() ;

    Thread getThread(long threadId) ;

    List<RemoteStackFrame> getStackFrames(long threadId);

    /**
     * Returns the variable scopes for the given stackframe ID <code>frameId</code>.
     *
     * @param frameId the stackframe ID
     *
     *
     * @return the variable scopes for the given stackframe ID <code>frameId</code>.
     * @
     */
    Scope[] getScopes(int frameId) ;

    /**
     * Retrieves all child variables for the given variable reference.
     *
     * @param variablesReference the Variable reference.
     * @return all child variables for the given variable reference.
     *
     * @
     */
    Variable[] getVariables(int variablesReference) ;

    SetVariableResponse setVariable(SetVariableArguments args);

    void terminate() ;

    void stepIn(long threadId) ;

    void stepOut(long threadId) ;

    void stepOver(long threadId) ;

    void next(long threadId) ;

    CompletionsResponse completions(CompletionsArguments args);

    void addDebuggerListener(DebuggerListener listener) ;

    void removeDebuggerListener(DebuggerListener listener) ;

}
