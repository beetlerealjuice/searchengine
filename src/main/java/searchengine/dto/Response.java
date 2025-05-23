package searchengine.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Response {
    private boolean result;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
}
