package dev.jarvis.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import dev.jarvis.agent.ApprovalService;

/**
 * An MCP tool that changes something (creates an issue, sends an email): it runs only after the user
 * approves the exact call, arguments included.
 */
final class ApprovalToolCallback implements ToolCallback {

    private static final int ARGUMENTS_PREVIEW = 2000;

    private final ToolCallback delegate;
    private final String serverLabel;
    private final ApprovalService approvals;

    ApprovalToolCallback(ToolCallback delegate, String serverLabel, ApprovalService approvals) {
        this.delegate = delegate;
        this.serverLabel = serverLabel;
        this.approvals = approvals;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return "Questa azione richiede l'approvazione dell'utente e qui non è possibile chiederla.";
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String arguments = toolInput.length() <= ARGUMENTS_PREVIEW ? toolInput
                : toolInput.substring(0, ARGUMENTS_PREVIEW) + "…";
        String description = serverLabel + ": " + getToolDefinition().name() + "\n\n" + arguments;
        if (!approvals.requestApproval(toolContext, description)) {
            return "L'utente non ha approvato. Nessuna modifica fatta.";
        }
        return delegate.call(toolInput, toolContext);
    }
}
