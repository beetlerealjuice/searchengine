package searchengine.repository;

import org.springframework.data.repository.CrudRepository;
import searchengine.model.IndexSearch;

public interface IndexSearchRepository extends CrudRepository<IndexSearch, Integer> {
}
