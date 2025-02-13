package searchengine.services;

import searchengine.dto.statistics.IndexingResponse;

public interface IndexingService {
    IndexingResponse startIndexing();

    IndexingResponse stopIndexing();
}
