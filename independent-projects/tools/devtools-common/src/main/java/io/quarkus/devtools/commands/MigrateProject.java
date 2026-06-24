package io.quarkus.devtools.commands;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
import io.quarkus.devtools.messagewriter.MessageWriter;

/**
 * Migrates a Spring Boot project to Quarkus using an ACP-compatible AI agent.
 * Shared by the Maven mojo and Gradle task.
 */
public class MigrateProject {

    public static final String STRATEGY_SPRING_COMPAT = "spring-compat";
    public static final String STRATEGY_FULL = "full";

    private static final String SKILL_URL_TEMPLATE = "https://raw.githubusercontent.com/quarkusio/skills/main/skills/%s/SKILL.md";
    private static final String MIGRATION_SKILL_URL = SKILL_URL_TEMPLATE.formatted("migrate-spring-to-quarkus");
    private static final String UPDATE_SKILL_URL = SKILL_URL_TEMPLATE.formatted("quarkus-update");
    private static final String COMPLETION_MARKER = "MIGRATION_COMPLETE";
    private static final String COMPLETION_INSTRUCTION = " When you have fully completed all migration steps, end your final message with the exact line: \"Migration complete. Press Enter to finish.\"";
    private static final String COMPLETION_MARKER_INSTRUCTION = " When you have fully completed all migration steps, end your final message with a summary of what was done and any remaining TODOs, followed by the exact line: \""
            + COMPLETION_MARKER + "\".";

    private static final List<AgentDescriptor> KNOWN_AGENTS = List.of(
            new AgentDescriptor("claude-agent-acp", new String[] {}),
            new AgentDescriptor("opencode", new String[] { "acp" }),
            new AgentDescriptor("gemini", new String[] { "--acp", "--skip-trust" }),
            new AgentDescriptor("pi-acp", new String[] {}));

    private final MessageWriter log;
    private final String workspacePath;

    private String agent;
    private String agentArgs;
    private String strategy = STRATEGY_SPRING_COMPAT;
    private String prompt;
    private String permissionMode = "allow_always";
    private boolean noBackup = false;
    private boolean noUpdate = false;
    private int requestTimeout = 30;
    private int promptTimeout = 0;
    private boolean interactive = false;
    private boolean autoSelectAgent = false;
    private int globalTimeout = 0;

    private volatile boolean spinnerRunning = false;
    private volatile boolean spinnerSuppressed = false;
    private volatile boolean lastWasContent = false;
    private volatile boolean atLineStart = true;
    private volatile boolean completionDetected = false;
    private volatile boolean timeoutReached = false;
    private volatile StdioAcpClientTransport activeTransport;

    public MigrateProject(MessageWriter log, String workspacePath) {
        this.log = log;
        this.workspacePath = workspacePath;
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static String ts() {
        return "[" + LocalTime.now().format(TIME_FMT) + "] ";
    }

    public MigrateProject agent(String agent) {
        this.agent = agent;
        return this;
    }

    public MigrateProject agentArgs(String agentArgs) {
        this.agentArgs = agentArgs;
        return this;
    }

    public MigrateProject strategy(String strategy) {
        this.strategy = strategy;
        return this;
    }

    public MigrateProject prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    public MigrateProject permissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
        return this;
    }

    public MigrateProject noBackup(boolean noBackup) {
        this.noBackup = noBackup;
        return this;
    }

    public MigrateProject noUpdate(boolean noUpdate) {
        this.noUpdate = noUpdate;
        return this;
    }

    public MigrateProject requestTimeout(int requestTimeout) {
        this.requestTimeout = requestTimeout;
        return this;
    }

    public MigrateProject promptTimeout(int promptTimeout) {
        this.promptTimeout = promptTimeout;
        return this;
    }

    public MigrateProject interactive(boolean interactive) {
        this.interactive = interactive;
        return this;
    }

    public MigrateProject autoSelectAgent(boolean autoSelectAgent) {
        this.autoSelectAgent = autoSelectAgent;
        return this;
    }

    public MigrateProject globalTimeout(int globalTimeout) {
        this.globalTimeout = globalTimeout;
        return this;
    }

    public void execute() throws Exception {
        if (!interactive) {
            log.info("Tip: run with interactive mode to respond to agent questions during migration.");
        } else if (!STRATEGY_SPRING_COMPAT.equals(strategy)) {
            log.warn("strategy is ignored in interactive mode; the agent will select the strategy interactively.");
        }
        log.warn("quarkus:migrate / quarkusMigrate is experimental, its options and output might change in future versions");

        final AgentDescriptor descriptor = resolveAgent();
        final String migrationPrompt = prompt != null ? prompt : buildMigrationPrompt(workspacePath, strategy, interactive);

        log.info("Workspace : " + workspacePath);
        log.info("Agent     : " + descriptor.binary);

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
        activeTransport = transport;
        if (globalTimeout > 0) {
            long timeoutMs = globalTimeout * 60_000L;
            Thread globalTimer = new Thread(() -> {
                try {
                    Thread.sleep(timeoutMs);
                    log.warn("Global timeout of " + globalTimeout + " min reached — terminating migration.");
                    timeoutReached = true;
                    forceTerminateTransport(activeTransport);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            globalTimer.setDaemon(true);
            globalTimer.start();
        }

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
            log.info("Connected to: " + agentName + " " + agentVersion);

            NewSessionResponse session = client.newSession(new NewSessionRequest(workspacePath, List.of()));
            log.info("Session: " + session.sessionId());

            String currentPrompt = migrationPrompt;
            BufferedReader stdin = interactive ? new BufferedReader(new InputStreamReader(System.in)) : null;
            while (true) {
                Thread spinner = startSpinner();
                try {
                    PromptResponse response = client.prompt(
                            new PromptRequest(List.of(new TextContent(currentPrompt)), session.sessionId()));
                    stopSpinner(spinner);
                    String stopReason = response.stopReason().getValue();
                    if (!interactive || !"end_turn".equalsIgnoreCase(stopReason)) {
                        log.info("Migration step done.");
                        break;
                    }
                } catch (Exception e) {
                    stopSpinner(spinner);
                    if (completionDetected) {
                        log.info("Migration complete — agent signalled completion.");
                        break;
                    }
                    if (timeoutReached) {
                        throw new Exception(
                                "Migration aborted: global timeout of " + globalTimeout + " min exceeded.", e);
                    }
                    throw e;
                }
                System.out.println();
                System.out.print("> ");
                System.out.flush();
                String userInput = stdin.readLine();
                if (userInput == null || userInput.trim().isEmpty()) {
                    log.info("Migration session ended.");
                    break;
                }
                currentPrompt = userInput;
            }

            if (!noUpdate) {
                log.info("Running Quarkus update skill...");
                Thread spinner = startSpinner();
                client.prompt(new PromptRequest(
                        List.of(new TextContent("Read and execute the skill at " + UPDATE_SKILL_URL
                                + " for the project at " + workspacePath + ".")),
                        session.sessionId()));
                stopSpinner(spinner);
                log.info("Update complete.");
            }

        } finally {
            forceTerminateTransport(transport);
        }
    }

    private AgentDescriptor resolveAgent() throws Exception {
        if (agent != null) {
            return new AgentDescriptor(agent, agentArgs != null ? agentArgs.split("\\s+") : new String[] {});
        }
        List<AgentDescriptor> found = new ArrayList<>();
        for (AgentDescriptor descriptor : KNOWN_AGENTS) {
            if (isOnPath(descriptor.binary)) {
                found.add(descriptor);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "No ACP-compatible agent found on PATH. Specify one explicitly with the agent option,\n" +
                            "or install a supported agent. Examples (may change — see the full list and providers at\n" +
                            "https://github.com/snowdrop/acp-java-client#acp-agents):\n" +
                            "  Claude Code : npm install -g @agentclientprotocol/claude-agent-acp\n" +
                            "  OpenCode    : see https://opencode.ai/docs/acp/\n" +
                            "  Gemini CLI  : npm install -g @google/gemini-cli\n" +
                            "  Pi          : npm install -g pi-acp");
        }
        if (found.size() == 1) {
            log.info("Auto-detected ACP agent: " + found.get(0).binary);
            return found.get(0);
        }
        if (autoSelectAgent || !isInteractiveTerminal()) {
            log.info("Auto-detected ACP agent: " + found.get(0).binary
                    + " (multiple found; use --agent or --auto-select-agent to control selection)");
            return found.get(0);
        }
        return promptAgentSelection(found);
    }

    private AgentDescriptor promptAgentSelection(List<AgentDescriptor> found) throws IOException {
        System.out.println("Multiple ACP-compatible agents found on PATH:");
        for (int i = 0; i < found.size(); i++) {
            System.out.println("  [" + (i + 1) + "] " + found.get(i).binary);
        }
        System.out.print("Select agent [1-" + found.size() + "]: ");
        System.out.flush();
        String line = readOneLine();
        if (line != null && !line.trim().isEmpty()) {
            try {
                int choice = Integer.parseInt(line.trim());
                if (choice >= 1 && choice <= found.size()) {
                    return found.get(choice - 1);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        log.warn("Invalid selection; using first detected agent: " + found.get(0).binary);
        return found.get(0);
    }

    private static String readOneLine() throws IOException {
        if (System.console() != null) {
            return System.console().readLine();
        }
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = System.in.read()) != -1 && ch != '\n') {
            if (ch != '\r') {
                sb.append((char) ch);
            }
        }
        return sb.toString();
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

    private void writeClaudeSettings(String path) throws Exception {
        Path claudeDir = Path.of(path).resolve(".claude");
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
                            "      \"Bash(git *)\",\n" +
                            "      \"Bash(java *)\",\n" +
                            "      \"Bash(quarkus *)\"\n" +
                            "    ]\n" +
                            "  }\n" +
                            "}\n");
            log.debug("Wrote .claude/settings.local.json with Edit/Write/Bash permissions");
        } catch (IOException e) {
            throw new Exception("Failed to write .claude/settings.local.json", e);
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

    private static boolean isInteractiveTerminal() {
        if (System.console() != null) {
            return true;
        }
        // System.console() can return null when the runtime wraps stdout (e.g. Quarkus CLI startup),
        // even though we're in a real terminal. Fall back to env var heuristics.
        String term = System.getenv("TERM");
        if (term != null && !term.equals("dumb")) {
            return true;
        }
        String colorterm = System.getenv("COLORTERM");
        return colorterm != null && !colorterm.isEmpty();
    }

    private Thread startSpinner() {
        if (!isInteractiveTerminal()) {
            return null;
        }
        spinnerRunning = true;
        spinnerSuppressed = false;
        String[] frames = { "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏" };
        long startMs = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            int i = 0;
            while (spinnerRunning) {
                if (!spinnerSuppressed) {
                    long elapsed = System.currentTimeMillis() - startMs;
                    long mins = elapsed / 60_000;
                    long secs = (elapsed % 60_000) / 1000;
                    String timer = mins > 0 ? mins + "m " + secs + "s" : secs + "s";
                    String line = "\r" + frames[i++ % frames.length] + " Agent is working... (" + timer + ")    ";
                    System.out.print(line);
                    System.out.flush();
                } else {
                    i = 0;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.print("\r                                        \r");
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
        String updateType = notification.meta() != null
                ? String.valueOf(notification.meta().get("sessionUpdate"))
                : null;
        Object update = notification.update();
        if (update instanceof ContentChunk && "agent_message_chunk".equals(updateType)) {
            if (!lastWasContent) {
                // First chunk in this content block: suppress spinner and clear its line
                spinnerSuppressed = true;
                System.out.print("\r                                        \r");
                System.out.flush();
            }
            ContentChunk chunk = (ContentChunk) update;
            Object content = chunk.content();
            String text = content instanceof Map<?, ?> ? String.valueOf(((Map<?, ?>) content).get("text"))
                    : String.valueOf(content);
            printContent(text);
            System.out.flush();
            lastWasContent = true;
            if (!completionDetected && text.contains(COMPLETION_MARKER)) {
                completionDetected = true;
                Thread terminator = new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    forceTerminateTransport(activeTransport);
                });
                terminator.setDaemon(true);
                terminator.start();
            }
        } else if (lastWasContent) {
            if (!atLineStart) {
                System.out.println();
                atLineStart = true;
            }
            System.out.flush();
            lastWasContent = false;
            spinnerSuppressed = false;
        }
    }

    private void printContent(String text) {
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (atLineStart && i > start) {
                    System.out.print(ts());
                }
                System.out.print(text.substring(start, i + 1));
                start = i + 1;
                atLineStart = true;
            }
        }
        if (start < text.length()) {
            if (atLineStart) {
                System.out.print(ts());
                atLineStart = false;
            }
            System.out.print(text.substring(start));
        }
    }

    private static String buildMigrationPrompt(String path, String strategy, boolean interactive) {
        StringBuilder sb = new StringBuilder("Read and execute the migration skill at ")
                .append(MIGRATION_SKILL_URL).append(" for the project at ").append(path).append(".");
        if (!interactive) {
            if (STRATEGY_SPRING_COMPAT.equals(strategy)) {
                sb.append(" Use the Spring compatibility migration strategy (skip the strategy selection step).");
            } else if (STRATEGY_FULL.equals(strategy)) {
                sb.append(" Use the full Quarkus migration strategy (skip the strategy selection step).");
            }
            sb.append(" Skip the git workflow step — apply all changes in place.");
            sb.append(COMPLETION_MARKER_INSTRUCTION);
        } else {
            sb.append(COMPLETION_INSTRUCTION);
        }
        return sb.toString();
    }

    private void backupWorkspace(String path) throws Exception {
        Path source = Path.of(path);
        Path backup = source.getParent().resolve(source.getFileName() + "-backup");
        log.info("Backup    : " + backup);
        try {
            copyDirectory(source, backup);
        } catch (IOException e) {
            throw new Exception("Failed to create workspace backup at " + backup, e);
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

    private static class AgentDescriptor {
        final String binary;
        final String[] defaultArgs;

        AgentDescriptor(String binary, String[] defaultArgs) {
            this.binary = binary;
            this.defaultArgs = defaultArgs;
        }
    }
}
