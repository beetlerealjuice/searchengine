package searchengine.services.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.lucene.morphology.WrongCharaterException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import searchengine.dto.SearchResponse;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.SearchData;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.SearchService;
import searchengine.utils.LemmaFinder;
import searchengine.utils.LemmaFinderEn;
import searchengine.utils.PageSnippet;
import searchengine.utils.TextUtils;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class SearchServiceImpl implements SearchService {

    private final SiteRepository siteRepository;

    private final PageRepository pageRepository;

    private final LemmaRepository lemmaRepository;

    private final EntityManager entityManager;

    private static final double REPETITION_PERCENTAGE = 0.9;

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

        // Добавляем копии искомых слов с заглавной буквы
        matchingWords = expandMatchingWords(matchingWords);

        // Получаем сниппеты
        List<PageSnippet> snippets = getSnippets(pages, matchingWords);

        List<Lemma> lemmas = lemmaRepository.findByLemmaIn(sortedLemmas);

        // Получаем относительную релевантность страниц
        Map<Integer, Float> relativeRelevance = getRelativeRelevance(lemmas, pages);

        // Формируем поисковую выдачу
        List<SearchData> searchDataList = getSearchData(snippets, pageRepository, relativeRelevance);

        int totalCount = searchDataList.size();

        if (offset == null) {
            offset = 0;
        }
        if (limit == null || limit <= 0) {
            limit = 20; // дефолтный лимит
        }

        // Защита от выхода за границы списка
        int fromIndex = Math.min(offset, totalCount);
        int toIndex = Math.min(offset + limit, totalCount);

        List<SearchData> paginatedResults = searchDataList.subList(fromIndex, toIndex);

        return SearchResponse.builder()
                .result(true)
                .count(totalCount)
                .data(paginatedResults)
                .build();
    }

    private List<SearchData> getSearchData(List<PageSnippet> snippets, PageRepository pages, Map<Integer, Float> relevance) {
        List<SearchData> searchDataList = new ArrayList<>();

        for (PageSnippet pageSnippet : snippets) {
            int pageId = pageSnippet.getPageId();

            Page page = pages.findById(pageId).get();

            Document document = Jsoup.parse(page.getContent());
            String title = document.title();

            for (String snippet : pageSnippet.getSnippet()) {
                SearchData searchData = new SearchData();
                searchData.setSite(page.getSite().getUrl());
                searchData.setSiteName(page.getSite().getName());
                searchData.setTitle(title);
                searchData.setUri(page.getPath());
                searchData.setSnippet(snippet);
                searchData.setRelevance(relevance.get(pageId));
                searchDataList.add(searchData);
            }
        }

        // Сортируем поисковую выдачу по относительной релевантности
        List<SearchData> sortedList = searchDataList.stream()
                .sorted(Comparator
                        .comparingDouble(SearchData::getRelevance).reversed()
                        .thenComparing(
                                Comparator.comparingInt((SearchData sd) -> TextUtils.countBoldTags(sd.getSnippet())).reversed()
                        )
                )
                .collect(Collectors.toList());

        return sortedList;
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
            String normalizedWord = TextUtils.normalizeWord(word);
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
        if (TextUtils.isRussian(normalizedWord)) {
            lemmas.addAll(russianMorph.getLemmaSet(normalizedWord));
        } else if (TextUtils.isEnglish(normalizedWord)) {
            lemmas.addAll(englishMorph.getLemmaSet(normalizedWord));
        } else {
            // Если слово содержит смешанные символы, используем оба варианта
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
                String normalizedWord = TextUtils.normalizeWord(word);
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
                if (siteId != null && !siteId.equals(foundSiteId)) {
                    lemmasToRemove.add(lemma);
                    continue;
                }
                Number repetitionCount = (Number) result[2];
                Number totalPagesOnSite = (Number) result[3];
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

    private List<PageSnippet> getSnippets(List<Page> pages, Set<String> matchingWords) {
        List<PageSnippet> snippets = new ArrayList<>();
        for (Page page : pages) {
            String text = Jsoup.parse(page.getContent()).body().text();
            String[] sentences = text.split("(?<=[.!?])\\s+"); // разбиваем на предложения
            List<String> snippetList = new ArrayList<>();

            for (String sentence : sentences) {
                String lowerCaseSentence = sentence.toLowerCase();
                boolean containsMatch = matchingWords.stream()
                        .anyMatch(word -> lowerCaseSentence.contains(word.toLowerCase()));

                if (containsMatch) {
                    String highlighted = highlightWords(sentence, matchingWords);

                    if (highlighted.length() > 150) {
                        highlighted = trimAroundFirstMatch(highlighted, matchingWords, 150);
                    }

                    snippetList.add(highlighted);
                }
            }

            if (!snippetList.isEmpty()) {
                PageSnippet pageSnippet = new PageSnippet();
                pageSnippet.setPageId(page.getId());
                pageSnippet.setSnippet(snippetList);
                snippets.add(pageSnippet);
            }
        }
        return snippets;
    }

    private String highlightWords(String sentence, Set<String> matchingWords) {
        String highlighted = sentence;
        for (String word : matchingWords) {
            highlighted = highlighted.replaceAll("(?i)(" + Pattern.quote(word) + ")", "<b>$1</b>");
        }
        return highlighted;
    }

    private String trimAroundFirstMatch(String text, Set<String> matchingWords, int maxLength) {
        String lowerText = text.toLowerCase();
        int firstMatchIndex = findFirstMatchIndex(text, matchingWords);

        if (firstMatchIndex == -1) {
            return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
        }

        int start = Math.max(0, firstMatchIndex - maxLength / 2);
        int end = Math.min(text.length(), start + maxLength);

        String trimmed = text.substring(start, end);

        // Обрезаем аккуратно по словам
        trimmed = adjustToWordBoundaries(trimmed);

        if (start > 0 || end < text.length()) {
            trimmed = "..." + trimmed + "...";
        }

        return trimmed;
    }

    private int findFirstMatchIndex(String text, Set<String> matchingWords) {
        String lowerText = text.toLowerCase();
        int minIndex = Integer.MAX_VALUE;
        for (String word : matchingWords) {
            int idx = lowerText.indexOf(word.toLowerCase());
            if (idx != -1 && idx < minIndex) {
                minIndex = idx;
            }
        }
        return minIndex == Integer.MAX_VALUE ? -1 : minIndex;
    }

    private String adjustToWordBoundaries(String text) {
        // Убираем обрыв слов с краёв
        int start = 0;
        int end = text.length();

        while (start < end && !Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }

        return text.substring(start, end).trim();
    }

    private Set<String> expandMatchingWords(Set<String> matchingWords) {
        Set<String> expandedWords = new HashSet<>(matchingWords);
        for (String word : matchingWords) {
            if (!word.isEmpty()) {
                String capitalizedWord = Character.toUpperCase(word.charAt(0)) + word.substring(1);
                expandedWords.add(capitalizedWord);
            }
        }
        return expandedWords;
    }

    private Map<Integer, Float> getRelativeRelevance(List<Lemma> foundLemmas, List<Page> pages) throws IOException {
        Map<Integer, Float> pageRank = new HashMap<>();
        Map<Integer, String> pageContent = new HashMap<>();

        // Получаем список ID лемм
        List<Integer> lemmaIds = foundLemmas.stream()
                .map(Lemma::getId)
                .collect(Collectors.toList());
        // Получаем список ID страниц
        List<Integer> pageIds = pages.stream()
                .map(Page::getId)
                .toList();

        // Получаем список лемм
        List<String> lemmaList = foundLemmas.stream()
                .map(Lemma::getLemma)
                .toList();

        if (lemmaIds.isEmpty()) {
            return pageRank; // Если список лемм пуст, сразу возвращаем пустую карту
        }

        // SQL-запрос для получения суммы рангов и содержимого страниц
        String sql = "SELECT isa.page_id, SUM(isa.rank) AS total_rank, p.content " +
                "FROM index_search isa " +
                "JOIN page p ON p.id = isa.page_id " +
                "WHERE isa.lemma_id IN (:lemmaIds) " +
                "AND p.id IN (:pageIds) " +
                "GROUP BY isa.page_id, p.content";

        // Создание и выполнение запроса
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("lemmaIds", lemmaIds);
        query.setParameter("pageIds", pageIds);
        List<Object[]> results = query.getResultList();

        // Обрабатываем результаты запроса
        for (Object[] result : results) {
            int pageId = ((Number) result[0]).intValue();
            float rank = ((Number) result[1]).floatValue();
            String content = (String) result[2];  // Содержимое страницы

            content = Jsoup.parse(content).body().text();

            // Сохраняем результаты
            pageRank.put(pageId, rank);
            pageContent.put(pageId, content);
        }

        Map<Integer, Map<Integer, String>> lemmasPositionsForPage = new HashMap<>();

        LemmaFinder lemmaFinderRu = LemmaFinder.getInstance();
        LemmaFinderEn lemmaFinderEn = LemmaFinderEn.getInstance();

        for (Map.Entry<Integer, String> entry : pageContent.entrySet()) {
            int pageId = entry.getKey();
            String content = entry.getValue();

            Map <Integer, String> positionsOfLemmas = lemmasPositions(content, lemmaFinderRu, lemmaFinderEn, lemmaList);

            // Если найдено точное совпадение лемм, идущих друг за другом в поисковом запросе,
            // то присвоить данной странице максимальную релевантность
            if (lemmaList.size() < 3) continue;
            if (hasConsecutiveWords(positionsOfLemmas, content, lemmaFinderRu, lemmaFinderEn)) {
                pageRank.put(
                        pageId,
                        pageRank.values().stream()
                                .max(Float::compare)
                                .orElse(0f) + 1
                );
            }
            lemmasPositionsForPage.put(pageId, positionsOfLemmas);
        }

        // Нормализация значений рангов (от 0 до 1)
        if (!pageRank.isEmpty()) {
            float max = Collections.max(pageRank.values());
            for (Map.Entry<Integer, Float> entry : pageRank.entrySet()) {
                entry.setValue(entry.getValue() / max);
            }
        }

        return pageRank;  // Возвращаем нормализованные ранги страниц
    }

    // Определяем позиции найденных лемм в тексте
    private HashMap<Integer, String> lemmasPositions(String content, LemmaFinder lemmaFinderRu,
                                                    LemmaFinderEn lemmaFinderEn, List<String> lemmaList) {
        HashMap<Integer, String> positionOfLemma = new HashMap<>();

        // Разбиваем контент на слова и нормализуем
        String[] words = content.split("\s+");

        for (String word : words) {
            String normalizedWord = TextUtils.normalizeWord(word);
            int index = content.indexOf(word);

            if (TextUtils.isRussian(normalizedWord)) {
                String lemma = lemmaFinderRu.getLemma(normalizedWord);
                if (lemmaList.contains(lemma)) {
                    while (index != -1) {
                        positionOfLemma.put(index, normalizedWord);
                        index = content.indexOf(word, index + 1);
                    }
                }
            } else if (TextUtils.isEnglish(normalizedWord)) {
                String lemma = lemmaFinderEn.getLemma(normalizedWord);
                if (lemmaList.contains(lemma)) {
                    while (index != -1) {
                        positionOfLemma.put(index, normalizedWord);
                        index = content.indexOf(word, index + 1);
                    }
                }
            }
        }
        return positionOfLemma;
    }

    // Определяем есть ли последовательности найденных форм лемм
    private boolean hasConsecutiveWords(Map<Integer, String> positionsOfLemmas, String content,
                                        LemmaFinder lemmaFinder, LemmaFinderEn lemmaFinderEn) {
        List<Integer> positions = new ArrayList<>(positionsOfLemmas.keySet());
        Collections.sort(positions);

        int consecutiveCount = 1;

        for (int i = 1; i < positions.size(); i++) {
            int prevPos = positions.get(i - 1);
            int currPos = positions.get(i);

            // Находим реальную длину слова в тексте
            int wordEnd = prevPos;
            while (wordEnd < content.length() && !Character.isWhitespace(content.charAt(wordEnd))) {
                wordEnd++;
            }

            int begin = wordEnd;
            int end = currPos;

            if (begin >= end) continue;

            String betweenText = content.substring(begin, end).trim();
            if (betweenText.isEmpty()) {
                consecutiveCount++;
            } else {
                // Убираем кавычки
                betweenText = betweenText.replaceAll("[\"«»„“]", "").trim();

                if (betweenText.isEmpty()) {
                    consecutiveCount++;
                    continue;
                }

                String[] wordsBetween = betweenText.split("\\s+");
                boolean allParticlesOrQuotes = true;

                for (String word : wordsBetween) {
                    if (word.isBlank()) continue;

                    word = TextUtils.normalizeWord(word);
                    if (word.isBlank()) continue;

                    try {
                        boolean isRussian = TextUtils.isRussian(word);
                        boolean isParticle = isRussian
                                ? lemmaFinder.anyWordBaseBelongToParticle(lemmaFinder.luceneMorphology.getMorphInfo(word))
                                : lemmaFinderEn.anyWordBaseBelongToParticle(lemmaFinderEn.luceneMorphology.getMorphInfo(word));

                        if (!isParticle) {
                            allParticlesOrQuotes = false;
                            break;
                        }
                    } catch (ArrayIndexOutOfBoundsException | WrongCharaterException e) {
                        System.out.println("Ошибка при анализе слова: " + word + " — " + e.getClass().getSimpleName());
                    }
                }

                if (allParticlesOrQuotes) {
                    consecutiveCount++;
                } else {
                    consecutiveCount = 1;
                }
            }

            if (consecutiveCount >= 3) {
                return true;
            }
        }
        return false;
    }
}