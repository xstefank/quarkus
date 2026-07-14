package io.quarkus.gradle.dependency;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Provider;

import io.quarkus.gradle.tooling.GradleAssistedMavenModelResolverImpl;
import io.quarkus.maven.dependency.ArtifactKey;

/**
 * A Maven BOM's {@code <dependencyManagement>} may legitimately declare the same
 * {@code groupId:artifactId} at different versions for different {@code <classifier>} values
 * (e.g. a productized native artifact that is only rebuilt for some platforms, falling back to
 * the upstream community version for the rest). Maven resolves classifiers independently, so this
 * is not a conflict there.
 * <p/>
 * Gradle's Maven-BOM-import mechanism has no equivalent of a classifier-scoped version: it
 * converts {@code <dependencyManagement>} into a single {@link org.gradle.api.artifacts.DependencyConstraint}
 * per {@code group:artifact}, discarding classifier entirely. When such a BOM is consumed via
 * {@code enforcedPlatform(...)}, Gradle's own dependency graph resolution sees two irreconcilable
 * version constraints for the same module and fails the build.
 * <p/>
 * This class recovers the classifier-scoped version data that Gradle discards, by resolving the
 * enforced-platform BOM's effective Maven model directly, including transitively imported BOMs
 * (e.g. {@code quarkus-bom} importing {@code netty-bom} via {@code <scope>import</scope>}).
 * For every {@code group:artifact} whose declared versions differ across classifiers, the version
 * declared for the default (no) classifier is treated as the canonical version, namely the
 * version a plain, unclassified dependency on that artifact would resolve to in Maven, and it is
 * the only classifier variant Gradle's own resolution graph is actually shaped around.
 */
class PlatformClassifierConflictResolver {

    private static final String NO_CLASSIFIER = "";

    private PlatformClassifierConflictResolver() {
    }

    /**
     * @param enforcedPlatformDependencies the platform BOMs applied via {@code enforcedPlatform(...)},
     *        in declared order. If more than one enforced BOM declares conflicting classifier-scoped
     *        versions for the same {@code group:artifact}, the first BOM in this list wins, matching
     *        both Maven's own first-imported-BOM {@code dependencyManagement} precedence and the
     *        existing first-seen-wins convention used elsewhere for platform constraints in this
     *        package.
     * @return canonical (no-classifier) version per {@code group:artifact}, but only for artifacts
     *         whose classifier-scoped versions actually diverge. Artifacts with a classifier split
     *         but no no-classifier entry to fall back on are omitted: there is no safe canonical
     *         choice, so resolution is left to fail as it did before this class existed.
     */
    static Map<ArtifactKey, String> resolve(Project project, List<Dependency> enforcedPlatformDependencies) {
        if (enforcedPlatformDependencies.isEmpty()) {
            return Map.of();
        }

        final GradleAssistedMavenModelResolverImpl mavenModelResolver = new GradleAssistedMavenModelResolverImpl(project);
        final ModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
        final Logger logger = project.getLogger();

        final Map<ArtifactKey, String> overrides = new HashMap<>();
        for (Dependency platformDep : enforcedPlatformDependencies) {
            final String groupId = platformDep.getGroup();
            final String artifactId = platformDep.getName();
            final String version = platformDep.getVersion();
            if (groupId == null || version == null) {
                continue;
            }

            final Model effectiveModel;
            try {
                var modelSource = mavenModelResolver.resolveModel(groupId, artifactId, version);
                var request = new DefaultModelBuildingRequest();
                request.setModelSource(modelSource);
                request.setModelResolver(mavenModelResolver);
                request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
                effectiveModel = modelBuilder.build(request).getEffectiveModel();
            } catch (UnresolvableModelException | ModelBuildingException e) {
                logger.debug("Unable to resolve effective model for platform BOM {}:{}:{} while checking for "
                        + "classifier-scoped dependencyManagement conflicts: {}", groupId, artifactId, version,
                        e.getMessage());
                continue;
            }

            if (effectiveModel.getDependencyManagement() == null) {
                continue;
            }

            // group:artifact -> classifier ("" for none) -> version, in declaration order
            final Map<ArtifactKey, Map<String, String>> versionsByClassifier = new LinkedHashMap<>();
            for (var managedDep : effectiveModel.getDependencyManagement().getDependencies()) {
                final ArtifactKey key = ArtifactKey.ga(managedDep.getGroupId(), managedDep.getArtifactId());
                final String classifier = managedDep.getClassifier() == null ? NO_CLASSIFIER : managedDep.getClassifier();
                versionsByClassifier.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .putIfAbsent(classifier, managedDep.getVersion());
            }

            for (var entry : versionsByClassifier.entrySet()) {
                final ArtifactKey key = entry.getKey();
                if (overrides.containsKey(key)) {
                    // an earlier (higher-precedence) enforced platform BOM already resolved this GA
                    continue;
                }
                final Map<String, String> byClassifier = entry.getValue();
                final String canonicalVersion = byClassifier.get(NO_CLASSIFIER);
                final boolean diverges = byClassifier.values().stream().distinct().count() > 1;
                if (diverges && canonicalVersion != null) {
                    overrides.put(key, canonicalVersion);
                } else if (diverges) {
                    logger.debug("Platform BOM {}:{}:{} declares {} at diverging versions across classifiers "
                            + "with no no-classifier entry to use as a canonical version; leaving Gradle's "
                            + "default (potentially failing) resolution behavior in place for it",
                            groupId, artifactId, version, key);
                }
            }
        }
        return Map.copyOf(overrides);
    }

    /**
     * Registers a {@code resolutionStrategy.eachDependency} rule on {@code configuration} that forces
     * resolution to the canonical (no-classifier) version wherever the enforced platform BOM declares
     * classifier-diverging versions for the same {@code group:artifact} - working around the fact that
     * Gradle's Maven-BOM-import mechanism discards classifier information and would otherwise fail such
     * artifacts with a {@code ConflictingConstraintsException}.
     */
    static void applyOverrides(Configuration configuration, Provider<PlatformSpec> platformSpecProvider) {
        configuration.getResolutionStrategy().eachDependency(details -> {
            final Map<ArtifactKey, String> overrides = platformSpecProvider.get().getClassifierConflictOverrides();
            final ArtifactKey key = ArtifactKey.ga(details.getTarget().getGroup(), details.getTarget().getName());
            final String forcedVersion = overrides.get(key);
            if (forcedVersion != null && !forcedVersion.equals(details.getTarget().getVersion())) {
                details.useVersion(forcedVersion);
                details.because(
                        "Quarkus: the platform BOM declares " + key + " at diverging versions across Maven classifiers,"
                                + " which Gradle's BOM import cannot represent; aligning to the no-classifier version");
            }
        });
    }
}
