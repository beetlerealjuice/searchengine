package searchengine.utils;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import searchengine.config.SiteConfig;
import searchengine.model.*;
import searchengine.repository.IndexSearchRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.IndexingServiceImpl;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ForkJoinPool;

public class IndexingThread extends Thread {

    private final List<SiteConfig> siteList;
    private final int index;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexSearchRepository indexSearchRepository;

    public IndexingThread(List<SiteConfig> siteList, int index, SiteRepository siteRepository,
                          PageRepository pageRepository,
                          LemmaRepository lemmaRepository,
                          IndexSearchRepository indexSearchRepository) {
        this.siteList = siteList;
        this.index = index;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexSearchRepository = indexSearchRepository;
    }

    @Override
    public void run() {
        Site newSite = new Site();
        newSite.setName(siteList.get(index).getName());
        newSite.setUrl(siteList.get(index).getUrl());
        newSite.setStatusTime(LocalDateTime.now());
        newSite.setStatus(Status.INDEXING);
        siteRepository.save(newSite);
        try {
            ForkJoinPool pool = new ForkJoinPool();
            Indexing indexing = new Indexing(newSite.getUrl());
            Set<String> setLinks = new HashSet<>(pool.invoke(indexing));
            pool.shutdown();
            for (String link : setLinks) {
                if (link.contains(extractDomain(newSite.getUrl()))) {
                    setPage(newSite, link);
                }

                if (IndexingServiceImpl.stopIndexing) {
                    currentThread().interrupt();
                    getErrorSite(newSite, "Индексация остановлена пользователем");
                    break;
                }

                newSite.setStatusTime(LocalDateTime.now());
                newSite.setStatus(Status.INDEXED);
            }

        } catch (Exception ex) {

            getErrorSite(newSite, ex.getMessage());

            siteRepository.save(newSite);
        }
        siteRepository.save(newSite);
    }

    private void getErrorSite(Site site, String error) {
        site.setLastError(error);
        site.setStatusTime(LocalDateTime.now());
        site.setStatus(Status.FAILED);
    }

    public void setPage(Site site, String url) throws IOException {

        Optional<Page> page = pageRepository.findByPath(extractPath(url));
        if (page.isPresent()) {
            return;
        }

        Page newPage = new Page();
        newPage.setSite(site);
        newPage.setPath(extractPath(url));

        try {
            Connection.Response response = Jsoup.connect(url.trim())
                    .userAgent("Mozilla")
                    .execute();

            newPage.setContent(response.body());
            newPage.setCode(response.statusCode());

        } catch (Exception ex) {
            newPage.setCode(HttpStatus.NOT_FOUND.value());
        }

        pageRepository.save(newPage);

        if (newPage.getCode() != 200) return;

        int frequency = 0;

        LemmaFinder lemmaFinder = LemmaFinder.getInstance();
        LemmaFinderEn lemmaFinderEn = LemmaFinderEn.getInstance();

        lemmaFinderEn.collectLemmas(newPage.getContent());

        Map<String, Integer> lemmas = new HashMap<>();
        lemmas = lemmaFinder.collectLemmas(newPage.getContent());
        lemmas.putAll(lemmaFinderEn.collectLemmas(newPage.getContent()));

        for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
            IndexSearch newIndex = new IndexSearch();
            Lemma newLemma = new Lemma();
            Optional<Lemma> lemma = lemmaRepository.findByLemma(entry.getKey());

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
