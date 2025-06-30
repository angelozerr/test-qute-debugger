package io.quarkus.qute.debug.adapter;

import io.quarkus.qute.Engine;
import io.quarkus.qute.EngineBuilder.EngineListener;
import io.quarkus.qute.debug.Breakpoint;
import io.quarkus.qute.debug.agent.DebuggeeAgent;
import io.quarkus.qute.trace.ResolveEvent;
import io.quarkus.qute.trace.TemplateEvent;
import io.quarkus.qute.trace.TraceListener;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RegisterDebugServerAdapter implements EngineListener {

    private boolean initialized;

    private DebuggeeAgent agent;
    private ServerSocket serverSocket;
    private Future<Void> future;
    private DebugServerAdapter server;
    private boolean connectedToSocket;

    @Override
    public void engineBuilt(Engine engine) {
        if (!engine.isDebuggable()) {
            return;
        }
        if (initialized) {
            agent.track(engine);
            return;
        }
        if  (agent != null) {
            return;
        }

        Integer port = getPort();
        if (port == null) {
            return;
        }
        boolean suspend = isSuspend();

        agent = new DebuggeeAgent();

        agent.track(engine);
        server = new DebugServerAdapter(agent);

        engine.addTraceListener(new TraceListener() {
            @Override
            public void onBeforeResolve(ResolveEvent event) {

            }

            @Override
            public void onAfterResolve(ResolveEvent event) {

            }

            @Override
            public void onStartTemplate(TemplateEvent event) {
                initializeAgent(port, suspend);
            }

            @Override
            public void onEndTemplate(TemplateEvent event) {

            }
        });
    }

    private static Integer getPort() {
        String port = System.getenv("qute.debug.port");
        if (port == null || port.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(port);
        }
        catch(Exception e) {
            return null;
        }
    }

    private boolean isSuspend() {
        String suspend = System.getenv("qute.debug.suspend");
        if (suspend == null || suspend.isBlank()) {
            return false;
        }
        try {
            return Boolean.parseBoolean(suspend);
        }
        catch(Exception e) {
            return false;
        }
    }

    private void initializeAgent(int port, boolean suspend) {
        if (initialized || connectedToSocket) {
            return;
        }
        initializeAgent2(port, suspend);
    }

    private synchronized  void initializeAgent2(int port, boolean suspend) {
        if (initialized) {
            return;
        }
        if (suspend) {
            doInitializeAgent(port);
            return;
        }
        if (!connectedToSocket) {
            CompletableFuture.runAsync(() -> doInitializeAgent(port));
        }
    }

    private void doInitializeAgent(int port) {
        try (var serverSocket = new ServerSocket(port)) {
            connectedToSocket = true;
            System.out.println("DebugServerAdapter listening on port " + port);
            var client = serverSocket.accept();

            Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(
                    server,
                    client.getInputStream(),
                    client.getOutputStream(),
                    createDaemonExecutor(),
                    null
            );

            var clientProxy = launcher.getRemoteProxy();
            server.connect(clientProxy);

            Thread listenerThread = new Thread(() -> {
                Future<?> listening = launcher.startListening();
            }, "dap-listener");
            listenerThread.setDaemon(false);
            listenerThread.start();

            initialized = true;

            if (false)
            Thread.sleep(3000);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (future != null) {
                    future.cancel(true);
                }
                System.out.println("Shutdown hook: closing server socket.");
                try {

                    if (serverSocket != null && !serverSocket.isClosed()) {
                        serverSocket.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));
            //       });

        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //this.agent = agent;
    }

    private ExecutorService createDaemonExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true); // 💡 très important
            t.setName("dap-daemon-thread");
            return t;
        });
    }
}
