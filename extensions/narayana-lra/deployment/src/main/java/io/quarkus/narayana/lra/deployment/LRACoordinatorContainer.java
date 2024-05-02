package io.quarkus.narayana.lra.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.OptionalInt;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.command.InspectContainerResponse;

public class LRACoordinatorContainer extends GenericContainer<LRACoordinatorContainer> {

    private final OptionalInt fixedExposedPort;
    private final boolean useSharedNetwork;

    private String additionalArgs = null;

    private String hostName = null;

    public LRACoordinatorContainer(DockerImageName dockerImageName, OptionalInt fixedExposedPort, String serviceName,
            boolean useSharedNetwork) {
        super(dockerImageName);
        this.fixedExposedPort = OptionalInt.of(findRandomPort());
        this.useSharedNetwork = useSharedNetwork;
        if (serviceName != null) {
            withLabel(DevServicesLRACoordinatorProcessor.DEV_SERVICE_LABEL, serviceName);
        }
        //        withNetworkMode("host"); // doesn't work
        withNetworkMode("bridge");
        withExtraHost("host.docker.internal", "host-gateway");
        withEnv("JAVA_OPTS", "-Dquarkus.http.port=" + this.fixedExposedPort.getAsInt());
        if (additionalArgs != null) {
            withCommand(additionalArgs);
        }
        waitingFor(Wait.forLogMessage(".*lra-coordinator-quarkus.*", 1));
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo, boolean reused) {
        super.containerIsStarting(containerInfo, reused);
    }

    @Override
    protected void configure() {
        super.configure();

        addExposedPort(fixedExposedPort.getAsInt());
    }

    public int getPort() {
        if (useSharedNetwork) {
            return DevServicesLRACoordinatorProcessor.LRA_COORDINATOR_PORT;
        }
        if (fixedExposedPort.isPresent()) {
            return fixedExposedPort.getAsInt();
        }
        return getFirstMappedPort();
    }

    public String getCoordinatorURL() {
        return "http://%s:%d/lra-coordinator".formatted(hostName, fixedExposedPort.getAsInt());
    }

    private Integer findRandomPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
