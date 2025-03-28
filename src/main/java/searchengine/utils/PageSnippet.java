package searchengine.utils;

import lombok.Data;

import java.util.List;

@Data
public class PageSnippet {

    List<String> snippet;

    private int pageId;
}
