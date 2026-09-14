package bio.cosy.featurecloud.orchestration.docker.system;

import com.github.dockerjava.api.command.DockerCmdSyncExec;
import com.github.dockerjava.api.command.SyncDockerCmd;


public interface SystemDfCmd extends SyncDockerCmd<DiskUsage> {

    public interface Exec extends DockerCmdSyncExec<SystemDfCmd, DiskUsage> {
    }
}