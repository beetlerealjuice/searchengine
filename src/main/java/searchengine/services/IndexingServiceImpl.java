package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import searchengine.config.SiteConfig;
import searchengine.config.SitesList;
import searchengine.dto.statistics.IndexingResponse;
import searchengine.repository.IndexSearchRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.utils.IndexingThread;

import java.util.ArrayList;
import java.util.List;

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

        pageRepository.deleteAll();
        siteRepository.deleteAll();

        List<SiteConfig> siteList = sites.getSites();

        for (int i = 0; i < siteList.size(); i++) {
            // Запускаем поток обхода сайта
            IndexingThread indexingThread = new IndexingThread(siteList, i, siteRepository,
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



        return getFalseResponse("Иди на хуй");
    }
}
