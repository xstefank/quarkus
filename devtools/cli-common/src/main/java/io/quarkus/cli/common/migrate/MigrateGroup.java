package io.quarkus.cli.common.migrate;

import picocli.CommandLine;

public class MigrateGroup {

    public static final String STRATEGY_SPRING_COMPAT = "spring-compat";
    public static final String STRATEGY_NATIVE = "native";

    @CommandLine.Option(order = 0, names = { "-a",
            "--agent" }, description = "ACP-compatible agent binary to use for migration (e.g. claude-agent-acp, opencode, gemini). Auto-detected from PATH if not specified.")
    public String agent;

    @CommandLine.Option(order = 1, names = {
            "--agent-args" }, description = "Space-separated arguments to pass to the agent binary. Overrides the built-in defaults for the detected agent.")
    public String agentArgs;

    @CommandLine.Option(order = 2, names = {
            "--provider" }, description = "Model provider to use with the agent: zen, vertex-ai.", defaultValue = "zen")
    public String provider = "zen";

    @CommandLine.Option(order = 3, names = { "-m",
            "--model" }, description = "Model to use (e.g. claude-opus-4-6). Resolved per agent/provider if not specified.")
    public String model;

    @CommandLine.Option(order = 4, names = {
            "--strategy" }, description = "Migration strategy: spring-compat (use Quarkus Spring compatibility extensions, minimal code changes) or native (replace Spring annotations with JAX-RS/CDI).", defaultValue = STRATEGY_SPRING_COMPAT)
    public String strategy;

    @CommandLine.Option(order = 5, names = { "-p",
            "--prompt" }, description = "Override the built-in Spring Boot → Quarkus migration prompt.")
    public String prompt;

    @CommandLine.Option(order = 6, names = {
            "--permission-mode" }, description = "How to respond to agent permission requests: allow_always, allow_once, reject_once, reject_always.", defaultValue = "allow_always")
    public String permissionMode = "allow_always";

    @CommandLine.Option(order = 7, names = {
            "--no-backup" }, description = "Disable workspace backup before running the migration.", defaultValue = "false")
    public boolean noBackup = false;

    @CommandLine.Option(order = 8, names = { "--wks",
            "--workspace-path" }, description = "Absolute path to the project to migrate. Defaults to the current directory.")
    public String workspacePath;

    @CommandLine.Option(order = 9, names = {
            "--request-timeout" }, description = "Timeout in seconds for individual ACP protocol requests.", defaultValue = "30")
    public int requestTimeout = 30;

    @CommandLine.Option(order = 10, names = {
            "--prompt-timeout" }, description = "Timeout in seconds for the migration prompt (0 = no timeout).", defaultValue = "0")
    public int promptTimeout = 0;

    @CommandLine.Option(order = 11, names = { "-i",
            "--interactive" }, description = "Enable interactive mode to respond to agent questions during migration.", defaultValue = "false")
    public boolean interactive;
}
