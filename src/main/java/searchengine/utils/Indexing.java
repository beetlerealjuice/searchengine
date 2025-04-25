package searchengine.utils;


import lombok.SneakyThrows;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.services.impl.IndexingServiceImpl;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.RecursiveTask;


public class Indexing extends RecursiveTask<ConcurrentSkipListSet<String>> {

    private static ConcurrentSkipListSet<String> links = new ConcurrentSkipListSet<>();
    private String link;

    public Indexing(String link) {
        this.link = link;
    }


    @SneakyThrows
    @Override
    protected ConcurrentSkipListSet<String> compute() {
        Thread.sleep(1000);
        Set<Indexing> tasks = new HashSet<>();
        String regex = "https?://[^,\\s]+";

        Elements elements;
        try {
            elements = Jsoup.connect(link)
                    .userAgent("Mozilla").get().select("a");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (Element element : elements) {
            if (!IndexingServiceImpl.isStopExecutor()) {
                IndexingServiceImpl.setStopExecutor();
                return links;
            }

            String newLink = element.absUrl("href");
            boolean checkLink = newLink.matches(regex) &&
                    newLink.contains(getDomen(link)) &&
                    !links.contains(newLink) &&
                    !newLink.contains(".pdf") &&
                    !newLink.contains(".jpg")
                    && checkException(newLink) == null;


            if (!checkLink) {
                continue;
            }
            links.add(newLink);
            Indexing indexing = new Indexing(newLink);
            indexing.fork();
            tasks.add(indexing);
        }

        tasks.forEach(task -> {
            task.join();
        });
        return links;

    }

    private Exception checkException(String link) {
        try {
            Document document = Jsoup.connect(link)
                    .userAgent("Mozilla").get();
        } catch (IOException e) {
            return e;
        }
        return null;
    }

    private String getDomen(String url) {
        return (url.contains("www")) ?
                url.substring(12).split("/", 2)[0] : url.substring(8).split("/", 2)[0];
    }

}
