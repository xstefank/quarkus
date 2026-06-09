package io.quarkus.cli;

import java.util.concurrent.Callable;

import io.quarkus.cli.common.build.BuildSystemRunner;
import io.quarkus.cli.common.migrate.MigrateGroup;
import picocli.CommandLine;

@CommandLine.Command(name = "migrate", aliases = {
        "mg" }, sortOptions = false, showDefaultValues = true, mixinStandardHelpOptions = false, header = "Migrate a Spring Boot project to Quarkus using an ACP-compatible AI agent.", headerHeading = "%n", commandListHeading = "%nCommands:%n", synopsisHeading = "%nUsage: ", parameterListHeading = "%n", optionListHeading = "%nOptions:%n")
public class Migrate extends BaseBuildCommand implements Callable<Integer> {

    @CommandLine.ArgGroup(order = 0, heading = "%nMigration options:%n", exclusive = false)
    MigrateGroup migrate = new MigrateGroup();

    @Override
    public Integer call() throws Exception {
        try {
            final BuildSystemRunner runner = getRunner();
            return runner.migrateProject(migrate);
        } catch (Exception e) {
            return output.handleCommandException(e, "Unable to run Quarkus project migration: " + e.getMessage());
        }
    }
}
