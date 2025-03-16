package searchengine.repository;

import org.springframework.data.repository.CrudRepository;
import searchengine.model.Page;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PageRepository extends CrudRepository<Page, Integer> {
    Optional<Page> findByPath(String path);

    List<Page> findAllByIdIn(Set<Integer> ids);
}
