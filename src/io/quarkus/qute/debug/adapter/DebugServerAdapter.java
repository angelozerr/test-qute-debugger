package io.quarkus.qute.debug.adapter;

import io.quarkus.qute.debug.DebuggerListener;
import io.quarkus.qute.debug.StoppedEvent;
import io.quarkus.qute.debug.ThreadEvent;
import io.quarkus.qute.debug.agent.DebuggeeAgent;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.quarkus.qute.debug.agent.RemoteStackFrame.EMPTY_STACK_FRAMES;

/**
 * Adapter implementing the Debug Adapter Protocol (DAP) server interface.
 * It connects the Qute debugging agent ({@link DebuggeeAgent}) with a DAP client
 * such as VS Code or any LSP-compatible IDE, allowing communication via standard DAP messages.
 */
public class DebugServerAdapter implements IDebugProtocolServer {

    private final DebuggeeAgent agent;
    private IDebugProtocolClient client;

    private final Map<Integer, Thread> threads = new HashMap<>();

    public DebugServerAdapter(DebuggeeAgent agent) {
        this.agent = agent;
        agent.addDebuggerListener(new DebuggerListener() {

            @Override
            public void output(OutputEventArguments args) {
                if (client != null) {
                    client.output(args);
                }
            }

            @Override
            public void onThreadChanged(ThreadEvent event)  {
                handleThreadChanged(event);
            }

            @Override
            public void onStopped(StoppedEvent event)  {
                handleStopped(event);
            }

            @Override
            public void onTerminate()  {
                handleTerminate();
            }

        });
    }

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        return CompletableFuture.supplyAsync(() -> {
            Capabilities capabilities = new Capabilities();
            capabilities.setSupportsCompletionsRequest(Boolean.TRUE);
            capabilities.setSupportsConditionalBreakpoints(Boolean.TRUE);
            return capabilities;
        });
    }

    public void connect(IDebugProtocolClient client) {
        this.client = client;
        this.agent.setEnabled(true);
    }

    @Override
    public CompletableFuture<Void> attach(Map<String, Object> args) {
        return CompletableFuture.runAsync(() -> {
            client.initialized();
        });
    }

    @Override
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        return CompletableFuture.supplyAsync(() -> {
            SetBreakpointsResponse response = new SetBreakpointsResponse();
            Source source = args.getSource();
            SourceBreakpoint[] sourceBreakpoints = args.getBreakpoints();
            Breakpoint[] breakpoints = agent.setBreakpoints(sourceBreakpoints, source);
            response.setBreakpoints(breakpoints);
            return response;
        });
    }

    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        return CompletableFuture.supplyAsync(() -> {
            ThreadsResponse response = new ThreadsResponse();
            response.setThreads(agent.getThreads());
            return response;
        });
    }

    @Override
    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        return CompletableFuture.supplyAsync(() -> {
            StackTraceResponse response = new StackTraceResponse();
            int threadId = args.getThreadId();
            var stackFrames = agent.getStackFrames(threadId);
            response.setStackFrames(stackFrames
                            .stream()
                            .toList()
                            .toArray(EMPTY_STACK_FRAMES));
            response.setTotalFrames(stackFrames.size());
            return response;
        });
    }

    @Override
    public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
        return CompletableFuture.supplyAsync(() -> {
            ScopesResponse response = new ScopesResponse();
            int frameId = args.getFrameId();
            response.setScopes(agent.getScopes(frameId));
            return response;
        });
    }

    @Override
    public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
        return CompletableFuture.supplyAsync(() -> {
            VariablesResponse response = new VariablesResponse();
            int variablesReference = args.getVariablesReference();
            response.setVariables(agent.getVariables(variablesReference));
            return response;
        });
    }

    @Override
    public CompletableFuture<Void> terminate(TerminateArguments args) {
        return CompletableFuture.runAsync(agent::terminate);
    }

    @Override
    public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        return CompletableFuture.runAsync(() -> {
            try {
                agent.terminate();
            }
            finally {
                this.agent.setEnabled(false);
            }
        });
    }

    @Override
    public CompletableFuture<Void> stepIn(StepInArguments args) {
        return CompletableFuture.runAsync(() -> {
            agent.stepIn(args.getThreadId());
        });
    }

    @Override
    public CompletableFuture<Void> stepOut(StepOutArguments args) {
        return CompletableFuture.runAsync(() -> {
            agent.stepOut(args.getThreadId());
        });
    }

    @Override
    public CompletableFuture<Void> pause(PauseArguments args) {
        return CompletableFuture.runAsync(() -> {
            agent.pause(args.getThreadId());
        });
    }

    @Override
    public CompletableFuture<Void> next(NextArguments args) {
        return CompletableFuture.runAsync(() -> {
            agent.next(args.getThreadId());
        });
    }

    @Override
    public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
        return CompletableFuture.supplyAsync(() -> {
            ContinueResponse response = new ContinueResponse();
            int threadId = args.getThreadId();
            if (threadId != 0) {
                response.setAllThreadsContinued(Boolean.FALSE);
                agent.resume(threadId);
            } else {
                response.setAllThreadsContinued(Boolean.TRUE);
            }
            return response;
        });
    }

    @Override
    public CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
        return agent.evaluate(args.getFrameId(), args.getExpression());
    }

    @Override
    public CompletableFuture<CompletionsResponse> completions(CompletionsArguments args) {
        return CompletableFuture.supplyAsync(() -> {
            return agent.completions(args);
        });
    }

    private void handleStopped(StoppedEvent event) {
        int threadId = (int) event.getThreadId();
        String reason = getReason(event.getReason());
        sendStopEvent(threadId, reason);
    }

    private static String getReason(StoppedEvent.StoppedReason reason) {
        return switch (reason) {
            case BREAKPOINT -> StoppedEventArgumentsReason.BREAKPOINT;
            case PAUSE -> StoppedEventArgumentsReason.PAUSE;
            case EXCEPTION -> StoppedEventArgumentsReason.EXCEPTION;
            case STEP -> StoppedEventArgumentsReason.STEP;
            default -> null;
        };
    }

    public void handleThreadChanged(ThreadEvent event) {
        int threadId = (int) event.getThreadId();
        String reason = getReason(event.getThreadStatus());
        sendThreadEvent(threadId, reason);

    }

    private static String getReason(ThreadEvent.ThreadStatus threadStatus) {
        return switch (threadStatus) {
            case STARTED -> ThreadEventArgumentsReason.STARTED;
            case EXITED -> ThreadEventArgumentsReason.EXITED;
        };
    }

    private void sendStopEvent(int threadId, String reason) {
        if (client == null) {
            return;
        }
        StoppedEventArguments args = new StoppedEventArguments();
        args.setThreadId(threadId);
        args.setReason(reason);
        client.stopped(args);
    }

    private void sendThreadEvent(int threadId, String reason) {
        if (client == null) {
            return;
        }
        ThreadEventArguments args = new ThreadEventArguments();
        args.setThreadId(threadId);
        args.setReason(reason);
        client.thread(args);
    }

    public void handleTerminate() {
        sendExitEvent();
    }


    private void sendExitEvent() {
        if (client == null) {
            return;
        }
        ExitedEventArguments args = new ExitedEventArguments();
        client.exited(args);
        client.terminated(new TerminatedEventArguments());
    }
}
