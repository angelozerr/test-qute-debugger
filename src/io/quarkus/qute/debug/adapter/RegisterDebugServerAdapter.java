package io.quarkus.qute.debug.adapter;

import io.quarkus.qute.Engine;
import io.quarkus.qute.EngineBuilder.EngineListener;
import io.quarkus.qute.debug.agent.DebuggeeAgent;
import io.quarkus.qute.trace.TemplateEvent;
import io.quarkus.qute.trace.TraceListenerAdapter;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

public class RegisterDebugServerAdapter implements EngineListener {

    // Port to listen for debug connections, retrieved from environment
    private Integer port;

    // Engines being tracked by the debug agent
    private final Set<Engine> trackedEngines = new HashSet<>();

    // Engines that are debuggable but not yet initialized
    private final Set<Engine> notInitializedEngines = new HashSet<>();

    private volatile boolean initialized;
    private volatile DebuggeeAgent agent;
    private volatile ServerSocket serverSocket;
    private Future<Void> launcherFuture;
    private DebugServerAdapter server;
    private volatile boolean connectedToSocket;

    private final ExecutorService executor = createDaemonExecutor();

    // Listener used to lazily initialize the debug agent when the first template is rendered
    private final TraceListenerAdapter initializeAgentListener = new TraceListenerAdapter() {
        @Override
        public void onStartTemplate(TemplateEvent event) {
            // Trigger initialization when the first template starts rendering
            initializeAgent(getPort(), isSuspend());
        }
    };

    @Override
    public void engineBuilt(Engine engine) {
        if (!engine.isDebuggable()) {
            return;
        }

        // Track the debuggable engine
        trackedEngines.add(engine);

        // If already initialized, immediately attach the engine
        if (initialized) {
            agent.track(engine);
            return;
        }

        Integer port = getPort();
        if (port == null) {
            return;
        }

        // Create the debug agent if needed
        agent = createAgentIfNeeded();

        // If not yet initialized, attach a listener to lazily initialize the agent later
        if (!initialized) {
            notInitializedEngines.add(engine);
            engine.addTraceListener(initializeAgentListener);
        }

        // Track the engine in the agent
        agent.track(engine);
    }

    private DebuggeeAgent createAgentIfNeeded() {
        if (agent == null) {
            synchronized (this) {
                if (agent == null) {
                    agent = new DebuggeeAgent();
                    server = new DebugServerAdapter(agent);
                    trackedEngines.forEach(agent::track);
                }
            }
        }
        return agent;
    }

    private Integer getPort() {
        if (port != null) {
            return port;
        }
        port = doGetPort();
        return port;
    }

    private static Integer doGetPort() {
        // Read the debug port from the environment variable
        String portStr = System.getenv("qute.debug.port");
        if (portStr == null || portStr.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(portStr);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSuspend() {
        // Read the suspend flag from the environment variable
        String suspend = System.getenv("qute.debug.suspend");
        if (suspend == null || suspend.isBlank()) {
            return false;
        }
        try {
            return Boolean.parseBoolean(suspend);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Initializes the debug agent. In suspend mode, this call blocks
     * until a DAP client connects. Otherwise, initialization is done
     * asynchronously in a background thread.
     */
    private synchronized void initializeAgent(int port, boolean suspend) {
        if (initialized || connectedToSocket) {
            return;
        }
        if (suspend) {
            // In suspend mode: block until a DAP client connects
            initializeAgentBlocking(port, true);
        } else {
            // In non-suspend mode: run in background without blocking main thread
            executor.execute(() -> initializeAgentBlocking(port, false));
        }
    }

    /**
     * Performs the blocking initialization of the debug agent, including
     * waiting for client connections depending on the suspend flag.
     *
     * @param port the port to listen on
     * @param suspend whether to block on accept or run accept loop in background
     */
    private synchronized void initializeAgentBlocking(int port, boolean suspend) {
        if (connectedToSocket) {
            return;
        }

        try {
            serverSocket = new ServerSocket(port);
            connectedToSocket = true;
            log("DebugServerAdapter listening on port " + serverSocket.getLocalPort());

            if (suspend) {
                // Suspend mode: block here until a DAP client connects
                log("Waiting for DAP client to connect (suspend mode)...");
                var client = serverSocket.accept();
                log("DAP client connected (suspend mode)!");
                setupLauncher(client, true);
            } else {
                // Non-suspend mode: accept clients asynchronously in a daemon thread loop
                executor.execute(() -> {
                    while (!serverSocket.isClosed()) {
                        try {
                            log("Waiting for a new DAP client...");
                            var client = serverSocket.accept();
                            log("DAP client connected!");
                            setupLauncher(client, false);
                            trackedEngines.forEach(engine -> agent.track(engine));
                            agent.setEnabled(true);
                        } catch (IOException e) {
                            if (!serverSocket.isClosed()) {
                                e.printStackTrace();
                            }
                        }
                    }
                });
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (launcherFuture != null) {
                    launcherFuture.cancel(true);
                }
                log("Shutdown hook: closing server socket.");
                try {
                    if (serverSocket != null && !serverSocket.isClosed()) {
                        serverSocket.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // Shutdown executor service gracefully
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        log("Executor did not terminate in the specified time.");
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }));

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates and starts the DAP launcher for the connected client socket.
     * Cancels any previous launcher.
     *
     * @param client the connected socket
     * @param suspend true if suspend mode (used to wait after connection)
     * @throws IOException if socket streams cannot be accessed
     */
    private void setupLauncher(java.net.Socket client, boolean suspend) throws IOException {
        // Cancel previous launcher if needed
        if (launcherFuture != null && !launcherFuture.isDone()) {
            launcherFuture.cancel(true);
        }

        Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(
                server,
                client.getInputStream(),
                client.getOutputStream(),
                executor,
                null
        );

        var clientProxy = launcher.getRemoteProxy();
        server.connect(clientProxy);
        launcherFuture = launcher.startListening();

        if (!notInitializedEngines.isEmpty()) {
            notInitializedEngines.forEach(engine -> engine.removeTraceListener(initializeAgentListener));
            notInitializedEngines.clear();
        }

        if (suspend) {
            // Sleep a bit to allow the client to register breakpoints
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ExecutorService createDaemonExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("dap-daemon-thread");
            return t;
        });
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
