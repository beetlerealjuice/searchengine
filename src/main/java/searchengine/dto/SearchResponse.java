package searchengine.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import searchengine.model.SearchData;

import java.util.List;

@Data
@Builder
public class SearchResponse {
    private boolean result;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer count;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SearchData> data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
}
