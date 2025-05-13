package io.quarkus.oidc.health;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import io.quarkus.logging.Log;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;

@Readiness
@ApplicationScoped
public class OIDCHealthCheck implements HealthCheck {

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "quarkus.oidc.health.url")
    Optional<String> healthUrl;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("OIDC connection health check");
        if (healthUrl.isEmpty() || healthUrl.get().isEmpty()) {
            return builder.down()
                    .withData("error", "No health URL configured. Define the quarkus.oidc.health.url configuration property.")
                    .build();
        }

        String urlValue = healthUrl.get();
        WebClient client = null;

        try {
            client = WebClient.create(vertx);
            URL url = new URL(urlValue);
            client.get(url.getPort(), url.getHost(), url.getPath()).send()
                    .onSuccess(response -> {
                        if (response.statusCode() / 100 == 2) { // HTTP 2xx
                            builder.up();
                        } else {
                            builder.down()
                                    .withData("status", response.statusCode())
                                    .withData("body", response.bodyAsString());
                        }
                    }).onFailure(error -> builder.down().withData("network-error", error.getMessage()))
                    .toCompletionStage().toCompletableFuture().join();
        } catch (MalformedURLException e) {
            Log.error("Invalid URL for OIDC health check: " + urlValue, e);
            builder.down().withData("invalid-url", urlValue);
        } finally {
            if (client != null) {
                client.close();
            }
        }

        return builder.build();
    }
}
