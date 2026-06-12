package io.quarkus.gradle.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

import io.quarkus.devtools.commands.MigrateProject;
import io.quarkus.devtools.messagewriter.MessageWriter;

@DisableCachingByDefault(because = "Not cacheable")
public abstract class QuarkusMigrate extends DefaultTask {

    private String agent;
    private String agentArgs;
    private String model;
    private String strategy = MigrateProject.STRATEGY_SPRING_COMPAT;
    private String prompt;
    private String permissionMode = "allow_always";
    private boolean noBackup = false;
    private boolean noUpdate = false;
    private String wks;
    private int requestTimeout = 30;
    private int promptTimeout = 0;
    private boolean interactive = false;
    private boolean autoSelectAgent = false;

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
    public boolean isNoUpdate() {
        return noUpdate;
    }

    @Option(description = "Disable automatic Quarkus update after migration.", option = "noUpdate")
    public void setNoUpdate(boolean noUpdate) {
        this.noUpdate = noUpdate;
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

    @Input
    public boolean isAutoSelectAgent() {
        return autoSelectAgent;
    }

    @Option(description = "When multiple ACP agents are detected on PATH, automatically use the first one instead of prompting.", option = "autoSelectAgent")
    public void setAutoSelectAgent(boolean autoSelectAgent) {
        this.autoSelectAgent = autoSelectAgent;
    }

    @TaskAction
    public void migrate() {
        final String workspacePath = wks != null ? wks : getProject().getProjectDir().getAbsolutePath();
        final MessageWriter log = gradleMessageWriter();
        try {
            new MigrateProject(log, workspacePath)
                    .agent(agent)
                    .agentArgs(agentArgs)
                    .strategy(strategy)
                    .prompt(prompt)
                    .permissionMode(permissionMode)
                    .noBackup(noBackup)
                    .noUpdate(noUpdate)
                    .requestTimeout(requestTimeout)
                    .promptTimeout(promptTimeout)
                    .interactive(interactive)
                    .autoSelectAgent(autoSelectAgent)
                    .execute();
        } catch (Exception e) {
            throw new GradleException("Migration failed", e);
        }
    }

    private MessageWriter gradleMessageWriter() {
        return new MessageWriter() {
            @Override
            public void info(String msg) {
                getLogger().lifecycle(msg);
            }

            @Override
            public void error(String msg) {
                getLogger().error(msg);
            }

            @Override
            public boolean isDebugEnabled() {
                return getLogger().isDebugEnabled();
            }

            @Override
            public void debug(String msg) {
                getLogger().debug(msg);
            }

            @Override
            public void warn(String msg) {
                getLogger().warn(msg);
            }
        };
    }
}
