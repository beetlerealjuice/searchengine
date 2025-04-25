package searchengine.repository;

import org.springframework.data.repository.CrudRepository;
import searchengine.model.IndexSearch;

import java.util.List;

public interface IndexSearchRepository extends CrudRepository<IndexSearch, Integer> {
    List<IndexSearch> findByPageId(int id);
}
