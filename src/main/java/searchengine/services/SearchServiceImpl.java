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
import searchengine.utils.PageSnippet;

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
        List<PageSnippet> snippets = getSnippets(pages, matchingWords);

        List<SearchData> searchDataList = getSearchData(snippets, pageRepository);

        return SearchResponse.builder()
                .result(true)
                .count(searchDataList.size())
                .data(searchDataList)
                .build();

        // todo: реализация пагинации и формирование окончательного ответа


    }

    private List<SearchData> getSearchData(List<PageSnippet> snippets, PageRepository pages) {
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

                searchDataList.add(searchData);
            }
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

    // Метод для формирования сниппетов по страницам


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

    private List<PageSnippet> getSnippets(List<Page> pages, Set<String> matchingWords) {

        List<PageSnippet> result = new ArrayList<>();
        int maxSnippetLength = 150;

        // Собираем регулярное выражение для искомых слов (регистронезависимо)
        String regex = "\\b(" + String.join("|", matchingWords) + ")\\b";
        Pattern wordPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

        // Регулярное выражение для разделения текста на предложения.
        // Предполагается, что предложение заканчивается точкой, восклицательным или вопросительным знаком.
        String sentenceDelimiter = "(?<=[.!?])\\s+";

        for (Page page : pages) {
            String content = page.getContent();
            if (content == null || content.isEmpty()) {
                System.out.println("Пустое содержимое");
                continue;
            }
            // Извлекаем текст через Jsoup (body().text() — получаем только видимый текст)
            Document document = Jsoup.parse(content);
            String plainText = document.body().text();
            // Нормализуем пробелы
            plainText = plainText.replaceAll("\\s+", " ").trim();
            if (plainText.isEmpty()) {
                System.out.println("Пустой текст");
                continue;
            }

            List<String> snippetsForPage = new ArrayList<>();

            // Если в тексте есть знаки окончания предложений, разбиваем на предложения.
            boolean hasSentenceDelimiter = plainText.matches(".*[.!?].*");
            if (hasSentenceDelimiter) {
                String[] sentences = plainText.split(sentenceDelimiter);

                // Для каждого предложения проверяем наличие хотя бы одного искомого слова.
                for (String sentence : sentences) {
                    String trimmedSentence = sentence.trim();
                    Matcher m = wordPattern.matcher(trimmedSentence);
                    if (!m.find()) {
                        continue; // предложение не содержит искомых слов
                    }
                    String snippetCandidate;
                    if (trimmedSentence.length() <= maxSnippetLength) {
                        snippetCandidate = trimmedSentence;
                    } else {
                        // Если предложение слишком длинное, обрезаем до maxSnippetLength
                        String truncated = trimmedSentence.substring(0, maxSnippetLength);
                        // Пытаемся обрезать до конца предложения внутри фрагмента
                        int lastDelimiter = Math.max(truncated.lastIndexOf("."),
                                Math.max(truncated.lastIndexOf("!"), truncated.lastIndexOf("?")));
                        if (lastDelimiter != -1) {
                            truncated = truncated.substring(0, lastDelimiter + 1);
                        } else {
                            truncated += "...";
                        }
                        // Проверяем, что в обрезанном фрагменте всё ещё есть искомое слово
                        if (wordPattern.matcher(truncated).find()) {
                            snippetCandidate = truncated;
                        } else {
                            snippetCandidate = truncated; // оставляем как есть
                            System.out.println("Совпадение за обрезкой");
                        }
                    }
                    // Если сниппет не заканчивается знаком окончания предложения, добавляем многоточие
                    if (!snippetCandidate.matches(".*[.!?]$")) {
                        snippetCandidate += "...";
                    }
                    // Выделяем совпадения тегом <b>
                    Matcher highlightMatcher = wordPattern.matcher(snippetCandidate);
                    String highlightedSnippet = highlightMatcher.replaceAll("<b>$1</b>");
                    snippetsForPage.add(highlightedSnippet);
                }
            } else {
                // Если разделителей предложений нет, находим первое совпадение и берем фиксированный фрагмент.
                Matcher m = wordPattern.matcher(plainText);
                System.out.println("Текст без разделителей");
                if (m.find()) {
                    int matchStart = m.start();
                    // Отступаем до начала слова (до пробела или начала строки)
                    int snippetStart = matchStart;
                    while (snippetStart > 0 && plainText.charAt(snippetStart - 1) != ' ') {
                        snippetStart--;
                    }
                    int snippetEnd = Math.min(snippetStart + maxSnippetLength, plainText.length());
                    String snippetCandidate = plainText.substring(snippetStart, snippetEnd).trim();
                    if (snippetEnd < plainText.length()) {
                        snippetCandidate += "...";
                    }
                    Matcher highlightMatcher = wordPattern.matcher(snippetCandidate);
                    String highlightedSnippet = highlightMatcher.replaceAll("<b>$1</b>");
                    snippetsForPage.add(highlightedSnippet);
                }
            }

            if (!snippetsForPage.isEmpty()) {
                PageSnippet pageSnippet = new PageSnippet();
                pageSnippet.setPageId(page.getId());
                pageSnippet.setSnippet(snippetsForPage);
                result.add(pageSnippet);
            }
        }
        return result;
    }
}