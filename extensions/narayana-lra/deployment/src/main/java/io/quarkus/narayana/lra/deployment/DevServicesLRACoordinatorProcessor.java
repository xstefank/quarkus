package io.quarkus.narayana.lra.deployment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.jboss.logging.Logger;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.deployment.Feature;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.devservices.common.ContainerAddress;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.configuration.ConfigUtils;

/**
 * Starts the Narayana LRA coordinator as a Dev service if needed.
 */
@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = GlobalDevServicesConfig.Enabled.class)
public class DevServicesLRACoordinatorProcessor {

    private static final Logger log = Logger.getLogger(DevServicesLRACoordinatorProcessor.class);
    private static final String QUARKUS_LRA_COORDINATOR_URL = "quarkus.lra.coordinator-url";

    /**
     * Label to add to shared Dev Service for LRA coordinator running in containers.
     * This allows other applications to discover the running service and use it instead of starting a new instance.
     */
    static final String DEV_SERVICE_LABEL = "quarkus-dev-service-lra";
    static final int LRA_COORDINATOR_PORT = 8080;
    static final String LRA_COORDINATOR_IMAGE = "quay.io/jbosstm/lra-coordinator";

    private static final ContainerLocator lraContainerLocator = new ContainerLocator(DEV_SERVICE_LABEL, LRA_COORDINATOR_PORT);

    static volatile RunningDevService devService;
    static volatile LRADevServiceConfig cfg;
    static volatile boolean first = true;

    @BuildStep
    public DevServicesResultBuildItem startLRADevService(
            DockerStatusBuildItem dockerStatusBuildItem,
            LaunchModeBuildItem launchMode,
            LRABuildTimeConfig lraBuildTimeConfig,
            List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem,
            Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
            CuratedApplicationShutdownBuildItem closeBuildItem,
            LoggingSetupBuildItem loggingSetupBuildItem, GlobalDevServicesConfig devServicesConfig) {

        LRADevServiceConfig configuration = new LRADevServiceConfig(lraBuildTimeConfig.devservices);

        if (devService != null) {
            boolean shouldShutdownTheBroker = !configuration.equals(cfg);
            if (!shouldShutdownTheBroker) {
                return devService.toBuildItem();
            }
            shutdownCoordinator();
            cfg = null;
        }

        StartupLogCompressor compressor = new StartupLogCompressor(
                (launchMode.isTest() ? "(test) " : "") + "LRA Dev Services Starting:",
                consoleInstalledBuildItem, loggingSetupBuildItem);
        try {
            devService = startCoordinator(dockerStatusBuildItem, configuration, launchMode,
                    !devServicesSharedNetworkBuildItem.isEmpty(),
                    devServicesConfig.timeout);
            if (devService == null) {
                compressor.closeAndDumpCaptured();
            } else {
                compressor.close();
            }
        } catch (Throwable t) {
            compressor.closeAndDumpCaptured();
            throw new RuntimeException(t);
        }

        if (devService == null) {
            return null;
        }

        // Configure the watch dog
        if (first) {
            first = false;
            Runnable closeTask = () -> {
                if (devService != null) {
                    shutdownCoordinator();
                }
                first = true;
                devService = null;
                cfg = null;
            };
            closeBuildItem.addCloseTask(closeTask, true);
        }
        cfg = configuration;

        if (devService.isOwner()) {
            log.infof(
                    "Dev Services for LRA started. Other Quarkus applications in dev mode will find the "
                            + "LRA coordinator automatically. For Quarkus applications in production mode, you can connect to"
                            + " this by starting your application with -Dquarkus.lra.coordinator-url=%s",
                    getLRACoordinatorUrl());
        }
        return devService.toBuildItem();
    }

    public static String getLRACoordinatorUrl() {
        return devService.getConfig().get(QUARKUS_LRA_COORDINATOR_URL);
    }

    private void shutdownCoordinator() {
        if (devService != null) {
            try {
                devService.close();
            } catch (Throwable e) {
                log.error("Failed to stop the LRA Coordinator DevService", e);
            } finally {
                devService = null;
            }
        }
    }

    private RunningDevService startCoordinator(DockerStatusBuildItem dockerStatusBuildItem, LRADevServiceConfig config,
            LaunchModeBuildItem launchMode, boolean useSharedNetwork, Optional<Duration> timeout) {
        if (!config.enabled) {
            // explicitly disabled
            log.debug("Not starting dev services for LRA, as it has been disabled in the config.");
            return null;
        }

        // Check the quarkus.lra.coordinator-url property
        if (ConfigUtils.isPropertyPresent(QUARKUS_LRA_COORDINATOR_URL)) {
            log.debug("Not starting dev services for LRA, the quarkus.lra.coordinator-url is configured.");
            return null;
        }

        if (!dockerStatusBuildItem.isDockerAvailable()) {
            log.warn(
                    "Docker isn't working, please start your own LRA Coordinator and configure the quarkus.lra.coordinator-url property accordingly.");
            return null;
        }

        final Optional<ContainerAddress> maybeContainerAddress = lraContainerLocator.locateContainer(
                config.serviceName,
                config.shared,
                launchMode.getLaunchMode());

        // Start the LRA Coordinator
        LRACoordinatorContainer lraCoordinatorContainer = new LRACoordinatorContainer(DockerImageName.parse(config.imageName),
                config.fixedExposedPort,
                launchMode.getLaunchMode() == LaunchMode.DEVELOPMENT ? config.serviceName : null,
                useSharedNetwork);
        timeout.ifPresent(lraCoordinatorContainer::withStartupTimeout);
        lraCoordinatorContainer.withEnv(config.containerEnv);
        lraCoordinatorContainer.start();

        //        String coordinatorUrl = lraCoordinatorContainer.getCoordinatorURL();

        return new RunningDevService(Feature.NARAYANA_LRA.getName(),
                lraCoordinatorContainer.getContainerId(),
                lraCoordinatorContainer::close,
                Map.of(QUARKUS_LRA_COORDINATOR_URL, lraCoordinatorContainer.getCoordinatorURL()));
    }

    private static final class LRADevServiceConfig {
        private final boolean enabled;
        private final String imageName;
        private final OptionalInt fixedExposedPort;
        private final boolean shared;
        private final String serviceName;
        private final Map<String, String> containerEnv;

        public LRADevServiceConfig(LRADevServicesBuildTimeConfig config) {
            this.enabled = config.enabled.orElse(true);
            this.imageName = config.image.orElse(LRA_COORDINATOR_IMAGE);
            this.fixedExposedPort = config.port;
            this.shared = config.shared;
            this.serviceName = config.serviceName;
            this.containerEnv = config.containerEnv;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            LRADevServiceConfig that = (LRADevServiceConfig) o;
            return enabled == that.enabled
                    && Objects.equals(imageName, that.imageName)
                    && Objects.equals(fixedExposedPort, that.fixedExposedPort)
                    && Objects.equals(containerEnv, that.containerEnv);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, imageName, fixedExposedPort, containerEnv);
        }
    }

}
