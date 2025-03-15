package searchengine.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import searchengine.model.Lemma;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LemmaRepository extends CrudRepository<Lemma, Integer> {
    Optional<Lemma> findFirstByLemma(String lemma);

    @Query("SELECT l FROM Lemma l WHERE l.lemma IN :lemmas ORDER BY l.frequency ASC")
    List<Lemma> findAndSortByFrequencyAsc(Set<String> lemmas);
}
