package searchengine.services;

import searchengine.dto.SearchResponse;

public interface SearchService {
    SearchResponse search(String query, String site, Integer offset, Integer limit);
}
