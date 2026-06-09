package io.quarkus.gradle.tasks;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

import io.quarkiverse.agentclientprotocol.sdk.client.AcpClient;
import io.quarkiverse.agentclientprotocol.sdk.client.AcpSyncClient;
import io.quarkiverse.agentclientprotocol.sdk.client.transport.AgentParameters;
import io.quarkiverse.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.ContentChunk;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.InitializeResponse;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.NewSessionRequest;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.NewSessionResponse;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.PermissionOptionKind;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.PromptRequest;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.PromptResponse;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.RequestPermissionResponse;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.SessionNotification;
import io.quarkiverse.agentclientprotocol.sdk.spec.schema.v1.TextContent;

// TODO: extract shared ACP invocation logic (agent detection, backup, prompt building, session loop)
// into a MigrateProject class in quarkus-devtools-common, mirroring the UpdateProject pattern,
// once acp-java-core has a stable release.
@DisableCachingByDefault(because = "Not cacheable")
public abstract class QuarkusMigrate extends DefaultTask {

    private static final class AgentDescriptor {
        final String binary;
        final String[] defaultArgs;

        AgentDescriptor(String binary, String[] defaultArgs) {
            this.binary = binary;
            this.defaultArgs = defaultArgs;
        }
    }

    private static final String MIGRATION_SKILL_URL = "https://raw.githubusercontent.com/quarkusio/skills/main/skills/migrate-spring-to-quarkus/SKILL.md";
    private static final String COMPLETION_INSTRUCTION = " When you have fully completed all migration steps, end your final message with the exact line: \"Migration complete. Press Enter to finish.\"";
    private static final String STRATEGY_SPRING_COMPAT = "spring-compat";
    private static final String STRATEGY_NATIVE = "native";

    private static final List<AgentDescriptor> KNOWN_AGENTS = List.of(
            new AgentDescriptor("claude-agent-acp", new String[] {}),
            new AgentDescriptor("opencode", new String[] { "acp" }),
            new AgentDescriptor("gemini", new String[] { "--acp" }),
            new AgentDescriptor("pi-acp", new String[] {}));

    private String agent;
    private String agentArgs;
    private String model;
    private String strategy = STRATEGY_SPRING_COMPAT;
    private String prompt;
    private String permissionMode = "allow_always";
    private boolean noBackup = false;
    private String wks;
    private int requestTimeout = 30;
    private int promptTimeout = 0;
    private boolean interactive = false;

    public QuarkusMigrate() {
        setDescription("Migrate a Spring Boot project to Quarkus using an ACP-compatible AI agent.");
        setGroup("quarkus");
    }

    @Input
    @Optional
    public String getAgent() {
        return agent;
    }

    @Option(description = "ACP-compatible agent binary (e.g. claude-agent-acp, opencode, gemini). Auto-detected from PATH if not specified.", option = "agent")
    public void setAgent(String agent) {
        this.agent = agent;
    }

    @Input
    @Optional
    public String getAgentArgs() {
        return agentArgs;
    }

    @Option(description = "Space-separated arguments to pass to the agent binary.", option = "agentArgs")
    public void setAgentArgs(String agentArgs) {
        this.agentArgs = agentArgs;
    }

    @Input
    @Optional
    public String getModel() {
        return model;
    }

    @Option(description = "Model to use (e.g. claude-opus-4-6).", option = "model")
    public void setModel(String model) {
        this.model = model;
    }

    @Input
    @Optional
    public String getStrategy() {
        return strategy;
    }

    @Option(description = "Migration strategy: spring-compat (use Quarkus Spring compatibility extensions, minimal code changes) or native (replace Spring annotations with JAX-RS/CDI). Skips the interactive strategy selection step.", option = "strategy")
    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    @Input
    @Optional
    public String getPrompt() {
        return prompt;
    }

    @Option(description = "Override the built-in Spring Boot → Quarkus migration prompt.", option = "prompt")
    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    @Input
    public String getPermissionMode() {
        return permissionMode;
    }

    @Option(description = "How to respond to agent permission requests: allow_always, allow_once, reject_once, reject_always.", option = "permissionMode")
    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    @Input
    public boolean isNoBackup() {
        return noBackup;
    }

    @Option(description = "Disable workspace backup before running the migration.", option = "noBackup")
    public void setNoBackup(boolean noBackup) {
        this.noBackup = noBackup;
    }

    @Input
    @Optional
    public String getWks() {
        return wks;
    }

    @Option(description = "Absolute path to the project to migrate. Defaults to the current project directory.", option = "wks")
    public void setWks(String wks) {
        this.wks = wks;
    }

    @Input
    public int getRequestTimeout() {
        return requestTimeout;
    }

    @Option(description = "Timeout in seconds for individual ACP protocol requests.", option = "requestTimeout")
    public void setRequestTimeout(int requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    @Input
    public int getPromptTimeout() {
        return promptTimeout;
    }

    @Option(description = "Timeout in seconds for the migration prompt (0 = no timeout).", option = "promptTimeout")
    public void setPromptTimeout(int promptTimeout) {
        this.promptTimeout = promptTimeout;
    }

    @Input
    public boolean isInteractive() {
        return interactive;
    }

    @Option(description = "Enable interactive mode to respond to agent questions during migration.", option = "interactive")
    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    @TaskAction
    public void migrate() {
        if (!interactive) {
            getLogger().lifecycle("Tip: run with --interactive to respond to agent questions during migration.");
        } else if (!STRATEGY_SPRING_COMPAT.equals(strategy)) {
            getLogger().warn("--strategy is ignored in interactive mode; the agent will select the strategy interactively.");
        }
        getLogger().warn(getName() + " is experimental, its options and output might change in future versions");

        final String workspacePath = wks != null ? wks : getProject().getProjectDir().getAbsolutePath();
        final AgentDescriptor descriptor = resolveAgent();
        final String migrationPrompt = prompt != null ? prompt : buildMigrationPrompt(workspacePath, strategy, interactive);

        getLogger().lifecycle("Workspace : " + workspacePath);
        getLogger().lifecycle("Agent     : " + descriptor.binary);

        if (!noBackup) {
            backupWorkspace(workspacePath);
        }

        if ("claude-agent-acp".equals(descriptor.binary) && "allow_always".equals(permissionMode)) {
            writeClaudeSettings(workspacePath);
        }

        final String[] resolvedArgs = agentArgs != null
                ? agentArgs.split("\\s+")
                : descriptor.defaultArgs;

        AgentParameters agentParams = AgentParameters.builder(descriptor.binary)
                .args(resolvedArgs)
                .build();

        StdioAcpClientTransport transport = new StdioAcpClientTransport(agentParams);

        try (AcpSyncClient client = AcpClient.sync(transport)
                .sessionUpdateConsumer(this::handleUpdate)
                .permissionRequestHandler(req -> {
                    PermissionOptionKind desired = toPermissionKind(permissionMode);
                    String optionId = req.options().stream()
                            .filter(o -> o.kind() == desired)
                            .map(o -> o.optionId())
                            .findFirst()
                            .orElse(req.options().get(0).optionId());
                    return new RequestPermissionResponse(optionId);
                })
                .requestTimeout(Duration.ofSeconds(requestTimeout))
                .promptTimeout(promptTimeout > 0 ? Duration.ofSeconds(promptTimeout) : null)
                .build()) {

            InitializeResponse init = client.initialize();
            String agentName = init.agentInfo() != null ? init.agentInfo().name() : descriptor.binary;
            String agentVersion = init.agentInfo() != null ? init.agentInfo().version() : "";
            getLogger().lifecycle("Connected to: " + agentName + " " + agentVersion);

            NewSessionResponse session = client.newSession(new NewSessionRequest(workspacePath, List.of()));
            getLogger().lifecycle("Session: " + session.sessionId());

            String currentPrompt = migrationPrompt;
            BufferedReader stdin = interactive ? new BufferedReader(new InputStreamReader(System.in)) : null;
            while (true) {
                Thread spinner = startSpinner();
                PromptResponse response = client.prompt(
                        new PromptRequest(List.of(new TextContent(currentPrompt)), session.sessionId()));
                stopSpinner(spinner);
                String stopReason = response.stopReason().getValue();
                if (!interactive || !"end_turn".equalsIgnoreCase(stopReason)) {
                    getLogger().lifecycle("Done. Stop reason: " + stopReason);
                    getLogger().lifecycle("The agent may have used an older Quarkus version based on its training data. Run 'gradle quarkusUpdate' to upgrade to the latest available release.");
                    break;
                }
                System.out.println();
                System.out.print("> ");
                System.out.flush();
                String userInput = stdin.readLine();
                if (userInput == null || userInput.trim().isEmpty()) {
                    getLogger().lifecycle("Migration session ended.");
                    break;
                }
                currentPrompt = userInput;
            }

        } catch (Exception e) {
            throw new GradleException("Migration failed", e);
        } finally {
            forceTerminateTransport(transport);
        }
    }

    private static void forceTerminateTransport(StdioAcpClientTransport transport) {
        try {
            java.lang.reflect.Field processField = StdioAcpClientTransport.class.getDeclaredField("process");
            processField.setAccessible(true);
            Process proc = (Process) processField.get(transport);
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
    }

    private static PermissionOptionKind toPermissionKind(String mode) {
        return switch (mode) {
            case "allow_once" -> PermissionOptionKind.ALLOW_ONCE;
            case "reject_once" -> PermissionOptionKind.REJECT_ONCE;
            case "reject_always" -> PermissionOptionKind.REJECT_ALWAYS;
            default -> PermissionOptionKind.ALLOW_ALWAYS;
        };
    }

    private AgentDescriptor resolveAgent() {
        if (agent != null) {
            return new AgentDescriptor(agent, agentArgs != null ? agentArgs.split("\\s+") : new String[] {});
        }
        for (AgentDescriptor descriptor : KNOWN_AGENTS) {
            if (isOnPath(descriptor.binary)) {
                getLogger().lifecycle("Auto-detected ACP agent: " + descriptor.binary);
                return descriptor;
            }
        }
        throw new GradleException(
                "No ACP-compatible agent found on PATH. Specify one explicitly with --agent=<binary>,\n" +
                        "or install a supported agent. Examples (may change — see the full list and providers at\n" +
                        "https://github.com/snowdrop/acp-java-client#acp-agents):\n" +
                        "  Claude Code : npm install -g @agentclientprotocol/claude-agent-acp\n" +
                        "  OpenCode    : see https://opencode.ai/docs/acp/\n" +
                        "  Gemini CLI  : npm install -g @google/gemini-cli");
    }

    private static boolean isOnPath(String binary) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File candidate = new File(dir, binary);
            if (candidate.isFile() && candidate.canExecute()) {
                return true;
            }
            for (String ext : new String[] { ".cmd", ".exe", ".bat" }) {
                File winCandidate = new File(dir, binary + ext);
                if (winCandidate.isFile() && winCandidate.canExecute()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeClaudeSettings(String workspacePath) {
        Path claudeDir = Path.of(workspacePath).resolve(".claude");
        Path settingsFile = claudeDir.resolve("settings.local.json");
        if (Files.exists(settingsFile)) {
            return;
        }
        try {
            Files.createDirectories(claudeDir);
            Files.writeString(settingsFile,
                    "{\n" +
                            "  \"permissions\": {\n" +
                            "    \"allow\": [\n" +
                            "      \"Edit(**/*)\",\n" +
                            "      \"Write(**/*)\",\n" +
                            "      \"WebFetch(*)\",\n" +
                            "      \"Bash(mvn *)\",\n" +
                            "      \"Bash(./mvnw *)\",\n" +
                            "      \"Bash(gradle *)\",\n" +
                            "      \"Bash(./gradlew *)\",\n" +
                            "      \"Bash(git *)\"\n" +
                            "    ]\n" +
                            "  }\n" +
                            "}\n");
            getLogger().debug("Wrote .claude/settings.local.json with Edit/Write/Bash permissions");
        } catch (IOException e) {
            throw new GradleException("Failed to write .claude/settings.local.json", e);
        }
    }

    private volatile boolean spinnerRunning = false;
    private volatile boolean lastWasContent = false;

    private static boolean isInteractiveTerminal() {
        if (System.console() != null) {
            return true;
        }
        // System.console() returns null when Gradle wraps stdout, but we may still be in a real terminal
        String term = System.getenv("TERM");
        return term != null && !term.equals("dumb");
    }

    private Thread startSpinner() {
        if (!isInteractiveTerminal()) {
            return null;
        }
        spinnerRunning = true;
        String[] frames = { "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏" };
        Thread t = new Thread(() -> {
            int i = 0;
            while (spinnerRunning) {
                System.out.print("\r" + frames[i++ % frames.length] + " Agent is working...");
                System.out.flush();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.print("\r                          \r");
            System.out.flush();
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void stopSpinner(Thread spinner) {
        spinnerRunning = false;
        if (spinner != null) {
            try {
                spinner.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleUpdate(SessionNotification notification) {
        spinnerRunning = false;
        String updateType = notification.meta() != null
                ? String.valueOf(notification.meta().get("sessionUpdate"))
                : null;
        Object update = notification.update();
        if (update instanceof ContentChunk && "agent_message_chunk".equals(updateType)) {
            ContentChunk chunk = (ContentChunk) update;
            Object content = chunk.content();
            String text = content instanceof Map<?, ?>
                    ? String.valueOf(((Map<?, ?>) content).get("text"))
                    : String.valueOf(content);
            if (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1))) {
                System.out.println(text.stripTrailing());
            } else {
                System.out.print(text);
            }
            System.out.flush();
            lastWasContent = true;
        } else if (lastWasContent) {
            System.out.println();
            System.out.flush();
            lastWasContent = false;
        }
    }

    private static String buildMigrationPrompt(String workspacePath, String strategy, boolean interactive) {
        StringBuilder sb = new StringBuilder("Read and execute the migration skill at ")
                .append(MIGRATION_SKILL_URL).append(" for the project at ").append(workspacePath).append(".");
        if (!interactive) {
            if (STRATEGY_SPRING_COMPAT.equals(strategy)) {
                sb.append(" Use the Spring compatibility migration strategy (skip the strategy selection step).");
            } else if (STRATEGY_NATIVE.equals(strategy)) {
                sb.append(" Use the native Quarkus migration strategy (skip the strategy selection step).");
            }
            sb.append(" Skip the git workflow step — apply all changes in place.");
        } else {
            sb.append(COMPLETION_INSTRUCTION);
        }
        return sb.toString();
    }

    private void backupWorkspace(String workspacePath) {
        Path source = Path.of(workspacePath);
        Path backup = source.getParent().resolve(source.getFileName() + "-backup");
        getLogger().lifecycle("Backup    : " + backup);
        try {
            copyDirectory(source, backup);
        } catch (IOException e) {
            throw new GradleException("Failed to create workspace backup at " + backup, e);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path dest = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
