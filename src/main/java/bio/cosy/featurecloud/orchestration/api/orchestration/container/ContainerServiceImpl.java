package bio.cosy.featurecloud.orchestration.api.orchestration.container;

import bio.cosy.featurecloud.orchestration.api.logging.ContainerLogAO;
import bio.cosy.featurecloud.orchestration.api.logging.ContainerLogDTO;
import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.helper.ContainerNetworkEnv;
import bio.cosy.featurecloud.orchestration.service.DockerAppService;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;

import java.util.List;

@ApplicationScoped
public class ContainerServiceImpl implements ContainerService {

    public static final String LOG_CHANNEL = "LOG_CHANNEL";


    @Inject
    DockerAppService dockerAppService;

    @Inject
    ContainerAppBO appBO;

    @Inject
    ContainerPipelineBO pipelineBO;

    @Inject
    ContainerLogAO containerLogAO;

    @Inject
    HttpServerRequest request;

    @Inject
    ContainerRunAO containerRunAO;

    @Inject
    @Channel(LOG_CHANNEL)
    Multi<ContainerLogDTO> rawLogs;

    @Inject
    ContainerConfig containerConfig;

    @Inject
    ContainerNetworkEnv containerNetworkEnv;

    @Override
    public List<Container> list() {
        return dockerAppService.showRunning();
    }

    @Override
    public Container get(String id) {
        return dockerAppService.getById(id);
    }

    @Override
    public Multi<String> getLogsStream(String id) {
        Uni<List<ContainerLogDTO>> savedLogsUni = Uni.createFrom()
                .item(() -> containerLogAO.findByContainerRun(id))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());

        Multi<String> savedLogsMulti = savedLogsUni
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem().transformToMulti(logs -> Multi.createFrom().iterable(logs))
                .onItem().transform(ContainerLogDTO::getLog);

        Multi<String> liveLogsMulti = rawLogs
                .filter(log -> id.equals(log.getContainerId()))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem().transform(ContainerLogDTO::getLog);

        return Multi.createBy()
                .concatenating()
                .streams(savedLogsMulti, liveLogsMulti);


    }

    @Override
    @Transactional
    public List<ContainerRunDTO> listRuns() {
        return containerRunAO.findAll().project(ContainerRunDTO.class).list();
    }

    @Override
    @Transactional
    public ContainerRunDTO getRun(Long id) {
        return containerRunAO.find("id", id)
                .project(ContainerRunDTO.class)
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException("Container run not found"));
    }

    @Override
    @Transactional
    public List<ContainerLogDTO> getRunLogs(Long id) {
        return containerLogAO.findByContainerRun(id);
    }


    @Override
    public CreateContainerResponse startContainer(StartAppDTO createDTO, boolean changeUrl, boolean sendConsoleLog, String path, Integer port) {
        String host = containerConfig.host().override().orElse(request.remoteAddress().host());
        if (changeUrl) {
            containerNetworkEnv.getNetworkEnvVariables(port, path, host,
                            false)
                    .forEach(createDTO::addEnvironment);
            createDTO.addEnvironment("SEND_CONSOLE_LOG=" + Boolean.toString(sendConsoleLog));
            createDTO.addEnvironment("DEV_MODE=false");
        }
        return appBO.startContainer(createDTO);
    }

    @Override
    public CreateContainerResponse startPipeline(StartPipelineDTO createDTO, boolean changeUrl, String path, Integer port) {
        String host = containerConfig.host().override().orElse(request.remoteAddress().host());
        if (changeUrl) {
            createDTO.addEnvironment(containerNetworkEnv.getNetworkEnvVariablesPipeline(port, path, host));
        }
        return pipelineBO.startPipeline(createDTO);
    }

    @Override
    public Response cleanupWorkflow(String containerId, boolean cleanup) {
        try {
            appBO.stopContainer(containerId, cleanup);
            return Response.ok().build();
        } catch (NotFoundException e) {
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    @Override
    public List<Container> listFeatureCloud() {
        return dockerAppService.showFeatureCloudContainers();
    }

    @Override
    public Response cleanupContainers(List<String> containerIds, boolean cleanup) {
        try {
            appBO.stopContainers(containerIds, cleanup);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }
}
