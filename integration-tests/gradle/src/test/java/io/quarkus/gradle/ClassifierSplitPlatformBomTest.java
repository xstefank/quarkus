package io.quarkus.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.bootstrap.app.ApplicationModelSerializer;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.maven.dependency.Dependency;

/**
 * Reproduces and verifies the fix for a Gradle plugin bug: a platform BOM's {@code <dependencyManagement>}
 * may legitimately declare the same {@code groupId:artifactId} at different versions for different Maven
 * {@code <classifier>} values (as the real, productized {@code quarkus-bom}/{@code netty-bom} does for
 * {@code io.netty:netty-transport-native-unix-common}, which is only rebuilt for some platforms). This is
 * valid, unambiguous Maven, but Gradle's Maven-BOM-import mechanism discards classifier information and
 * produces a single version constraint per {@code group:artifact}, previously failing such builds with
 * {@code ConflictingConstraintsException} when resolving {@code quarkusProdRuntimeClasspathConfigurationDeployment}.
 * <p/>
 * This test uses a fully synthetic BOM (no real productized artifacts, no network access beyond
 * {@code mavenLocal()}) to reproduce the same classifier-split shape, including a nested
 * {@code <scope>import</scope>} BOM (mirroring {@code quarkus-bom} importing {@code netty-bom}) to also
 * exercise recursive import resolution.
 * <p/>
 * We avoid a {@code @BeforeAll} lifecycle hook (which would need to be static) to preserve access to
 * instance helpers from {@link QuarkusGradleWrapperTestBase}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Platform BOM classifier-split version conflict (Gradle)")
public class ClassifierSplitPlatformBomTest extends QuarkusGradleWrapperTestBase {

    private static final String CONSUMER_PROJECT_PATH = "enforcing-platform-classifier-conflict/consumer-project";
    private static final String PRODUCER_PROJECT_PATH = "enforcing-platform-classifier-conflict/other-deps";

    /**
     * Publishes the synthetic classifier-split library and BOMs used by the subsequent test to the local
     * Maven repository.
     */
    @Test
    @Order(1)
    @DisplayName("Publish synthetic classifier-split test artifacts to local Maven repository")
    public void publishTestArtifacts() throws IOException, InterruptedException {
        File dependencyProject = getProjectDir(PRODUCER_PROJECT_PATH);
        runGradleWrapper(dependencyProject, ":classifier-split-lib:publishToMavenLocal");
        // classifier-split-root-bom imports classifier-split-nested-bom, so the nested BOM must already be
        // installed to mavenLocal by the time root-bom's own `mvn install` runs; these are two independent
        // Gradle projects with no dependsOn relationship, so they must be published in two separate,
        // sequential invocations rather than as a single multi-task command line (Gradle does not guarantee
        // the two tasks would otherwise run in argument order).
        runGradleWrapper(dependencyProject, ":classifier-split-nested-bom:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":classifier-split-root-bom:publishToMavenLocal");
    }

    /**
     * Verifies that a project applying a platform BOM with classifier-diverging
     * {@code dependencyManagement} versions for the same {@code group:artifact} now resolves successfully
     * (this previously failed with {@code ConflictingConstraintsException}), and that the artifact is
     * aligned to the canonical no-classifier version declared by the BOM.
     */
    @Test
    @Order(2)
    @DisplayName("Resolve platform BOM with classifier-split dependencyManagement versions")
    public void resolvesClassifierSplitPlatformBom() throws Exception {
        var projectDir = getProjectDir(CONSUMER_PROJECT_PATH);
        // quarkusGenerateAppModel is the PROD/NORMAL launch mode task, resolving
        // quarkusProdRuntimeClasspathConfigurationDeployment - the exact configuration in which this
        // conflict was originally observed to fail.
        runGradleWrapper(projectDir, "clean", ":runner:quarkusGenerateAppModel");

        ApplicationModel appModel = ApplicationModelSerializer.deserialize(
                projectDir.toPath().resolve("runner/build/quarkus/application-model/quarkus-app-model.dat"));
        Optional<? extends Dependency> classifierSplitLib = appModel.getDependencies().stream()
                .filter(d -> "org.enforcing.deps".equals(d.getGroupId()) && "classifier-split-lib".equals(d.getArtifactId()))
                .findFirst();
        assertThat(classifierSplitLib).isPresent();
        assertThat(classifierSplitLib.get().getVersion()).isEqualTo("1.0.0");
    }
}
