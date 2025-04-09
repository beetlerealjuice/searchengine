package searchengine.utils;

import java.util.regex.Pattern;

public class TextUtils {

    private TextUtils() {

    }

    // Нормализация слова: оставляем только буквы, приводим к нижнему регистру
    public static String normalizeWord(String word) {
        return word.toLowerCase().replaceAll("[^а-яёa-z]", "");
    }

    // Проверка: слово состоит только из русских букв
    public static boolean isRussian(String word) {
        return word.matches("[а-яё]+");
    }

    // Проверка: слово состоит только из английских букв
    public static boolean isEnglish(String word) {
        return word.matches("[a-z]+");
    }

    // Подсчет количества открывающихся тегов <b>
    public static int countBoldTags(String snippet) {
        // Подсчёт количества открывающих тегов <b>
        return snippet == null ? 0 : snippet.split("<b>", -1).length - 1;
    }

    public static String highlightMatches(String text, Pattern wordPattern) {
        return wordPattern.matcher(text).replaceAll("<b>$1</b>");
    }

    public static String ensureEndsWithPunctuation(String snippet) {
        return snippet.matches(".*[.!?]$") ? snippet : snippet + "...";
    }

}
