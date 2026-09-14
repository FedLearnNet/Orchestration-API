package bio.cosy.featurecloud.orchestration.api.orchestration.container;

import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.StartWorkflowNodeDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

@ApplicationScoped
public class ContainerRunAO implements PanacheRepository<ContainerRunEntity> {


    @Transactional
    public ContainerRunEntity create(StartAppDTO createDTO) {
        ContainerRunEntity containerRunEntity = new ContainerRunEntity();
        containerRunEntity.setContainerName(createDTO.getContainerName());
        containerRunEntity.setAppId(createDTO.getIdentifier());
        containerRunEntity.setAppImage(createDTO.getAppImage());
        containerRunEntity.setWorkflowId(createDTO.getGroupId());
        containerRunEntity.setStatus(ContainerRunStatus.INIT);
        persist(containerRunEntity);
        return containerRunEntity;
    }

    @Transactional
    public ContainerRunEntity create(StartWorkflowNodeDTO createDTO) {
        ContainerRunEntity containerRunEntity = new ContainerRunEntity();
        containerRunEntity.setContainerName(createDTO.getContainerName());
        containerRunEntity.setAppId(createDTO.getWorkflowNodeId());
        containerRunEntity.setAppImage(createDTO.getAppImage());
        containerRunEntity.setWorkflowId(createDTO.getWorkflowId());
        containerRunEntity.setStatus(ContainerRunStatus.INIT);
        persist(containerRunEntity);
        return containerRunEntity;
    }


    @Transactional
    public void updateStatus(Long id, String containerId, ContainerRunStatus status) {
        ContainerRunEntity run = findByIdOptional(id)
                .orElseThrow(() -> new NotFoundException("Container run not found"));
        run.setContainerId(containerId);
        run.setStatus(status);
        persist(run);
    }
}
