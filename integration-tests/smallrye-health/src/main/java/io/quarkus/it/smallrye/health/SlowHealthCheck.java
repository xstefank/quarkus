package io.quarkus.it.smallrye.health;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@ApplicationScoped
public class SlowHealthCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return HealthCheckResponse.up("slow-check");
    }
}
