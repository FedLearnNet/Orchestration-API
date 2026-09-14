package bio.cosy.featurecloud.orchestration.helper;

import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Singleton
public class NamingService {

    @Inject
    ContainerConfig containerConfig;

    private static volatile String systemName;

    void onStart(@Observes StartupEvent event) {
        String configured = containerConfig.name().orElse("").trim();
        systemName = resolveName(configured);
        String source = configured.isEmpty() ? "generated UUID"
                : configured.length() > 16 ? "SHA-256 of configured name" : "configured name";
        Log.infof("Naming initialized: container.name='%s', source=%s, system_name=%s. "
                        + "Container names, volume names and ownership labels share this name.",
                configured, source, systemName);
    }

    public static String getSystemName() {
        String name = systemName;
        if (name == null) {
            throw new IllegalStateException("NamingService has not received the application startup event");
        }
        return name;
    }

    static String resolveName(String configured) {
        String name = configured == null ? "" : configured.trim();
        if (name.isEmpty()) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (name.length() <= 16) {
            return name;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to shorten container.name", e);
        }
    }
}
