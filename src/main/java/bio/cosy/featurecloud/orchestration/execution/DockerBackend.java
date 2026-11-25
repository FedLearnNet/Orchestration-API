package bio.cosy.featurecloud.orchestration.execution;

import jakarta.inject.Qualifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the Docker-backed implementation of {@link AppExecutionService}/
 * {@link VolumeExecutionService} so it isn't also discovered as an
 * unqualified/{@code @Default} bean of that type — only the producers
 * ({@link AppExecutionServiceProducer}, {@link VolumeExecutionServiceProducer})
 * should be resolvable that way.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface DockerBackend {
}
