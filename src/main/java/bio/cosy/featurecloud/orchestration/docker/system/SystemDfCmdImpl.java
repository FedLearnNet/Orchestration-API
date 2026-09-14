package bio.cosy.featurecloud.orchestration.docker.system;

import com.github.dockerjava.api.command.DockerCmdSyncExec;
import com.github.dockerjava.core.command.AbstrDockerCmd;

public class SystemDfCmdImpl  extends AbstrDockerCmd<SystemDfCmd, DiskUsage> implements SystemDfCmd {
    public SystemDfCmdImpl(DockerCmdSyncExec<SystemDfCmd, DiskUsage> execution) {
        super(execution);
    }
}
