package searchengine.dto.statistics;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
public class StatisticsResponse {
    private boolean result;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private StatisticsData statistics;
}
