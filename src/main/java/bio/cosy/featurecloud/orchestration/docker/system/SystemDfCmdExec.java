package bio.cosy.featurecloud.orchestration.docker.system;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.MediaType;
import com.github.dockerjava.core.WebTarget;
import com.github.dockerjava.core.exec.AbstrSyncDockerCmdExec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemDfCmdExec extends AbstrSyncDockerCmdExec<SystemDfCmd, DiskUsage> implements
        SystemDfCmd.Exec {


    private static final Logger LOGGER = LoggerFactory.getLogger(SystemDfCmdExec.class);

    public SystemDfCmdExec(WebTarget baseResource, DockerClientConfig dockerClientConfig) {
        super(baseResource, dockerClientConfig);
    }

    @Override
    protected DiskUsage execute(SystemDfCmd command) {
        WebTarget webResource = getBaseResource().path("/system/df");

        LOGGER.trace("GET: {}", webResource);
        return webResource.request().accept(MediaType.APPLICATION_JSON).get(new TypeReference<DiskUsage>() {
        });
    }
}
