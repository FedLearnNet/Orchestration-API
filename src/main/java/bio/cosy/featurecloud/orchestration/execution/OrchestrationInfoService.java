package bio.cosy.featurecloud.orchestration.execution;

import bio.cosy.featurecloud.orchestration.api.orchestration.OrchestrationInfoDTO;

/**
 * Backend for {@code GET /docker/info}. See {@link
 * bio.cosy.featurecloud.orchestration.api.orchestration.OrchestrationInfoDTO}
 * for why this doesn't just return docker-java's {@code Info}.
 */
public interface OrchestrationInfoService {

    OrchestrationInfoDTO getInfo();
}
