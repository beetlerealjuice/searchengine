package searchengine.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import searchengine.dto.SearchResponse;
import searchengine.model.Site;
import searchengine.repository.IndexSearchRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.utils.LemmaFinder;
import searchengine.utils.LemmaFinderEn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Service
public class SearchServiceImpl implements SearchService {

    private final SiteRepository siteRepository;

    private final PageRepository pageRepository;

    private final LemmaRepository lemmaRepository;

    private final IndexSearchRepository indexSearchRepository;

    private final EntityManager entityManager;

    private static final double REPETITION_PERCENTAGE = 0.7;

    @Override
    @SneakyThrows
    public SearchResponse search(String query, String site, Integer offset, Integer limit) {

        if (query.isEmpty()) {
            return getErrorSearchResponse("Задан пустой поисковый запрос");
        }

        LemmaFinder lemmaFinder = LemmaFinder.getInstance();
        LemmaFinderEn lemmaFinderEn = LemmaFinderEn.getInstance();
        Set<String> lemmasFromQuery;

        if (isRussian(query)) {
            lemmasFromQuery = lemmaFinder.getLemmaSet(query);
        } else {
            lemmasFromQuery = lemmaFinderEn.getLemmaSet(query);
        }
        System.out.println("Stop");
        filterLemmas(lemmasFromQuery, site);

        System.out.println("Stop");

        System.out.println(lemmasFromQuery);

        return null;
    }

    private boolean isRussian(String query) {
        String regex = "[А-яЁё0-9\\s.,?!:;\"'()\\-–—]+";
        return query.matches(regex);
    }

    private SearchResponse getErrorSearchResponse(String error) {
        return SearchResponse.builder()
                .result(false)
                .count(null)
                .error(error)
                .build();
    }

    public void filterLemmas(Set<String> lemmasFromQuery, String website) {

        Set<String> lemmasToRemove = new HashSet<>();

        for (String lemma : lemmasFromQuery) {
            String sql =
                    "SELECT " +
                    " l.lemma, " +
                    " p.site_id, " +
                    " COUNT(*) AS repetition_count, " +
                    " (SELECT COUNT(*) FROM page WHERE site_id = p.site_id) AS total_pages_on_site " +
                    "FROM " +
                    " index_search AS isa " +
                    "JOIN " +
                    " page p ON isa.page_id = p.id " +
                    "JOIN " +
                    " lemma l ON isa.lemma_id = l.id " +
                    "WHERE " +
                    " l.lemma = :lemma " +
                    (website != null ? "AND p.site_id = :siteId " : "") +
                    "GROUP BY " +
                    " p.site_id, l.lemma";

            Query query = entityManager.createNativeQuery(sql);
            query.setParameter("lemma", lemma);

            // Если поиск по заданному сайту, устанавливаем параметр siteId
            if (website != null) {
                query.setParameter("siteId", siteRepository.findByUrl(website).getId());
            }

            List<Object[]> results = query.getResultList();

            for (Object[] result : results) {
                String foundLemma = (String) result[0];
                Number repetitionCount = (Number) result[2];
                Number totalPagesOnSite = (Number) result[3];

                if (repetitionCount != null && totalPagesOnSite != null) {
                    double repetitionPercentage = repetitionCount.doubleValue() / totalPagesOnSite.doubleValue();

                    if (repetitionPercentage > REPETITION_PERCENTAGE) {
                        lemmasToRemove.add(foundLemma);
                    }
                }
            }
        }

        lemmasFromQuery.removeAll(lemmasToRemove);
    }
}
