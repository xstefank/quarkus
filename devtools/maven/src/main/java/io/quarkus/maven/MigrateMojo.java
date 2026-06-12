package io.quarkus.maven;

import java.io.File;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import io.quarkus.devtools.commands.MigrateProject;
import io.quarkus.devtools.messagewriter.MessageWriter;

/**
 * Migrate a Spring Boot project to Quarkus using an ACP-compatible AI agent.
 */
@Mojo(name = "migrate", requiresProject = false)
public class MigrateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${basedir}", readonly = true)
    File projectDir;

    @Parameter(property = "agent")
    String agent;

    @Parameter(property = "agentArgs")
    String agentArgs;

    @Parameter(property = "model")
    String model;

    @Parameter(property = "strategy", defaultValue = MigrateProject.STRATEGY_SPRING_COMPAT)
    String strategy;

    @Parameter(property = "prompt")
    String prompt;

    @Parameter(property = "permissionMode", defaultValue = "allow_always")
    String permissionMode = "allow_always";

    @Parameter(property = "noBackup", defaultValue = "false")
    boolean noBackup = false;

    @Parameter(property = "noUpdate", defaultValue = "false")
    boolean noUpdate = false;

    @Parameter(property = "wks")
    String wks;

    @Parameter(property = "requestTimeout", defaultValue = "30")
    int requestTimeout = 30;

    @Parameter(property = "promptTimeout", defaultValue = "0")
    int promptTimeout = 0;

    @Parameter(property = "interactive", defaultValue = "false")
    boolean interactive;

    @Parameter(property = "autoSelectAgent", defaultValue = "false")
    boolean autoSelectAgent = false;

    @Override
    public void execute() throws MojoExecutionException {
        final String workspacePath = wks != null ? wks
                : projectDir != null ? projectDir.getAbsolutePath()
                        : System.getProperty("user.dir");
        final MessageWriter log = mavenMessageWriter();
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
            throw new MojoExecutionException("Migration failed", e);
        }
    }

    private MessageWriter mavenMessageWriter() {
        return new MessageWriter() {
            @Override
            public void info(String msg) {
                getLog().info(msg);
            }

            @Override
            public void error(String msg) {
                getLog().error(msg);
            }

            @Override
            public boolean isDebugEnabled() {
                return getLog().isDebugEnabled();
            }

            @Override
            public void debug(String msg) {
                getLog().debug(msg);
            }

            @Override
            public void warn(String msg) {
                getLog().warn(msg);
            }
        };
    }
}
