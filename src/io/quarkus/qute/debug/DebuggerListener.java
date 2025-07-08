package io.quarkus.qute.debug;

import org.eclipse.lsp4j.debug.OutputEventArguments;

import java.rmi.Remote;

public interface DebuggerListener extends Remote {

    void output(OutputEventArguments args);

    void onThreadChanged(ThreadEvent event) ;

    void onStopped(StoppedEvent event) ;

    void onTerminate() ;

}
