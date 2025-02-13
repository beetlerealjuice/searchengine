package searchengine.utils;

import lombok.SneakyThrows;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.services.IndexingServiceImpl;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.RecursiveTask;


public class Indexing extends RecursiveTask<ConcurrentSkipListSet<String>> {

    private static ConcurrentSkipListSet<String> links = new ConcurrentSkipListSet<>();
    private String url;

    public Indexing(String url) {
        this.url = url;
    }

    @Override
    @SneakyThrows
    protected ConcurrentSkipListSet<String> compute() {

        Thread.sleep(1000);
        Set<Indexing> tasks = new HashSet<>();

        Elements elements;
        try {
            elements = Jsoup.connect(url)
                    .userAgent("Mozilla").get().select("a");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (Element element : elements) {
            String newLink = element.absUrl("href");

            if (IndexingServiceImpl.stopIndexing) {
                return links;
            }

            if (!isValidLink(newLink)) {
                continue;
            }

            links.add(newLink);

            Indexing indexing = new Indexing(newLink);
            indexing.fork();
            tasks.add(indexing);
        }

        tasks.forEach(task -> task.join());
        return links;

    }

    private boolean isValidLink(String link) {
        return (link.startsWith("http://") || link.startsWith("https://")) &&
                !links.contains(link) &&
                !link.contains(".jpg") &&
                !link.contains(".pdf") &&
                domainsMatch(link, url) &&
                !throwsException(link);
    }

    private boolean domainsMatch(String link, String originalUrl) {
        String linkDomain = extractDomain(link);
        String originalDomain = extractDomain(originalUrl);
        return linkDomain.equals(originalDomain);
    }

    private boolean throwsException(String link) {
        try {
            Document document = Jsoup.connect(link)
                    .userAgent("Mozilla").get();
        } catch (IOException e) {
            return true;
        }
        return false;
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
