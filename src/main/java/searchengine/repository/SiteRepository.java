package searchengine.repository;

import org.springframework.data.repository.CrudRepository;
import searchengine.model.Site;

public interface SiteRepository extends CrudRepository<Site, Integer> {

}
