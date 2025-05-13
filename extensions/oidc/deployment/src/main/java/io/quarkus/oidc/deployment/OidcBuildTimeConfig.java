package io.quarkus.oidc.deployment;

import java.util.Optional;

import io.quarkus.oidc.runtime.OidcConfig;
import io.quarkus.runtime.annotations.ConfigDocSection;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Build time configuration for OIDC.
 */
@ConfigMapping(prefix = "quarkus.oidc")
@ConfigRoot
public interface OidcBuildTimeConfig {
    /**
     * If the OIDC extension is enabled.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * OIDC Dev UI configuration which is effective in dev mode only.
     */
    @ConfigDocSection
    DevUiConfig devui();

    /**
     * Enable the registration of the Default TokenIntrospection and UserInfo Cache implementation bean.
     * Note: This only enables the default implementation. It requires configuration to be activated.
     * See {@link OidcConfig#tokenCache}.
     */
    @WithDefault("true")
    boolean defaultTokenCacheEnabled();

    /**
     * Whether a health check is published in case the smallrye-health extension is present.
     * <p>
     * If you enable the health check, you must specify the `quarkus.oidc.health.url` property.
     */
    @WithName("health.enabled")
    @WithDefault("false")
    boolean healthEnabled();

    /**
     * The URL of the endoint invoked as health check.
     */
    @WithName("health.url")
    Optional<String> healthUrl();
}
