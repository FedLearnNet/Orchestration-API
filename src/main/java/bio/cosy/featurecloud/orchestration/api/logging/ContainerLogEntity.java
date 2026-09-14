package bio.cosy.featurecloud.orchestration.api.logging;

import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunEntity;
import bio.cosy.featurecloud.orchestration.base.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Entity
@Table(name = "container_logs")
public class ContainerLogEntity extends BaseEntity {

    @Column(name = "log", nullable = false, columnDefinition = "TEXT")
    private String log;

    @ManyToOne(cascade = CascadeType.REFRESH)
    @JoinColumn(name = "container_run_id")
    private ContainerRunEntity containerRun;

}
