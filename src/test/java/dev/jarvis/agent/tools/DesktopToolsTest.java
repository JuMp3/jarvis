package dev.jarvis.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import dev.jarvis.agent.AgentService;
import dev.jarvis.agent.ApprovalChannel;
import dev.jarvis.agent.ApprovalRequest;
import dev.jarvis.agent.ApprovalService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.config.JarvisProperties.DesktopApp;

/** Nothing is ever started here: every approval request goes unanswered and is denied. */
class DesktopToolsTest {

    @TempDir
    Path tmp;

    private final List<ApprovalRequest> asked = new CopyOnWriteArrayList<>();
    private final ToolContext context = new ToolContext(Map.of(
            AgentService.SESSION_ID_KEY, "s1", AgentService.CONVERSATION_ID_KEY, "conv"));

    private DesktopTools tools() {
        ApprovalService approvals = new ApprovalService(new ApprovalChannel() {
            @Override
            public void send(ApprovalRequest request) {
                asked.add(request);
            }

            @Override
            public void closed(ApprovalRequest request) {
            }
        }, Duration.ofMillis(50), Duration.ofMillis(50));
        JarvisProperties properties = new JarvisProperties(null, null, null, null, null, null,
                new JarvisProperties.Desktop(Map.of(
                        "ide", new DesktopApp("Un IDE", List.of("ide.exe", "{target}")),
                        "mail", new DesktopApp("La posta", List.of("mail.exe")))), null, null, null);
        return new DesktopTools(approvals, properties);
    }

    @Test
    void opensOnlyWebLinksAndAlwaysAsksFirst() {
        DesktopTools tools = tools();

        assertThat(tools.openUrl("javascript:alert(1)", context)).isEqualTo("Apro solo link http o https.");
        assertThat(tools.openUrl("file:///C:/Windows/System32/calc.exe", context)).isEqualTo("Apro solo link http o https.");
        assertThat(asked).isEmpty();

        assertThat(tools.openUrl("https://spring.io", context)).contains("non ha confermato");
        assertThat(asked).singleElement().extracting(ApprovalRequest::description).asString().contains("https://spring.io");
    }

    @Test
    void startsOnlyCatalogAppsWithAnExistingTarget() {
        DesktopTools tools = tools();

        assertThat(tools.launchApp("powershell", null, context)).contains("Applicazione sconosciuta");
        assertThat(tools.launchApp("ide", null, context)).contains("parametro target");
        assertThat(tools.launchApp("ide", tmp.resolve("missing").toString(), context)).contains("non esiste");
        assertThat(asked).isEmpty();

        assertThat(tools.launchApp("ide", tmp.toString(), context)).contains("non ha confermato");
        assertThat(asked).singleElement().extracting(ApprovalRequest::description).asString()
                .contains("ide.exe " + tmp.toAbsolutePath().normalize());
        assertThat(tools.listApps()).contains("ide: Un IDE (con file o cartella)", "mail: La posta");
    }

    @Test
    void opensOnlyExistingFolders() {
        assertThat(tools().openFolder(tmp.resolve("nope").toString(), context)).contains("non esiste");
    }
}
