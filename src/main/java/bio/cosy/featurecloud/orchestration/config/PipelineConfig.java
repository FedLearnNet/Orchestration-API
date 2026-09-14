package bio.cosy.featurecloud.orchestration.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "pipeline")
public interface PipelineConfig {

    @WithName("use-buildx")
    @WithDefault("true")
    boolean useBuildx();

    @WithName("buildx-platforms")
    @WithDefault("linux/arm64/v8,linux/amd64")
    List<String> buildxPlatforms();

    @WithName("buildx-sbom")
    @WithDefault("true")
    boolean buildxSbom();

    @WithName("buildx-provenance-mode")
    @WithDefault("max")
    Optional<String> buildxProvenanceMode();

    @WithName("buildx-no-default-oci-artifacts")
    @WithDefault("true")
    Optional<Boolean> buildxNoDefaultOciArtifacts();

    @WithName("cosign-sign")
    @WithDefault("false")
    boolean cosignSign();

    @WithName("docker-login")
    @WithDefault("true")
    boolean dockerLogin();

    @WithName("docker-registry")
    @WithDefault("gitlab.cosy.bio:5050")
    String dockerRegistry();

    @WithName("docker-group")
    @WithDefault("/cosybio/federated-learning/federated_db/app-build-pipeline")
    String dockerGroup();

    @WithName("docker-username")
    @WithDefault("USERNAME")
    String dockerUsername();

    @WithName("docker-password")
    Optional<String> dockerPassword();

    @WithName("repo-token")
    Optional<String> repoToken();

    @WithName("repo-path")
    @WithDefault("./repo")
    String repoPath();

    @WithName("hostRemove")
    @WithDefault("true")
    Boolean hostRemove();
}
