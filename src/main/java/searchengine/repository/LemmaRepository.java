package searchengine.repository;

import org.springframework.data.repository.CrudRepository;
import searchengine.model.Lemma;

import java.util.Optional;

public interface LemmaRepository extends CrudRepository<Lemma, Integer> {
    Optional<Lemma> findByLemma(String lemma);
}
