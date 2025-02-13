package searchengine.repository;

import org.springframework.data.repository.CrudRepository;
import searchengine.model.Page;

import java.util.Optional;

public interface PageRepository extends CrudRepository<Page, Integer> {
    Optional<Page> findByPath(String path);
}
