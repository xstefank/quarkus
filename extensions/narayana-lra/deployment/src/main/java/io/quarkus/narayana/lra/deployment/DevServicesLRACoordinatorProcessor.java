package io.quarkus.narayana.lra.deployment;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.jboss.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testcontainers.utility.DockerImageName;

import com.arjuna.ats.arjuna.StateManager;
import com.arjuna.ats.arjuna.common.RecoveryEnvironmentBean;
import com.arjuna.ats.arjuna.common.RecoveryEnvironmentBeanMBean;
import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.coordinator.BasicAction;
import com.arjuna.ats.arjuna.exceptions.FatalError;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.arjuna.recovery.RecoveryModule;
import com.arjuna.ats.internal.arjuna.recovery.RecoveryManagerImple;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import com.arjuna.common.logging.commonI18NLogger;
import com.arjuna.common.logging.commonLogger;
import com.arjuna.common.util.ConfigurationInfo;
import com.arjuna.common.util.propertyservice.AbstractPropertiesFactory;
import com.arjuna.common.util.propertyservice.FileLocator;
import com.arjuna.common.util.propertyservice.PropertiesFactory;
import com.arjuna.common.util.propertyservice.PropertiesFactorySax;
import com.arjuna.common.util.propertyservice.PropertiesFactoryStax;

import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.coordinator.api.RecoveryCoordinator;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.internal.LRARecoveryModule;
import io.narayana.lra.coordinator.internal.RecoveringLRA;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Feature;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.AdditionalClassLoaderResourcesBuildItem;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.deployment.util.IoUtil;
import io.quarkus.devservices.common.ContainerAddress;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.resteasy.reactive.spi.AdditionalResourceClassBuildItem;
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
    public void instrument(final CombinedIndexBuildItem index,
            BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer) throws IOException {
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(Coordinator.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(RecoveryCoordinator.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(LRARecoveryModule.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(RecoveryModule.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(LongRunningAction.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(BasicAction.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(StateManager.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(RecoveringLRA.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(ObjectStoreException.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(RecoveryManager.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(RecoveryManagerImple.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(FatalError.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(recoveryPropertyManager.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(RecoveryEnvironmentBean.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(RecoveryEnvironmentBeanMBean.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(BeanPopulator.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(PropertiesFactory.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(AbstractPropertiesFactory.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(PropertiesFactoryStax.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(PropertiesFactorySax.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(ConfigurationInfo.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(FileLocator.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(commonLogger.class));
        generatedClassBuildItemBuildProducer.produce(createGeneratedClassBuildItem(commonI18NLogger.class));
    }

    private static @NotNull GeneratedClassBuildItem createGeneratedClassBuildItem(Class<?> clazz) throws IOException {
        final String classname = clazz.getName();
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        final byte[] bytes = IoUtil.readClassAsBytes(cl, classname);
        return new GeneratedClassBuildItem(false, classname, bytes);
    }

    @BuildStep
    public void addCoordinatorClassToIndex(
            BuildProducer<AdditionalIndexedClassesBuildItem> additionalIndexedClassesBuildItemBuildProducer,
            BuildProducer<AdditionalClassLoaderResourcesBuildItem> additionalClassLoaderResourcesBuildItemBuildProducer)
            throws IOException {
        System.out.println("sdafsadfsadfsadf ----------------");
        additionalIndexedClassesBuildItemBuildProducer
                .produce(new AdditionalIndexedClassesBuildItem(Coordinator.class.getName()));

        //            String cName = Coordinator.class.getName();
        //            String classAsPath = cName.replace('.', '/') + ".class";
        //            InputStream is = Coordinator.class.getClassLoader().getResourceAsStream(classAsPath);
        //
        //            additionalClassLoaderResourcesBuildItemBuildProducer.produce(new AdditionalClassLoaderResourcesBuildItem(
        //                    Map.of(cName, IOUtils.toByteArray(is))));
    }

    @BuildStep
    public DevServicesResultBuildItem startLRADevService(
            DockerStatusBuildItem dockerStatusBuildItem,
            LaunchModeBuildItem launchMode,
            LRABuildTimeConfig lraBuildTimeConfig,
            List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem,
            Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
            CuratedApplicationShutdownBuildItem closeBuildItem,
            LoggingSetupBuildItem loggingSetupBuildItem, GlobalDevServicesConfig devServicesConfig,
            BuildProducer<AdditionalBeanBuildItem> additionalBeanBuildItemBuildProducer,
            BuildProducer<AdditionalResourceClassBuildItem> additionalResourceClassBuildItemBuildProducer,
            CombinedIndexBuildItem combinedIndexBuildItem) throws IOException {

        //        additionalBeanBuildItemBuildProducer.produce(new AdditionalBeanBuildItem(Coordinator.class));
        additionalResourceClassBuildItemBuildProducer.produce(
                new AdditionalResourceClassBuildItem(combinedIndexBuildItem.getIndex().getClassByName(Coordinator.class),
                        "/lra-coordinator"));

        if (lraBuildTimeConfig.devservices.container) {
            return runCoordinatorInContainer(dockerStatusBuildItem, launchMode, lraBuildTimeConfig,
                    devServicesSharedNetworkBuildItem, consoleInstalledBuildItem, closeBuildItem, loggingSetupBuildItem,
                    devServicesConfig);
        }

        return null;
    }

    private @Nullable DevServicesResultBuildItem runCoordinatorInContainer(DockerStatusBuildItem dockerStatusBuildItem,
            LaunchModeBuildItem launchMode, LRABuildTimeConfig lraBuildTimeConfig,
            List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem,
            Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem, CuratedApplicationShutdownBuildItem closeBuildItem,
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
