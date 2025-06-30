package io.quarkus.qute.debug.agent.variables;

import org.eclipse.lsp4j.debug.Variable;

import java.util.Collection;

public interface VariablesProvider {

    int getVariablesReference();

    void setVariablesReference(int variablesReference);

    Collection<Variable> getVariables();

}
