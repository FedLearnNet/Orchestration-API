package bio.cosy.featurecloud.orchestration.api.logging;

import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunAO;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ContainerLogAO implements PanacheRepository<ContainerLogEntity> {

    @Inject
    ContainerRunAO containerRunAO;


    public List<ContainerLogDTO> findByContainerRun(Long containerRunId) {
        return find("containerRun.id", containerRunId).project(ContainerLogDTO.class).list();
    }

    public List<ContainerLogDTO> findByContainerRun(String containerId) {
        return find("containerRun.containerId", containerId).project(ContainerLogDTO.class).list();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public ContainerLogDTO createLogTransactional(Long containerRunId, String log) {
        Optional<ContainerRunEntity> containerRunEntity = containerRunAO.findByIdOptional(containerRunId);

        if (containerRunEntity.isEmpty()) {
            Log.errorf("ContainerRunEntity with id %d not found, ignore log", containerRunId);
            return null;
        }

        ContainerLogEntity logEntity = new ContainerLogEntity();
        logEntity.setContainerRun(containerRunEntity.get());
        logEntity.setLog(log);
        persist(logEntity);
        return new ContainerLogDTO(
                containerRunEntity.get().getAppId(),
                containerRunEntity.get().getAppImage(),
                containerRunEntity.get().getWorkflowId(),
                containerRunEntity.get().getContainerName(),
                containerRunEntity.get().getContainerId(),
                log
        );
    }
}
