package searchengine.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import searchengine.dto.SearchResponse;
import searchengine.model.Lemma;
import searchengine.model.Page;
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

        // Определяем язык запроса и получаем набор лемм
        LemmaFinder lemmaFinder = LemmaFinder.getInstance();
        LemmaFinderEn lemmaFinderEn = LemmaFinderEn.getInstance();
        Set<String> lemmasFromQuery = isRussian(query)
                ? lemmaFinder.getLemmaSet(query)
                : lemmaFinderEn.getLemmaSet(query);

        // Фильтруем леммы по принадлежности к сайту и по порогу повторяемости
        filterLemmas(lemmasFromQuery, site);

        if (lemmasFromQuery.isEmpty()) {
            return SearchResponse.builder()
                    .result(true)
                    .count(0)
                    .data(Collections.emptyList())
                    .build();
        }

        // Сортируем леммы по возрастанию частоты встречаемости
        List<String> sortedLemmas = getSortedLemmasByFrequencyAsc(lemmasFromQuery);

        // Определяем siteId, если задан сайт
        Integer siteId = (site != null) ? siteRepository.findByUrl(site).getId() : null;

        // Для первой (самой редкой) леммы находим все id страниц, на которых она встречается
        Set<Integer> resultPageIds = getPageIdsByLemma(sortedLemmas.get(0), siteId);

        // Для каждой следующей леммы пересекаем найденный набор страниц с новыми
        for (int i = 1; i < sortedLemmas.size(); i++) {
            Set<Integer> lemmaPageIds = getPageIdsByLemma(sortedLemmas.get(i), siteId);
            resultPageIds.retainAll(lemmaPageIds);
            if (resultPageIds.isEmpty()) {
                break;
            }
        }

        // Если после пересечений страниц не осталось, возвращаем пустой список
        if (resultPageIds.isEmpty()) {
            return SearchResponse.builder()
                    .result(true)
                    .count(0)
                    .data(Collections.emptyList())
                    .build();
        }

        // Загружаем объекты Page по найденным идентификаторам
        List<Page> pages = pageRepository.findAllByIdIn(resultPageIds);


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

    private Set<Integer> getPageIdsByLemma(String lemma, Integer siteId) {
        String sql = "SELECT p.id " +
                "FROM index_search AS isa " +
                "JOIN page p ON isa.page_id = p.id " +
                "JOIN lemma l ON isa.lemma_id = l.id " +
                "WHERE l.lemma = :lemma " +
                (siteId != null ? "AND p.site_id = :siteId" : "");

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("lemma", lemma);
        if (siteId != null) {
            query.setParameter("siteId", siteId);
        }
        List<?> results = query.getResultList();
        return results.stream()
                .map(r -> ((Number) r).intValue())
                .collect(Collectors.toSet());
    }
}
