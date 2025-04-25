package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.StatisticsService;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class StatisticServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;

    public static final int OFFSET_HOURS;

    static {
        ZonedDateTime zonedDateTime = ZonedDateTime.now();
        ZoneOffset zoneOffset = zonedDateTime.getOffset();
        OFFSET_HOURS = zoneOffset.getTotalSeconds() / 3600;
    }

    @Override
    public StatisticsResponse getStatistics() {

        StatisticsData statisticsData = new StatisticsData();
        TotalStatistics totalStatistics = new TotalStatistics();
        List<DetailedStatisticsItem> detailedStatisticsItems = new ArrayList<>();
        StatisticsResponse statisticsResponse = new StatisticsResponse();

        List<Site> sites = new ArrayList<>();
        siteRepository.findAll().forEach(sites::add);
        if (sites.isEmpty()) {
            statisticsResponse.setResult(false);
            return statisticsResponse;
        }

        List<Page> pages = new ArrayList<>();
        pageRepository.findAll().forEach(pages::add);
        List<Lemma> lemmas = new ArrayList<>();
        lemmaRepository.findAll().forEach(lemmas::add);

        totalStatistics.setSites(sites.size());
        totalStatistics.setPages(pages.size());
        totalStatistics.setLemmas(lemmas.size());
        totalStatistics.setIndexing(true);
        statisticsData.setTotal(totalStatistics);

        Map<Integer, Integer> pageCountsBySiteId = new HashMap<>();
        Map<Integer, Integer> lemmaCountsBySiteId = new HashMap<>();

        for (Page page : pages) {
            int siteId = page.getSite().getId();
            if (pageCountsBySiteId.containsKey(siteId)) {
                pageCountsBySiteId.put(siteId, pageCountsBySiteId.get(siteId) + 1);
            } else {
                pageCountsBySiteId.put(siteId, 1);
            }
        }

        for (Lemma lemma : lemmas) {
            int siteId = lemma.getSite().getId();
            if (lemmaCountsBySiteId.containsKey(siteId)) {
                lemmaCountsBySiteId.put(siteId, lemmaCountsBySiteId.get(siteId) + 1);
            } else {
                lemmaCountsBySiteId.put(siteId, 1);
            }
        }

        for (Site site : sites) {
            DetailedStatisticsItem detailedStatisticsItem = new DetailedStatisticsItem();
            detailedStatisticsItem.setName(site.getName());
            detailedStatisticsItem.setUrl(site.getUrl());
            detailedStatisticsItem.setStatus(site.getStatus().toString());
            detailedStatisticsItem.setStatusTime(site.getStatusTime().toEpochSecond(ZoneOffset.ofHours(+OFFSET_HOURS)));
            detailedStatisticsItem.setError(site.getLastError());

            int siteId = site.getId();
            Integer pageCount = pageCountsBySiteId.get(siteId);
            if (pageCount == null) {
                pageCount = 0;
            }
            detailedStatisticsItem.setPages(pageCount);

            Integer lemmaCount = lemmaCountsBySiteId.get(siteId);
            if (lemmaCount == null) {
                lemmaCount = 0;
            }
            detailedStatisticsItem.setLemmas(lemmaCount);

            detailedStatisticsItems.add(detailedStatisticsItem);
        }

        statisticsData.setDetailed(detailedStatisticsItems);
        statisticsResponse.setResult(true);
        statisticsResponse.setStatistics(statisticsData);

        return statisticsResponse;
    }
}