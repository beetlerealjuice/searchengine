package searchengine.model;

import searchengine.utils.LemmaFinder;
import searchengine.utils.LemmaFinderEn;

import java.io.IOException;
import java.util.Map;

public class TestLemma {
    public static void main(String[] args) throws IOException {
        LemmaFinderEn lemmaFinder = LemmaFinderEn.getInstance();
        String text = "I was very upset when I saw very small ass of Natasha OR her asses";

        Map<String, Integer> lemmas = lemmaFinder.collectLemmas(text);
        lemmas.forEach((lemma, count) -> {
            System.out.println("Лемма: " + lemma + ", Количество: " + count);
        });

        LemmaFinder lemmaFinder1 = LemmaFinder.getInstance();
        String text1 = "Повторное появление леопарда в Осетии позволяет предположить, " +
                "что леопард постоянно обитает в некоторых районах Северного Кавказа.";

        Map<String, Integer> lemmas1 = lemmaFinder1.collectLemmas(text1);
        lemmas1.forEach((lemma, count) -> {
            System.out.println("Лемма: " + lemma + ", Количество: " + count);
        });






    }
}
