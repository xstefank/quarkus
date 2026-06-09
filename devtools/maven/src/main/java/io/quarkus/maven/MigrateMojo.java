package io.quarkus.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

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

/**
 * Migrate a Spring Boot project to Quarkus using an ACP-compatible AI agent.
 *
 * TODO: extract shared ACP invocation logic (agent detection, backup, prompt building, session loop)
 * into a MigrateProject class in quarkus-devtools-common, mirroring the UpdateProject pattern,
 * once acp-java-core has a stable release.
 */
@Mojo(name = "migrate", requiresProject = true)
public class MigrateMojo extends AbstractMojo {

    private static final class AgentDescriptor {
        final String binary;
        final String[] defaultArgs;

        AgentDescriptor(String binary, String[] defaultArgs) {
            this.binary = binary;
            this.defaultArgs = defaultArgs;
        }
    }

    private static final String MIGRATION_SKILL_URL = "https://raw.githubusercontent.com/quarkusio/skills/main/skills/migrate-spring-to-quarkus/SKILL.md";
    private static final String STRATEGY_SPRING_COMPAT = "spring-compat";
    private static final String STRATEGY_NATIVE = "native";

    private static final List<AgentDescriptor> KNOWN_AGENTS = List.of(
            new AgentDescriptor("claude-agent-acp", new String[] {}),
            new AgentDescriptor("opencode", new String[] { "acp" }),
            new AgentDescriptor("gemini", new String[] { "--acp" }),
            new AgentDescriptor("pi-acp", new String[] {}));

    @Parameter(defaultValue = "${basedir}", readonly = true)
    File projectDir;

    @Parameter(property = "agent")
    String agent;

    @Parameter(property = "agentArgs")
    String agentArgs;

    @Parameter(property = "model")
    String model;

    @Parameter(property = "strategy", defaultValue = STRATEGY_SPRING_COMPAT)
    String strategy;

    @Parameter(property = "prompt")
    String prompt;

    @Parameter(property = "permissionMode", defaultValue = "allow_always")
    String permissionMode = "allow_always";

    @Parameter(property = "noBackup", defaultValue = "false")
    boolean noBackup = false;

    @Parameter(property = "wks")
    String wks;

    @Parameter(property = "requestTimeout", defaultValue = "30")
    int requestTimeout = 30;

    @Parameter(property = "promptTimeout", defaultValue = "0")
    int promptTimeout = 0;

    @Parameter(property = "interactive", defaultValue = "false")
    boolean interactive;

    @Override
    public void execute() throws MojoExecutionException {
        if (!interactive) {
            getLog().info("Tip: run with -Dinteractive to respond to agent questions during migration.");
        } else if (!STRATEGY_SPRING_COMPAT.equals(strategy)) {
            getLog().warn("-Dstrategy is ignored in interactive mode; the agent will select the strategy interactively.");
        }
        getLog().warn("quarkus:migrate goal is experimental, its options and output might change in future versions");

        final String workspacePath = wks != null ? wks : projectDir.getAbsolutePath();
        final AgentDescriptor descriptor = resolveAgent();
        final String migrationPrompt = prompt != null ? prompt : buildMigrationPrompt(workspacePath, strategy, interactive);

        getLog().info("Workspace : " + workspacePath);
        getLog().info("Agent     : " + descriptor.binary);

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
            getLog().info("Connected to: " + agentName + " " + agentVersion);

            NewSessionResponse session = client.newSession(new NewSessionRequest(workspacePath, List.of()));
            getLog().info("Session: " + session.sessionId());

            String currentPrompt = migrationPrompt;
            BufferedReader stdin = interactive ? new BufferedReader(new InputStreamReader(System.in)) : null;
            while (true) {
                Thread spinner = startSpinner();
                PromptResponse response = client.prompt(
                        new PromptRequest(List.of(new TextContent(currentPrompt)), session.sessionId()));
                stopSpinner(spinner);
                String stopReason = response.stopReason().getValue();
                if (!interactive || !"end_turn".equalsIgnoreCase(stopReason)) {
                    getLog().info("Done. Stop reason: " + stopReason);
                    getLog().info(
                            "The agent may have used an older Quarkus version based on its training data. Run 'mvn quarkus:update' to upgrade to the latest available release.");
                    break;
                }
                System.out.println();
                System.out.print("> ");
                System.out.flush();
                String userInput = stdin.readLine();
                if (userInput == null || userInput.trim().isEmpty()) {
                    getLog().info("Migration session ended.");
                    break;
                }
                currentPrompt = userInput;
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Migration failed", e);
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

    private AgentDescriptor resolveAgent() throws MojoExecutionException {
        if (agent != null) {
            return new AgentDescriptor(agent, agentArgs != null ? agentArgs.split("\\s+") : new String[] {});
        }
        for (AgentDescriptor descriptor : KNOWN_AGENTS) {
            if (isOnPath(descriptor.binary)) {
                getLog().info("Auto-detected ACP agent: " + descriptor.binary);
                return descriptor;
            }
        }
        throw new MojoExecutionException(
                "No ACP-compatible agent found on PATH. Specify one explicitly with -Dagent=<binary>,\n" +
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
            // Windows: also check with .cmd / .exe suffixes
            for (String ext : new String[] { ".cmd", ".exe", ".bat" }) {
                File winCandidate = new File(dir, binary + ext);
                if (winCandidate.isFile() && winCandidate.canExecute()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeClaudeSettings(String workspacePath) throws MojoExecutionException {
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
            getLog().debug("Wrote .claude/settings.local.json with Edit/Write/Bash permissions");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write .claude/settings.local.json", e);
        }
    }

    private volatile boolean spinnerRunning = false;
    private volatile boolean lastWasContent = false;

    private static boolean isInteractiveTerminal() {
        if (System.console() != null) {
            return true;
        }
        // System.console() returns null when Maven wraps stdout, but we may still be in a real terminal
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
            String text = content instanceof Map<?, ?> ? String.valueOf(((Map<?, ?>) content).get("text"))
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

    private static final String COMPLETION_INSTRUCTION = " When you have fully completed all migration steps, end your final message with the exact line: \"Migration complete. Press Enter to finish.\"";

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

    private void backupWorkspace(String workspacePath) throws MojoExecutionException {
        Path source = Path.of(workspacePath);
        Path backup = source.getParent().resolve(source.getFileName() + "-backup");
        getLog().info("Backup    : " + backup);
        try {
            copyDirectory(source, backup);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create workspace backup at " + backup, e);
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
