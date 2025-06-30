package io.quarkus.qute.debug;

import io.quarkus.qute.Engine;
import io.quarkus.qute.TemplateNode;
import io.quarkus.qute.debug.agent.RemoteStackFrame;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;

/**
 * Information about a Breakpoint created in setBreakpoints.
 */
public class Breakpoint {

    private static final Engine conditionEngine;

    static {
        conditionEngine = Engine.builder().addDefaults().debuggable(false).build();
    }

    private final String templateId;

    private final int line;
    private final String condition;
    private TemplateNode ifNode;

    public Breakpoint(String templateId, int line, String condition) {
        this.templateId = templateId;
        this.line = line;
        this.condition = condition;
    }

    /**
     * Returns the source template id where the breakpoint is located.
     *
     * @return the source template id where the breakpoint is located.
     */
    public String getTemplateId() {
        return templateId;
    }

    /**
     * Return the start line of the actual range covered by the breakpoint.
     *
     * @return the start line of the actual range covered by the breakpoint.
     */
    public int getLine() {
        return line;
    }

    public String getCondition() {
        return condition;
    }

    public boolean checkCondition(RemoteStackFrame frame) {
        String condition = getCondition();
        if (condition == null || condition.isBlank()) {
            return true;
        }
        if (ifNode == null) {
            ifNode = conditionEngine.parse("{#if " + condition + "}true{/if}")
                    .findNodes(o -> true).iterator().next();
        }
        return frame.evaluateCondition(ifNode)
                .toCompletableFuture()
                .getNow(false);
    }

}
