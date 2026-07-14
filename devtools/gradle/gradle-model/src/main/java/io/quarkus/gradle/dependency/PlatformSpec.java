package io.quarkus.gradle.dependency;

import java.util.Map;
import java.util.Set;

import org.gradle.api.artifacts.ExcludeRule;

import io.quarkus.maven.dependency.ArtifactKey;

class PlatformSpec {
    private final Map<ArtifactKey, Constraint> constraints;
    private final Set<ExcludeRule> exclusions;
    /**
     * Canonical (no-classifier) version per {@code group:artifact}, for artifacts whose platform BOM
     * declares diverging versions across Maven classifiers. Gradle's BOM import is classifier-blind,
     * so without an explicit override such artifacts can fail to resolve with a
     * {@code ConflictingConstraintsException} even though the underlying BOM is valid Maven.
     *
     * @see PlatformClassifierConflictResolver
     */
    private final Map<ArtifactKey, String> classifierConflictOverrides;

    public PlatformSpec(Map<ArtifactKey, Constraint> constraints, Set<ExcludeRule> exclusions,
            Map<ArtifactKey, String> classifierConflictOverrides) {
        this.constraints = constraints;
        this.exclusions = exclusions;
        this.classifierConflictOverrides = classifierConflictOverrides;
    }

    public Map<ArtifactKey, Constraint> getConstraints() {
        return constraints;
    }

    public Set<ExcludeRule> getExclusions() {
        return exclusions;
    }

    public Map<ArtifactKey, String> getClassifierConflictOverrides() {
        return classifierConflictOverrides;
    }

    static class Constraint {
        private final String groupId;
        private final String artifactId;
        private final String version;

        public Constraint(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getVersion() {
            return version;
        }
    }
}