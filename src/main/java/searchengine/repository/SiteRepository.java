package searchengine.repository;

import org.springframework.data.repository.CrudRepository;
import searchengine.model.Site;

import java.util.List;

public interface SiteRepository extends CrudRepository<Site, Integer> {
    List<Site> findAll();

    Site findByUrl(String url);

}
