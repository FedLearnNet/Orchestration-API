package bio.cosy.featurecloud.orchestration.api.info;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class InfoServiceImpl implements InfoService {

    @Inject
    SystemComputingInfoBO systemComputingInfoBO;


    @Override
    public SystemComputingInfoDTO getInfo() {
        return systemComputingInfoBO.get();
    }
}
