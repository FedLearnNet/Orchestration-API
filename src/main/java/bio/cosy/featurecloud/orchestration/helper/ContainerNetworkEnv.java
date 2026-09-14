package bio.cosy.featurecloud.orchestration.helper;

import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ContainerNetworkEnv {

    @Inject
    ContainerConfig containerConfig;

    public String getNetworkEnvVariablesPipeline(Integer port, String path,
                                                 String host) {
        String httpUrl = String.format("HTTP_URL=http://%s:%d/%s/", host, port, path);
        if (StringUtils.isEmpty(path)) {
            httpUrl = String.format("HTTP_URL=http://%s:%d/", host, port);
        }
        if (containerConfig.network().learning().isPresent()) {
            httpUrl = String.format("HTTP_URL=http://%s/%s/", containerConfig.network().learning().get(), path);
            if (StringUtils.isEmpty(path)) {
                httpUrl = String.format("HTTP_URL=http://%s/", containerConfig.network().learning().get());
            }
        }
        return httpUrl;
    }

    public List<String> getNetworkEnvVariables(Integer port, String path,
                                               String host,
                                               boolean needsFederatedLearningAccess
    ) {
        List<String> envVariables = new ArrayList<>();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String wsUrl = String.format("WS_URL=ws://%s:%d/%s/", host, port, path);
        String httpUrl = String.format("HTTP_URL=http://%s:%d/%s/", host, port, path);
        if (StringUtils.isEmpty(path)) {
            wsUrl = String.format("WS_URL=ws://%s:%d/", host, port);
            httpUrl = String.format("HTTP_URL=http://%s:%d/", host, port);
        }
        if (containerConfig.network().learning().isPresent()) {
            wsUrl = String.format("WS_URL=ws://%s/%s/", containerConfig.network().learning().get(), path);
            httpUrl = String.format("HTTP_URL=http://%s/%s/", containerConfig.network().learning().get(), path);
            if (StringUtils.isEmpty(path)) {
                wsUrl = String.format("WS_URL=ws://%s/", containerConfig.network().learning().get());
                httpUrl = String.format("HTTP_URL=http://%s/", containerConfig.network().learning().get());
            }
        }
        if (needsFederatedLearningAccess) {
            envVariables.add(String.format("FL_RUN__CONTROLLER_COMM_URL=http://%s", containerConfig.network().controller().get()));
        }
        envVariables.add(wsUrl);
        envVariables.add(httpUrl);
        return envVariables;
    }
}
