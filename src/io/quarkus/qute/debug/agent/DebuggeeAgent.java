package io.quarkus.qute.debug.agent;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import io.quarkus.qute.debug.agent.variables.VariablesHelper;
import io.quarkus.qute.debug.agent.variables.VariablesRegistry;
import org.eclipse.lsp4j.debug.*;

import io.quarkus.qute.Engine;
import io.quarkus.qute.debug.Breakpoint;
import io.quarkus.qute.debug.Debugger;
import io.quarkus.qute.debug.DebuggerListener;
import io.quarkus.qute.debug.DebuggerState;
import io.quarkus.qute.debug.StoppedEvent;
import io.quarkus.qute.debug.ThreadEvent;
import io.quarkus.qute.trace.ResolveEvent;
import io.quarkus.qute.trace.TemplateEvent;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;

public class DebuggeeAgent implements Debugger {

    private final DebuggerTraceListener debugListener;

    private final Map<String /* template id */, Map<Integer, Breakpoint>> breakpoints;

    private final Map<Long, RemoteThread> debuggees;

    private final Collection<DebuggerListener> listeners;

    private final VariablesRegistry variablesRegistry;

    private final SourceTemplateRegistry sourceTemplateRegistry;

    public DebuggeeAgent() {
        this.debugListener = new DebuggerTraceListener(this);
        this.breakpoints = new HashMap<>();
        this.debuggees = new HashMap<>();
        this.listeners = new ArrayList<>();
        this.variablesRegistry = new VariablesRegistry();
        this.sourceTemplateRegistry = new SourceTemplateRegistry();
    }

    public void track(Engine engine) {
        engine.addTraceListener(debugListener);
    }

    @Override
    public DebuggerState getState(long threadId) {
        RemoteThread thread = getRemoteThread(threadId);
        return thread != null ? thread.getState() : DebuggerState.UNKWOWN;
    }

    @Override
    public void pause(long threadId) {
        RemoteThread thread = getRemoteThread(threadId);
        if (thread != null) {
            thread.pause();
        }
    }

    @Override
    public void resume(long threadId) {
        RemoteThread thread = getRemoteThread(threadId);
        if (thread != null) {
            thread.resume();
        }
    }

    public void onStartTemplate(TemplateEvent event) {
        if (!isTrackEnabled()) {
            return;
        }
        RemoteThread debuggee = getOrCreateDebuggeeThread();
        debuggee.start();
    }

    public void onTemplateNode(ResolveEvent event) {
        if (!isTrackEnabled()) {
            return;
        }

        OutputEventArguments args = new OutputEventArguments();
        args.setOutput(event.getTemplateNode().toString());
        args.setCategory(OutputEventArgumentsCategory.CONSOLE);
        output(args);

        RemoteThread debuggee = getOrCreateDebuggeeThread();
        debuggee.onTemplateNode(event);
    }

    public void onEndTemplate(TemplateEvent event) {
        if (!isTrackEnabled()) {
            return;
        }
        RemoteThread debuggee = getOrCreateDebuggeeThread();
        debuggees.remove(debuggee.getId());
        debuggee.exit();
    }

    private boolean isTrackEnabled() {
        // Tracking template nodes is enabled only if
        // - client debugger has registered some debugger listeners
        // - client debugger has registered some breakpoints
        return !listeners.isEmpty() || !breakpoints.isEmpty();
    }

    private RemoteThread getOrCreateDebuggeeThread() {
        java.lang.Thread thread = java.lang.Thread.currentThread();
        long threadId = thread.threadId();
        RemoteThread debuggee = getRemoteThread(threadId);
        if (debuggee == null) {
            debuggee = new RemoteThread(thread, this);
            debuggees.put(threadId, debuggee);
        }
        return debuggee;
    }

    private RemoteThread getRemoteThread(long threadId) {
        return debuggees.get(threadId);
    }

    @Override
    public org.eclipse.lsp4j.debug.Breakpoint[] setBreakpoints(SourceBreakpoint[] sourceBreakpoints, Source source) {
        sourceTemplateRegistry.registerSource(source);
        String templateId = sourceTemplateRegistry.getTemplateId(source);
        Map<Integer, Breakpoint> templateBreakpoints = this.breakpoints.computeIfAbsent(templateId, k -> new HashMap<>());

        org.eclipse.lsp4j.debug.Breakpoint[] result = new org.eclipse.lsp4j.debug.Breakpoint[sourceBreakpoints.length];
        if (sourceBreakpoints.length == 0) {
            templateBreakpoints.clear();
        } else {
            for (int i = 0; i < sourceBreakpoints.length; i++) {
                SourceBreakpoint sourceBreakpoint = sourceBreakpoints[i];
                int line = sourceBreakpoint.getLine();
                String condition = sourceBreakpoint.getCondition();
                Breakpoint breakpoint = new Breakpoint(templateId, line, condition);
                templateBreakpoints.put(line, breakpoint);

                var bp = new org.eclipse.lsp4j.debug.Breakpoint();
                bp.setSource(source);
                bp.setLine(line);
                bp.setVerified(true);
                result[i] = bp;
            }
        }
        return result;
    }


    @Override
    public Thread getThread(long threadId) {
        return debuggees.get(threadId);
    }

    @Override
    public Thread[] getThreads() {
        return debuggees.values() //
                .toArray(RemoteThread.EMPTY_THREAD);
    }

    @Override
    public Breakpoint getBreakpoint(String templateId, int line) {
        Map<Integer, Breakpoint> templateBreakpoints = this.breakpoints.get(templateId);
        if (templateBreakpoints == null) {
            templateBreakpoints = this.breakpoints.get(templateId + ".qute");
        }
        return templateBreakpoints != null ? templateBreakpoints.get(line) : null;
    }

    @Override
    public void addDebuggerListener(DebuggerListener listener) {
        listeners.add(listener);
    }

    public void removeDebuggerListener(DebuggerListener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            // The remote client debugger is disconnected, unlock all debuggee threads.
            unlockAllDebuggeeThreads();
        }
    }

    void fireStoppedEvent(StoppedEvent event) {
        for (DebuggerListener listener : listeners) {
            try {
                listener.onStopped(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    void fireThreadEvent(ThreadEvent event) {
        for (DebuggerListener listener : listeners) {
            try {
                listener.onThreadChanged(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    void fireTerminateEvent() {
        for (DebuggerListener listener : listeners) {
            try {
                listener.onTerminate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    void output(OutputEventArguments args) {
        for (DebuggerListener listener : listeners) {
            try {
                listener.output(args);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void unlockAllDebuggeeThreads() {
        try {
            // Terminate all current debuggee Thread.
            for (RemoteThread thread : debuggees.values()) {
                thread.terminate();
            }
            debuggees.clear();
            // Remove all breakpoints
            this.breakpoints.clear();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void terminate() {
        try {
            // Terminate all current debugee Thread.
            unlockAllDebuggeeThreads();
        } finally {
            // Notify that debugger server is terminated.
            fireTerminateEvent();
        }
    }

    @Override
    public void stepIn(long threadId) {
        RemoteThread thread = getRemoteThread(threadId);
        if (thread != null) {
            thread.stepIn();
        }
    }

    @Override
    public void stepOut(long threadId) {
        RemoteThread thread = getRemoteThread(threadId);
        if (thread != null) {
            thread.stepOut();
        }
    }

    @Override
    public void stepOver(long threadId) {
        RemoteThread thread = getRemoteThread(threadId);
        if (thread != null) {
            thread.stepOver();
        }
    }

    @Override
    public void next(long threadId) {
        RemoteThread thread = getRemoteThread(threadId);
        if (thread != null) {
            thread.next();
        }
    }

    @Override
    public CompletionsResponse completions(CompletionsArguments args) {
        Collection<CompletionItem> targets = new ArrayList<>();
        Integer frameId = args.getFrameId();
        var stackFrame = findStackFrame(frameId);
        if (stackFrame != null) {
            for(var scope : stackFrame.getScopes()) {
                for (var variable : scope.getVariables()) {
                    CompletionItem item = new CompletionItem();
                    item.setLabel(variable.getName());
                    targets.add((item));
                }
            }
        }
        CompletionsResponse response = new CompletionsResponse();
        response.setTargets(targets.toArray(new CompletionItem[0]));
        return response;
    }

    @Override
    public List<RemoteStackFrame> getStackFrames(long threadId) {
        RemoteThread thread = getRemoteThread(threadId);
        if (thread != null) {
            return thread.getStackFrames();
        }
        return null;
    }

    @Override
    public Scope[] getScopes(int frameId) {
        for (RemoteThread thread : debuggees.values()) {
            RemoteStackFrame frame = thread.getStackFrame(frameId);
            if (frame != null) {
                return frame.getScopes() //
                        .toArray(new Scope[0]);
            }
        }
        return new Scope[0];
    }

    @Override
    public Variable[] getVariables(int variablesReference) {
        return variablesRegistry.getVariables(variablesReference);
    }

    @Override
    public SetVariableResponse setVariable(SetVariableArguments args) {
        SetVariableResponse response = new SetVariableResponse();
        return response;
    }

    public CompletableFuture<EvaluateResponse> evaluate(Integer frameId, String expression) {
        return doEvaluate(frameId, expression)
                .handle((result, error) -> {
                    if (error != null) {
                        ResponseError re = new ResponseError();
                        re.setCode(ResponseErrorCode.InvalidRequest);
                        re.setMessage(error.getMessage());
                        throw new ResponseErrorException(re);
                    }
                    return result;
                })
                .thenApply(result -> {
            EvaluateResponse response = new EvaluateResponse();
            if (result != null) {
                response.setResult(result.toString());
                if (VariablesHelper.shouldBeExpanded(result)) {
                    var variable = VariablesHelper.fillVariable("", result, null, getVariablesRegistry());
                    response.setVariablesReference(variable.getVariablesReference());
                }
            }
            return response;
        });
    }

    private CompletableFuture<Object> doEvaluate(Integer frameId, String expression) {
        RemoteStackFrame frame = findStackFrame(frameId);
        if (frame != null) {
            return frame.evaluate(expression)
                    .toCompletableFuture();
        }
        return CompletableFuture.completedFuture(null);
    }

    private RemoteStackFrame findStackFrame(Integer frameId) {
        if (frameId == null) {
            return null;
        }
        for (RemoteThread thread : debuggees.values()) {
            RemoteStackFrame frame = thread.getStackFrame(frameId);
            if (frame != null) {
                return frame;
            }
        }
        return null;
    }

    public VariablesRegistry getVariablesRegistry() {
        return variablesRegistry;
    }

    public SourceTemplateRegistry getSourceTemplateRegistry() {
        return sourceTemplateRegistry;
    }
}
