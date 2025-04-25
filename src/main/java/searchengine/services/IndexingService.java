package searchengine.services;

import searchengine.dto.Response;

public interface IndexingService {
    Response startIndexing();

    Response stopIndexing();

    Response indexPage(String url);
}
