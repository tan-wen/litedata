package com.litedata.web;

import com.litedata.dictionary.DictionaryService;
import java.util.*;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice(assignableTypes = {PageController.class, ModelingController.class, MetricController.class, AssetCatalogController.class})
public class DictionaryOptions {
    private final DictionaryService dictionaries;
    public DictionaryOptions(DictionaryService dictionaries) { this.dictionaries = dictionaries; }
    @ModelAttribute
    void options(Model model) {
        var choices = dictionaries.subjectAreas();
        var labels = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        choices.forEach(item -> labels.put(item.getValue(), item.getLabel()));
        model.addAttribute("subjectAreaChoices", choices);
        model.addAttribute("subjectAreaLabels", labels);
    }
}
