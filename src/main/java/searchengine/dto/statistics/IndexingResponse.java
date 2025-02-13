package searchengine.dto.statistics;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IndexingResponse {
    private boolean result;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
}
