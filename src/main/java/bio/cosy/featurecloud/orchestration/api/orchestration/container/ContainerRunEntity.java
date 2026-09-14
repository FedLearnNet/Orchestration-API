package bio.cosy.featurecloud.orchestration.api.orchestration.container;

import bio.cosy.featurecloud.orchestration.api.logging.ContainerLogEntity;
import bio.cosy.featurecloud.orchestration.base.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Setter
@Getter
@Entity
@Table(name = "container_runs")
public class ContainerRunEntity extends BaseEntity {

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "app_image", nullable = false)
    private String appImage;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "container_name", nullable = false)
    private String containerName;

    @Column(name = "container_id")
    private String containerId;

    @Enumerated(EnumType.STRING)
    private ContainerRunStatus status;

    @OneToMany(mappedBy = "containerRun", cascade = CascadeType.ALL)
    private Set<ContainerLogEntity> logs;
}
