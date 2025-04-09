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
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.utils.LemmaFinder;
import searchengine.utils.LemmaFinderEn;
import searchengine.utils.PageSnippet;
import searchengine.utils.TextUtils;

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


        return SearchResponse.builder()
                .result(true)
                .count(searchDataList.size())
                .data(searchDataList)
                .build();

        // todo: реализация пагинации и формирование окончательного ответа


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
        List<PageSnippet> result = new ArrayList<>();
        int maxSnippetLength = 150;

        String regex = "\\b(" + String.join("|", matchingWords) + ")\\b";
        Pattern wordPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        String sentenceDelimiter = "(?<=[.!?])\\s+";

        for (Page page : pages) {
            String content = page.getContent();
            if (content == null || content.isEmpty()) continue;

            Document document = Jsoup.parse(content);
            String plainText = document.body().text().replaceAll("\\s+", " ").trim();
            if (plainText.isEmpty()) continue;

            List<String> snippetsForPage = extractSnippets(plainText, wordPattern, maxSnippetLength, sentenceDelimiter);

            if (!snippetsForPage.isEmpty()) {
                PageSnippet pageSnippet = new PageSnippet();
                pageSnippet.setPageId(page.getId());
                pageSnippet.setSnippet(snippetsForPage);
                result.add(pageSnippet);
            }
        }

        return result;
    }

    private List<String> extractSnippets(String text, Pattern wordPattern, int maxLength, String delimiter) {
        List<String> snippets = new ArrayList<>();

        if (text.matches(".*[.!?].*")) {
            for (String sentence : text.split(delimiter)) {
                String trimmed = sentence.trim();
                if (!wordPattern.matcher(trimmed).find()) continue;

                String snippet = buildSnippet(trimmed, wordPattern, maxLength);
                snippets.add(TextUtils.highlightMatches(snippet, wordPattern));
            }
        } else {
            Matcher m = wordPattern.matcher(text);
            if (m.find()) {
                String snippet = getString(m, text, maxLength);
                snippets.add(TextUtils.highlightMatches(snippet, wordPattern));
            }
        }

        return snippets;
    }

    private String buildSnippet(String sentence, Pattern wordPattern, int maxLength) {
        if (sentence.length() <= maxLength) {
            return TextUtils.ensureEndsWithPunctuation(sentence);
        }

        String truncated = getString(sentence, maxLength);
        if (wordPattern.matcher(truncated).find()) {
            return TextUtils.ensureEndsWithPunctuation(truncated);
        }

        Matcher matcher = wordPattern.matcher(sentence);
        if (matcher.find(maxLength)) {
            String tail = "..." + sentence.substring(matcher.start());
            if (tail.length() > maxLength) {
                tail = tail.substring(0, maxLength);
                if (!tail.matches(".*[.!?]$")) {
                    tail += "...";
                }
            }
            return tail;
        }

        return TextUtils.ensureEndsWithPunctuation(truncated);
    }

    private String getString(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String getString(Matcher matcher, String text, int maxLength) {
        int start = Math.max(0, matcher.start() - 10);
        int end = Math.min(text.length(), start + maxLength);
        return text.substring(start, end);
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

        //Map<Integer, Map<Integer, String>> lemmasPositionsForPage = new HashMap<>();

        LemmaFinder lemmaFinderRu = LemmaFinder.getInstance();
        LemmaFinderEn lemmaFinderEn = LemmaFinderEn.getInstance();

        for (Map.Entry<Integer, String> entry : pageContent.entrySet()) {
            int pageId = entry.getKey();
            String content = entry.getValue();

            Map <Integer, String> positionsOfLemmas = lemmasPositions(content, lemmaFinderRu, lemmaFinderEn, lemmaList);

            // Если найдено точное совпадение лемм, идущих друг за другом в поисковом запросе,
            // то присвоить данной странице максимальную релевантность
            if (lemmaList.size() >= 3 && hasConsecutiveWords(positionsOfLemmas)) {
                System.out.println("Нашел фразу");
                pageRank.put(
                        pageId,
                        pageRank.values().stream()
                                .max(Float::compare)
                                .orElse(0f) + 1
                );
            }
            //lemmasPositionsForPage.put(pageId, positionsOfLemmas);
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
    private boolean hasConsecutiveWords(Map<Integer, String> positionsOfLemmas) {
        List<Integer> positions = new ArrayList<>(positionsOfLemmas.keySet());
        Collections.sort(positions);

        int consecutiveCount = 1;

        for (int i = 1; i < positions.size(); i++) {
            int prevPos = positions.get(i - 1);
            int currPos = positions.get(i);

            String prevWord = positionsOfLemmas.get(prevPos);
            int expectedNextStart = prevPos + prevWord.length() + 1;

            if (currPos == expectedNextStart) {
                consecutiveCount++;
                if (consecutiveCount >= 3) {
                    return true;
                }
            } else {
                consecutiveCount = 1;
            }
        }

        return false;
    }

}