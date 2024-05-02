package io.quarkus.narayana.lra.deployment;

import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;

@ConfigRoot(name = "lra", phase = ConfigPhase.BUILD_TIME)
public class LRABuildTimeConfig {

    /**
     * Configuration for the LRA DevServices.
     */
    @ConfigItem
    public LRADevServicesBuildTimeConfig devservices;

}
