package bio.cosy.featurecloud.orchestration.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

@ConfigMapping(prefix = "updater")
public interface UpdaterConfig {

    // flnet as an image; the tag is the version to install, as flnet upgrades to its own version
    @WithName("image-prefix")
    @WithDefault("ghcr.io/fedlearnnet/fl-net-cli/flnet")
    String imagePrefix();

    // host path of the deployment, written by flnet init; empty when the deployment does not support updates yet
    @WithName("deployment-dir")
    Optional<String> deploymentDir();

    // updates/state.json of the deployment, mounted read-only
    @WithName("state-file")
    @WithDefault("/flnet/updates/state.json")
    String stateFile();

    // backups/ of the deployment, mounted read-only
    @WithName("backups-dir")
    @WithDefault("/flnet/backups")
    String backupsDir();
}
