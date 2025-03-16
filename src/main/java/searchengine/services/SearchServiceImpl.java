package searchengine.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import searchengine.dto.SearchResponse;
import searchengine.model.Lemma;
import searchengine.model.Site;
import searchengine.repository.IndexSearchRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.utils.LemmaFinder;
import searchengine.utils.LemmaFinderEn;

import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class SearchServiceImpl implements SearchService {

    private final SiteRepository siteRepository;

    private final PageRepository pageRepository;

    private final LemmaRepository lemmaRepository;

    private final IndexSearchRepository indexSearchRepository;

    private final EntityManager entityManager;

    private static final double REPETITION_PERCENTAGE = 0.75;

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

        filterLemmas(lemmasFromQuery, site);

        List<String> sortedLemmas = getSortedLemmasByFrequencyAsc(lemmasFromQuery);

        System.out.println("Stop");



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

    // Фильтруем леммы по принадлежности к заданному сайту и встречающиеся более чем на 75% страниц
    public void filterLemmas(Set<String> lemmasFromQuery, String website) {
        Set<String> lemmasToRemove = new HashSet<>();
        Integer siteId = (website != null) ? siteRepository.findByUrl(website).getId() : null;

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

            if (siteId != null) {
                query.setParameter("siteId", siteId);
            }

            List<Object[]> results = query.getResultList();

            // Удаляем лемму, если ее нет в результатах запроса
            if (results.isEmpty()) {
                lemmasToRemove.add(lemma);
                continue;
            }

            for (Object[] result : results) {
                Integer foundSiteId = (Integer) result[1];
                System.out.println("foundSiteId: " + foundSiteId);
                // Удаляем лемму, если она относится к другому siteId
                if (siteId != null && !siteId.equals(foundSiteId)) {
                    lemmasToRemove.add(lemma);
                    continue;
                }

                Number repetitionCount = (Number) result[2];
                System.out.println("repetitionCount: " + repetitionCount);
                Number totalPagesOnSite = (Number) result[3];
                System.out.println("totalPagesOnSite: " + totalPagesOnSite);

                if (repetitionCount != null && totalPagesOnSite != null) {
                    double repetitionPercentage = repetitionCount.doubleValue() / totalPagesOnSite.doubleValue();

                    if (repetitionPercentage > REPETITION_PERCENTAGE) {
                        lemmasToRemove.add(lemma);
                    }
                }
            }
        }

        lemmasFromQuery.removeAll(lemmasToRemove);
    }



    // Сортируем леммы в порядке увеличения частоты встречаемости
    public List<String> getSortedLemmasByFrequencyAsc(Set<String> lemmasFromQuery) {
        return lemmaRepository.findAndSortByFrequencyAsc(lemmasFromQuery)
                .stream()
                .map(Lemma::getLemma)
                .collect(Collectors.toList());
    }
}
