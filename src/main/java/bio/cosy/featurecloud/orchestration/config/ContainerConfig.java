package bio.cosy.featurecloud.orchestration.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "container")
public interface ContainerConfig {

    @WithName("auto-stop")
    @WithDefault("true")
    boolean autoStop();

    Optional<String> name();

    Host host();

    Memory memory();

    Cpu cpu();

    Enable enable();

    Network network();

    @WithName("log-container-logs")
    @WithDefault("true")
    Boolean logContainerLogs();

    @WithName("file-transfer-volume")
    FileTransferVolume fileTransferVolume();

    @WithName("file-transfer")
    FileTransfer fileTransfer();

    interface Host {

        @WithName("override")
        Optional<String> override();
    }

    interface Memory {
        @WithName("limit")
        Optional<Long> limit();

        @WithName("swap")
        Optional<Long> swap();
    }

    interface Cpu {
        @WithName("shares")
        Optional<Integer> shares();
    }

    interface Enable {
        @WithName("autokill")
        Optional<Boolean> autokill();

        @WithName("oomkill-disable")
        Optional<Boolean> oomkillDisable();
    }

    interface Network {

        //Pre-existing docker networks every started container is attached to.
        @WithName("names")
        Optional<List<String>> names();


        //Learning API container attached to the dedicated network created for every started
        // container. Required format: <container>:<port>, e.g. local-learning-api:8080.
        @WithName("learning")
        Optional<String> learning();


        // only attached when the started container asked for
        // federated learning access. Required format: <container>:<port>, e.g. controller:8001.
        @WithName("controller")
        Optional<String> controller();

        // Attach containers to the configured non-internal networks even when they were started
        //without internet access. Only meant for local/dev setups.
        @WithName("always-bridge")
        @WithDefault("false")
        boolean alwaysBridge();
    }

    interface FileTransferVolume {
        @WithName("name")
        Optional<String> name();

        @WithName("path")
        Optional<String> path();
    }

    interface FileTransfer {
        @WithName("grant-users")
        Optional<List<String>> grantUsers();

        @WithName("grant-group")
        Optional<List<String>> grantGroup();
    }
}
