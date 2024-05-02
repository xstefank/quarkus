package io.quarkus.narayana.lra.deployment;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigItem;

@ConfigGroup
public class LRADevServicesBuildTimeConfig {

    /**
     * Enable or disable LRA DevServices. The DevService is generally enabled if
     * the LRA Coordinator URL (quarkus.lra.coordinator-url) is not configured.
     */
    @ConfigItem
    public Optional<Boolean> enabled = Optional.empty();

    /**
     * Optional fixed port the dev service will listen to.
     * <p>
     * If not defined, the port will be chosen randomly.
     */
    @ConfigItem
    public OptionalInt port;

    /**
     * The LRA Coordinator container image to use.
     */
    @ConfigItem
    public Optional<String> image;

    /**
     * Whether the started LRA Coordinator DevService is shared among all Quarkus applications
     * started in dev mode. It utilizes the label-based service discovery to find the LRA
     * Coordinator started by different dev mode. If found, the dev mode connects to the existing
     * LRA Coordinator. Otherwise, a new LRA DevService is started.
     * <p>
     * The discovery uses the {@code quarkus-dev-service-lra} label.
     * The value is configured using the {@code service-name} property.
     * <p>
     * Container sharing is only used in dev mode.
     */
    @ConfigItem(defaultValue = "true")
    public boolean shared;

    /**
     * The value of the {@code quarkus-dev-service-lra} label attached to the started container.
     * This property is used when {@code shared} is set to {@code true}.
     * In this case, before starting a container, Dev Services for Kafka looks for a container with the
     * {@code quarkus-dev-service-lra} label
     * set to the configured value. If found, it will use this container instead of starting a new one. Otherwise, it
     * starts a new container with the {@code quarkus-dev-service-lra} label set to the specified value.
     * <p>
     * This property is used when you need multiple shared LRA Coordinators simultaneously.
     */
    @ConfigItem(defaultValue = "lra")
    public String serviceName;

    /**
     * Environment variables that are passed to the container.
     */
    @ConfigItem
    public Map<String, String> containerEnv;

}
