package bio.cosy.featurecloud.orchestration.api.orchestration.workflow;

import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;
import bio.cosy.featurecloud.orchestration.helper.ContainerNetworkEnv;
import com.github.dockerjava.api.command.CreateContainerResponse;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;

import static bio.cosy.featurecloud.orchestration.service.DockerVolumeService.VOLUME_PATH;

@ApplicationScoped
public class WorkflowServiceImpl implements WorkflowService {

    @Inject
    WorkflowNodeBO workflowNodeBO;

    @Inject
    HttpServerRequest request;

    @Inject
    ContainerConfig containerConfig;

    @Inject
    ContainerNetworkEnv containerNetworkEnv;

    @Override
    public CreateContainerResponse startWorkflow(StartWorkflowNodeDTO createDTO, boolean changeUrl, String path, Integer port) {
        String host = containerConfig.host().override().orElse(request.remoteAddress().host());
        if (changeUrl) {

            containerNetworkEnv.getNetworkEnvVariables(port, path, host,
                            createDTO.getNeedsFederatedLearningAccess())
                    .forEach(createDTO::addEnvironment);
            createDTO.addEnvironment("DATA_DIR=" + VOLUME_PATH.get(DockerVolumeType.INPUT));
            createDTO.addEnvironment("ENABLE_LOCAL_RESULT_SAVING=true");
            if (createDTO.getEnableRemoteResultSaving()) {
                createDTO.addEnvironment("ENABLE_REMOTE_RESULT_SAVING=true");
            } else {
                createDTO.addEnvironment("ENABLE_REMOTE_RESULT_SAVING=false");
            }
            createDTO.addEnvironment("OUTPUT_DIR=" + VOLUME_PATH.get(DockerVolumeType.OUTPUT));
            createDTO.addEnvironment("SEND_CONSOLE_LOG=true");
            createDTO.addEnvironment("DEV_MODE=false");
        }
        return workflowNodeBO.startContainer(createDTO);
    }

    @Override
    public Response cleanupWorkflow(String containerId, boolean cleanup, boolean runInBackground) {
        try {
            if (runInBackground) {
                Uni.createFrom().voidItem()
                        .invoke(() -> workflowNodeBO.stopContainer(containerId, cleanup))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .subscribe().with(
                                ignored -> {
                                },
                                failure -> Log.error("Background cleanup failed", failure)
                        );
                return Response.accepted().build();
            }
            workflowNodeBO.stopContainer(containerId, cleanup);
        } catch (Exception e) {
            Log.error("Cleanup workflow failed", e);
        }
        return Response.ok().build();
    }

    @Override
    public Response cleanupWorkflowStep(String appId, boolean cleanup, boolean runInBackground) {
        try {
            if (runInBackground) {
                Uni.createFrom().voidItem()
                        .invoke(() -> workflowNodeBO.stopContainerStep(appId, cleanup))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .subscribe().with(
                                ignored -> {
                                },
                                failure -> Log.error("Background cleanup failed", failure)
                        );
                return Response.accepted().build();
            }
            workflowNodeBO.stopContainerStep(appId, cleanup);
        } catch (Exception e) {
            Log.error("Cleanup workflow failed", e);
        }
        return Response.ok().build();
    }
}
