package bio.cosy.featurecloud.orchestration.helper;

import io.quarkus.jackson.JsonMapperBuilderCustomizer;
import jakarta.inject.Singleton;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

// Jackson 3 maps enums via toString, the API uses the enum names (as with Jackson 2)
@Singleton
public class EnumJacksonCustomizer implements JsonMapperBuilderCustomizer {

    @Override
    public void customize(JsonMapper.Builder builder) {
        builder.disable(EnumFeature.READ_ENUMS_USING_TO_STRING)
                .disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING);
    }
}
