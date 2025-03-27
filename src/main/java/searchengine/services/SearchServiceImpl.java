package searchengine.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import searchengine.dto.SearchResponse;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.SearchData;
import searchengine.repository.IndexSearchRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.utils.LemmaFinder;
import searchengine.utils.LemmaFinderEn;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        // Получаем леммы из запроса (обрабатываем слова раздельно, независимо от языка)
        Set<String> lemmasFromQuery = getLemmasFromQuery(query);

        // Фильтруем леммы по принадлежности к сайту и порогу повторяемости
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

        // Получаем множество слов (из страниц), соответствующих списку искомых лемм
        Set<String> matchingWords = extractMatchingWords(pages, sortedLemmas);

        // Получаем сниппеты
        Map<String, Integer> snippets = getSnippet(pages, matchingWords);

        List<SearchData> searchDataList = getSearchData(snippets, pageRepository);

        return SearchResponse.builder()
                .result(true)
                .count(searchDataList.size())
                .data(searchDataList)
                .build();

        // todo: реализация пагинации и формирование окончательного ответа


    }

    private List<SearchData> getSearchData(Map<String, Integer> snippets, PageRepository pages) {
        List<SearchData> searchDataList = new ArrayList<>();

        for (Map.Entry<String, Integer> snippetEntry : snippets.entrySet()) {

            Integer pageId = snippetEntry.getValue();
            Page page = pages.findById(pageId).get();

            SearchData searchData = new SearchData();
            searchData.setSite(page.getSite().getUrl());
            searchData.setSiteName(page.getSite().getName());
            Document document = Jsoup.parse(page.getContent());
            String title = document.title();
            searchData.setTitle(title);
            searchData.setUri(page.getPath());
            searchData.setSnippet(snippetEntry.getKey());
            searchDataList.add(searchData);

        }
        System.out.println("Stop");
        return searchDataList;
    }

    // Метод для получения лемм из запроса (обрабатываем каждое слово отдельно)
    private Set<String> getLemmasFromQuery(String query) {
        Set<String> resultLemmas = new HashSet<>();
        // Разбиваем запрос на слова
        String[] words = query.split("\\s+");

        LemmaFinder russianMorph;
        LemmaFinderEn englishMorph;
        try {
            russianMorph = LemmaFinder.getInstance();
            englishMorph = LemmaFinderEn.getInstance();
        } catch (IOException e) {
            throw new RuntimeException("Ошибка создания экземпляров морфологических анализаторов", e);
        }

        for (String word : words) {
            String normalizedWord = normalizeWord(word);
            if (normalizedWord.isEmpty()) {
                continue;
            }
            // Получаем леммы для слова с учетом языка
            resultLemmas.addAll(getLemmasForWord(normalizedWord, russianMorph, englishMorph));
        }
        return resultLemmas;
    }

    // Метод для получения множества лемм для одного слова
    private Set<String> getLemmasForWord(String normalizedWord, LemmaFinder russianMorph, LemmaFinderEn englishMorph) {
        Set<String> lemmas = new HashSet<>();
        if (isRussian(normalizedWord)) {
            lemmas.addAll(russianMorph.getLemmaSet(normalizedWord));
        } else if (isEnglish(normalizedWord)) {
            lemmas.addAll(englishMorph.getLemmaSet(normalizedWord));
        } else {
            // Если слово содержит смешанные символы, пробуем оба варианта
            lemmas.addAll(russianMorph.getLemmaSet(normalizedWord));
            lemmas.addAll(englishMorph.getLemmaSet(normalizedWord));
        }
        return lemmas;
    }

    // Метод для извлечения слов из страниц, соответствующих искомым леммам
    private Set<String> extractMatchingWords(List<Page> pages, List<String> sortedLemmas) {
        Set<String> resultSet = new HashSet<>();
        LemmaFinder russianMorph;
        LemmaFinderEn englishMorph;
        try {
            russianMorph = LemmaFinder.getInstance();
            englishMorph = LemmaFinderEn.getInstance();
        } catch (IOException e) {
            throw new RuntimeException("Ошибка создания экземпляров морфологического анализатора", e);
        }

        for (Page page : pages) {
            String plainText = page.getContent().replaceAll("<[^>]*>", " ");
            String[] words = plainText.split("\\s+");

            for (String word : words) {
                String normalizedWord = normalizeWord(word);
                if (normalizedWord.isEmpty()) {
                    continue;
                }
                Set<String> wordLemmas = getLemmasForWord(normalizedWord, russianMorph, englishMorph);
                for (String lemma : wordLemmas) {
                    if (sortedLemmas.contains(lemma)) {
                        resultSet.add(normalizedWord);
                        break;
                    }
                }
            }
        }
        return resultSet;
    }

    // Вспомогательный метод для нормализации слова (оставляем только буквы, переводим в нижний регистр)
    private String normalizeWord(String word) {
        return word.toLowerCase().replaceAll("[^а-яёa-z]", "");
    }

    // Вспомогательный метод для определения, состоит ли слово только из русских букв
    private boolean isRussian(String word) {
        return word.matches("[а-яё]+");
    }

    // Вспомогательный метод для определения, состоит ли слово только из английских букв
    private boolean isEnglish(String word) {
        return word.matches("[a-z]+");
    }

    // Метод для формирования сниппетов по страницам (выделение найденных лемм)
    public static Map<String, Integer> getSnippet(List<Page> pages, Set<String> matchingWords) {
        Map<String, Integer> snippets = new HashMap<>();
        String regex = "\\b(" + String.join("|", matchingWords) + ")\\b";
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

        for (Page page : pages) {
            String[] lines = page.getContent().split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = pattern.matcher(lines[i]);
                if (matcher.find()) {
                    String highlightedLine = matcher.replaceAll("<b>$1</b>");
                    StringBuilder snippet = getStringBuilder(i, lines, highlightedLine);
                    snippets.put(snippet.toString().trim(), page.getId());
                }
            }
        }
        return snippets;
    }

    private static StringBuilder getStringBuilder(int i, String[] lines, String highlightedLine) {
        int start = Math.max(0, i - 1);
        int end = Math.min(lines.length, i + 2);
        StringBuilder snippet = new StringBuilder();
        for (int j = start; j < end; j++) {
            if (j == i) {
                snippet.append(highlightedLine);
            } else {
                snippet.append(lines[j]);
            }
            snippet.append("\n");
        }
        return snippet;
    }

    private SearchResponse getErrorSearchResponse(String error) {
        return SearchResponse.builder()
                .result(false)
                .count(null)
                .error(error)
                .build();
    }

    // Фильтруем леммы по принадлежности к сайту и по порогу повторяемости
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

            if (results.isEmpty()) {
                lemmasToRemove.add(lemma);
                continue;
            }

            for (Object[] result : results) {
                Integer foundSiteId = (Integer) result[1];
                System.out.println("foundSiteId: " + foundSiteId);
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

    // Получаем id страниц по заданной лемме
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
