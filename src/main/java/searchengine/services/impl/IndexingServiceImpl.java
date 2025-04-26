package searchengine.services.impl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import searchengine.config.SiteConfig;
import searchengine.config.SitesList;
import searchengine.dto.Response;
import searchengine.model.*;
import searchengine.repository.IndexSearchRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.IndexingService;
import searchengine.utils.Indexing;
import searchengine.utils.LemmaFinder;
import searchengine.utils.LemmaFinderEn;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;


@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final SitesList list;

    private final SiteRepository siteRepository;

    private final PageRepository pageRepository;

    private final IndexSearchRepository indexSearchRepository;

    private final LemmaRepository lemmaRepository;

    @Getter
    private static volatile boolean stopExecutor;
    private static volatile ThreadPoolExecutor executor;

    static {
        stopExecutor = true;
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors()
        );
    }

    @Override
    public Response startIndexing() {
        if (executor.getActiveCount() != 0) {

            return getFalseResponse("Индексация уже запущена");
        }
        stopExecutor = true;
        executor =
                (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        indexSearchRepository.deleteAll();
        lemmaRepository.deleteAll();
        pageRepository.deleteAll();
        siteRepository.deleteAll();


        List<SiteConfig> sitesList = list.getSites();
        for (int i = 0; i < sitesList.size(); i++) {
            int j = i;
            createNewSiteInDb(sitesList, j);
        }

        return getTrueResponse();
    }

    private void createNewSiteInDb(List<SiteConfig> sitesList, int j) {
        executor.execute(() -> {
            Site newSite = new Site();
            newSite.setName(sitesList.get(j).getName());
            newSite.setUrl(sitesList.get(j).getUrl());
            newSite.setStatusTime(LocalDateTime.now());
            newSite.setStatus(Status.INDEXING);
            siteRepository.save(newSite);

            try {
                ForkJoinPool pool = new ForkJoinPool();
                Indexing indexing = new Indexing(newSite.getUrl());
                Set<String> setLinks = new HashSet<>(pool.invoke(indexing));
                pool.shutdown();
                for (String link : setLinks) {
                    if (link.contains(getDomen(newSite.getUrl()))) setPage(newSite, link);
                    if (!stopExecutor) {
                        executor.shutdown();
                        getErrorSite(newSite, "Индексация остановлена пользователем");
                        break;
                    }
                }
                if (stopExecutor) {
                    newSite.setStatusTime(LocalDateTime.now());
                    newSite.setStatus(Status.INDEXED);
                }
            } catch (Exception ex) {
                getErrorSite(newSite, ex.getMessage());
                siteRepository.save(newSite);
            }
            siteRepository.save(newSite);
        });
    }

    private void getErrorSite(Site site, String error) {
        site.setLastError(error);
        site.setStatusTime(LocalDateTime.now());
        site.setStatus(Status.FAILED);
    }

    private synchronized void setPage(Site site, String url) throws IOException {
        int frequency = 0;

        Optional<Page> page = pageRepository.findByPath(extractPath(url));
        if (page.isPresent()) return;

        Page newPage = new Page();
        newPage.setSite(site);
        newPage.setPath(extractPath(url));
        frequency = 1;

        try {
            newPage.setContent(getHtml(url));
            newPage.setCode(new ResponseEntity<>(HttpStatus.OK).getStatusCodeValue());
        } catch (SocketTimeoutException e) {
            // Логируем и пропускаем эту страницу, не прерывая индексацию
            System.err.println("Timeout for URL: " + url);
            return;
        } catch (IOException ex) {
            newPage.setCode(new ResponseEntity<>(HttpStatus.NOT_FOUND).getStatusCodeValue());
        }

        pageRepository.save(newPage);

        if (newPage.getCode() != 200) return;

        // Лемматизация
        LemmaFinder lemmaFinder = LemmaFinder.getInstance();
        LemmaFinderEn lemmaFinderEn = LemmaFinderEn.getInstance();
        Map<String, Integer> lemmas = lemmaFinder.collectLemmas(url);
        lemmas.putAll(lemmaFinderEn.collectLemmas(url));

        for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
            IndexSearch newIndex = new IndexSearch();
            Lemma newLemma = new Lemma();
            Optional<Lemma> lemma = lemmaRepository.findFirstByLemma(entry.getKey());

            if (lemma.isEmpty()) {
                newLemma.setLemma(entry.getKey());
                newLemma.setSite(site);
                newLemma.setFrequency(frequency);
                newIndex.setPage(newPage);
                newIndex.setLemma(newLemma);
                newIndex.setRank(Float.valueOf(entry.getValue()));

                lemmaRepository.save(newLemma);
                indexSearchRepository.save(newIndex);
            } else {
                frequency = lemma.get().getFrequency() + 1;
                lemma.get().setFrequency(frequency);
                newIndex.setPage(newPage);
                newIndex.setLemma(lemma.get());
                newIndex.setRank(Float.valueOf(entry.getValue()));
                lemmaRepository.save(lemma.get());
                indexSearchRepository.save(newIndex);
            }
        }
    }


    public static String getHtml(String link) throws IOException {
        return Jsoup.connect(link.trim())
                .userAgent("Mozilla")
                .timeout(10000)  // 10 секунд таймаут
                .get()
                .html();
    }



    public static String getDomen(String url) {
        return (url.contains("www")) ?
                url.substring(12).split("/", 2)[0] : url.substring(8).split("/", 2)[0];
    }


    @Override
    public Response stopIndexing() {
        if (executor.getActiveCount() == 0) {
            return getFalseResponse("Индексация не запущена");
        }

        executor.shutdown();
        stopExecutor = false;

        return getTrueResponse();
    }


    @SneakyThrows
    @Override
    public Response indexPage(String url) {
        stopExecutor = true;
        executor =
                (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        if (!urlIsUrl(url)) {
            return getFalseResponse("Данная страница не найдена");
        }

        String domen = getDomen(url);
        Iterable<Site> sites = siteRepository.findAll();

        int i = 0;
        for (Site site : sites) {
            if (site.getUrl().contains(domen)) {
                i++;
                Optional<Page> page = pageRepository.findByPath(extractPath(url));
                page.ifPresent(this::findLemmaInDb);
                setPage(site, url);
            }
        }


        if (i == 0)
            return getFalseResponse("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");

        return getTrueResponse();
    }

    private Response getTrueResponse() {
        return Response.builder()
                .result(true)
                .build();

    }

    private Response getFalseResponse(String error) {
        return Response.builder()
                .result(false)
                .error(error)
                .build();
    }


    private void findLemmaInDb(Page page) {
        Iterable<IndexSearch> indexSearches = indexSearchRepository.findByPageId(page.getId());
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


    private boolean urlIsUrl(String url) {
        String regex = "https?://[^,\\s]+";
        return url.matches(regex);
    }

    private String extractPath(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                return "/";
            }
            // Убираем лишние слэши в конце, кроме корня "/"
            if (path.endsWith("/") && path.length() > 1) {
                path = path.replaceAll("/+$", ""); // убираем все конечные /
            }
            return path;
        } catch (URISyntaxException e) {
            return "/";
        }
    }


    public static void setStopExecutor() {
        executor.shutdown();
    }


}
