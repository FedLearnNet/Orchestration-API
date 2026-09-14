package bio.cosy.featurecloud.orchestration.base;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public abstract class BaseDTO {


    @PositiveOrZero(message = "Id has to be positive")
    private Long id;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private Date createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private Date updatedAt;

    @PositiveOrZero(message = "Id has to be positive")
    private Long version = 0L;
}
