package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import searchengine.config.SiteConfig;
import searchengine.config.SitesList;
import searchengine.dto.statistics.IndexingResponse;
import searchengine.model.IndexSearch;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repository.IndexSearchRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.utils.IndexingThread;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    private final SitesList sites;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexSearchRepository indexSearchRepository;

    // Признак остановки индексации
    public static boolean stopIndexing;
    // Признак запущенной индексации
    private boolean isIndexing;
    // Список для хранения ссылок на потоки
    private final List<Thread> indexingThreads = new ArrayList<>();
    // Признак добавления отельной страницы для индексации
    private boolean isAddPage;
    // Добавленная для индексации страница
    private String addedUrl;
    private Site addedSite;


    @Override
    @SneakyThrows
    public IndexingResponse startIndexing() {

        for (Thread thread : indexingThreads) {
            if (thread.isAlive()) {
                return getFalseResponse("Индексация уже запущена");
            }
        }

        stopIndexing = false;
        isIndexing = true;

        List<SiteConfig> siteList = sites.getSites();

        if (isAddPage) {
            IndexingThread indexingThread = new IndexingThread(siteList, 0, siteRepository,
                    pageRepository, lemmaRepository, indexSearchRepository);

            indexingThread.setPage(addedSite, addedUrl);
        }

        else {
            indexSearchRepository.deleteAll();
            lemmaRepository.deleteAll();
            pageRepository.deleteAll();
            siteRepository.deleteAll();
        }

        for (int i = 0; i < siteList.size(); i++) {
            // Запускаем поток обхода сайта
            IndexingThread indexingThread = new IndexingThread(siteList, i,
                    siteRepository,
                    pageRepository,
                    lemmaRepository,
                    indexSearchRepository);

            indexingThreads.add(indexingThread);
            indexingThread.start();

            if (stopIndexing) {
                indexingThread.interrupt();
                break;
            }
        }

        return getTrueResponse();
    }

    @Override
    public IndexingResponse stopIndexing() {

        if (!isIndexing) {
            return getFalseResponse("Индексация не запущена");
        }

        stopIndexing = true;

        for (Thread thread : indexingThreads) {
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }

        isIndexing = false;
        return getTrueResponse();
    }

    private IndexingResponse getFalseResponse(String error) {
        return IndexingResponse.builder()
                .result(false)
                .error(error)
                .build();
    }

    private IndexingResponse getTrueResponse() {
        return IndexingResponse.builder()
                .result(true)
                .build();

    }

    private boolean isValidUrl(String url) {
        String regex = "https?://[^,\\s]+";
        return url.matches(regex);
    }

    @Override
    public IndexingResponse indexPage(String url) {

        if (!isValidUrl(url)) {
            return getFalseResponse("Данная страница не найдена");
        }

        String domain = extractDomain(url);
        List<Site> sitesInDB = siteRepository.findAll();

        long count = sitesInDB.stream()
                .filter(site -> site.getUrl().contains(domain))
                .map(site -> {
                    Optional<Page> page = pageRepository.findByPath(extractPath(url));
                    page.ifPresent(currentPage -> {
                        findLemmaInDb(currentPage);
                        isAddPage = true;
                        addedSite = site;
                        addedUrl = url;

                    });
                    return site;
                })
                .count();

        if (count == 0)
            return getFalseResponse("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");

        List<SiteConfig> siteList = sites.getSites();
        SiteConfig newSite = new SiteConfig();

        newSite.setUrl(url);
        newSite.setName(extractSiteName(url));

        siteList.add(0, newSite);

        return getTrueResponse();
    }

    private void findLemmaInDb(Page page) {
        List<IndexSearch> indexSearches = indexSearchRepository.findByPageId(page.getId());
        pageRepository.deleteById(page.getId());

        for (IndexSearch indexSearch : indexSearches) {
            int lemmaId = indexSearch.getLemma().getId();
            Optional<Lemma> lemma = lemmaRepository.findById(lemmaId);
            int frequency = lemma.get().getFrequency();
            if (frequency >= 2) {
                frequency = frequency - 1;
                Lemma newLemma = lemma.get();
                newLemma.setFrequency(frequency);
                lemmaRepository.save(newLemma);
            } else {
                lemmaRepository.deleteById(lemmaId);
            }
            indexSearchRepository.deleteById(indexSearch.getId());
        }
    }

    private String extractSiteName(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host != null) {
                host = host.replaceFirst("www\\.", "");
                String[] parts = host.split("\\.");
                if (parts.length >= 2) {
                    String siteName = capitalize(parts[0]) + "." + parts[1];
                    return siteName;
                }
            }
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private String extractPath(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            return (path != null && !path.isEmpty()) ? path : "/";
        } catch (URISyntaxException e) {
            return "/";
        }
    }

    private String extractDomain(String urlString) {
        try {
            URL url = new URL(urlString);
            String host = url.getHost();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (MalformedURLException e) {
            return "";
        }
    }
}
