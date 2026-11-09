package com.litedata.web;

import com.litedata.dictionary.*;
import com.litedata.service.QueryPage;
import com.litedata.store.DictionaryMapper;
import java.security.Principal;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
@RequestMapping("/dictionaries")
public class DictionaryController {
    private final DictionaryService dictionaries;
    private final DictionaryMapper mapper;
    public DictionaryController(DictionaryService dictionaries, DictionaryMapper mapper) {
        this.dictionaries = dictionaries;
        this.mapper = mapper;
    }
    @ModelAttribute("activeNav") String activeNav() { return "dictionaries"; }
    @ModelAttribute("canManageUsers") boolean canManageUsers() { return true; }
    @InitBinder("draft")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("code", "name", "description", "value", "label", "sortOrder", "enabled", "version");
    }
    @GetMapping
    String list(@RequestParam Map<String, String> params, Model model) {
        String keyword = DictionaryService.keyword(params.get("keyword"));
        var page = ListPagination.prepare(model, "/dictionaries", params, mapper.count(keyword));
        model.addAttribute("keyword", keyword);
        model.addAttribute("dictionaries", mapper.page(keyword, page.pageSize(), page.offset()));
        return "dictionaries";
    }
    @GetMapping("/new")
    String create(@RequestParam Map<String, String> params, Model model) {
        context(model, params, "/dictionaries");
        return definitionForm(model, new DictionaryDefinition(), null);
    }
    @GetMapping("/{code}/edit")
    String edit(@PathVariable String code, @RequestParam Map<String, String> params, Model model) {
        context(model, params, "/dictionaries");
        return definitionForm(model, dictionaries.require(code), code);
    }
    @PostMapping
    String create(@ModelAttribute("draft") DictionaryDefinition draft, BindingResult errors,
                  @RequestParam Map<String, String> params, Model model, Principal principal, RedirectAttributes redirect) {
        return save(null, draft, errors, params, model, principal, redirect);
    }
    @PostMapping("/{code}")
    String update(@PathVariable String code, @ModelAttribute("draft") DictionaryDefinition draft, BindingResult errors,
                  @RequestParam Map<String, String> params, Model model, Principal principal, RedirectAttributes redirect) {
        dictionaries.require(code);
        return save(code, draft, errors, params, model, principal, redirect);
    }
    private String save(String code, DictionaryDefinition draft, BindingResult errors, Map<String, String> params,
                        Model model, Principal principal, RedirectAttributes redirect) {
        context(model, params, "/dictionaries");
        try {
            if (errors.hasErrors()) throw new IllegalArgumentException("字典表单格式无效");
            dictionaries.save(code, draft, principal.getName());
            redirect.addFlashAttribute("success", "字典已保存");
            return "redirect:" + model.getAttribute("listReturnPath");
        } catch (IllegalArgumentException e) { model.addAttribute("error", e.getMessage()); }
        catch (DuplicateKeyException e) { model.addAttribute("error", "字典编码已存在"); }
        return definitionForm(model, draft, code);
    }
    @GetMapping("/{code}/items")
    String items(@PathVariable String code, @RequestParam Map<String, String> params, Model model) {
        model.addAttribute("dictionary", dictionaries.require(code));
        String keyword = DictionaryService.keyword(params.get("keyword"));
        var page = ListPagination.prepare(model, "/dictionaries/" + code + "/items", params, mapper.itemCount(code, keyword));
        model.addAttribute("keyword", keyword);
        model.addAttribute("items", mapper.itemPage(code, keyword, page.pageSize(), page.offset()));
        return "dictionary-items";
    }
    @GetMapping("/{code}/items/new")
    String createItem(@PathVariable String code, @RequestParam Map<String, String> params, Model model) {
        context(model, params, "/dictionaries/" + code + "/items");
        return itemForm(model, new DictionaryItem(), code, null);
    }
    @GetMapping("/{code}/items/{id}/edit")
    String editItem(@PathVariable String code, @PathVariable long id, @RequestParam Map<String, String> params, Model model) {
        context(model, params, "/dictionaries/" + code + "/items");
        return itemForm(model, dictionaries.requireItem(code, id), code, id);
    }
    @PostMapping("/{code}/items")
    String createItem(@PathVariable String code, @ModelAttribute("draft") DictionaryItem draft, BindingResult errors,
                      @RequestParam Map<String, String> params, Model model, Principal principal, RedirectAttributes redirect) {
        return saveItem(code, null, draft, errors, params, model, principal, redirect);
    }
    @PostMapping("/{code}/items/{id}")
    String updateItem(@PathVariable String code, @PathVariable long id, @ModelAttribute("draft") DictionaryItem draft, BindingResult errors,
                      @RequestParam Map<String, String> params, Model model, Principal principal, RedirectAttributes redirect) {
        dictionaries.requireItem(code, id);
        return saveItem(code, id, draft, errors, params, model, principal, redirect);
    }
    private String saveItem(String code, Long id, DictionaryItem draft, BindingResult errors, Map<String, String> params,
                            Model model, Principal principal, RedirectAttributes redirect) {
        context(model, params, "/dictionaries/" + code + "/items");
        try {
            if (errors.hasErrors()) throw new IllegalArgumentException("字典项表单格式无效");
            dictionaries.saveItem(code, id, draft, principal.getName());
            redirect.addFlashAttribute("success", "字典项已保存");
            return "redirect:" + model.getAttribute("listReturnPath");
        } catch (IllegalArgumentException e) { model.addAttribute("error", e.getMessage()); }
        catch (DuplicateKeyException e) { model.addAttribute("error", "该字典中已存在相同编码"); }
        return itemForm(model, draft, code, id);
    }
    @PostMapping("/{code}/delete")
    String delete(@PathVariable String code, @RequestParam int version, @RequestParam Map<String, String> params,
                  Model model, Principal principal, RedirectAttributes redirect) {
        context(model, params, "/dictionaries");
        try { dictionaries.delete(code, version, principal.getName()); redirect.addFlashAttribute("success", "字典已删除"); }
        catch (IllegalArgumentException e) { redirect.addFlashAttribute("error", e.getMessage()); }
        return "redirect:" + model.getAttribute("listReturnPath");
    }
    @PostMapping("/{code}/items/{id}/delete")
    String deleteItem(@PathVariable String code, @PathVariable long id, @RequestParam int version,
                      @RequestParam Map<String, String> params, Model model, Principal principal, RedirectAttributes redirect) {
        context(model, params, "/dictionaries/" + code + "/items");
        try { dictionaries.deleteItem(code, id, version, principal.getName()); redirect.addFlashAttribute("success", "字典项已删除"); }
        catch (IllegalArgumentException e) { redirect.addFlashAttribute("error", e.getMessage()); }
        return "redirect:" + model.getAttribute("listReturnPath");
    }
    private static void context(Model model, Map<String, String> params, String path) {
        var page = QueryPage.parse(params, 20);
        String keyword = DictionaryService.keyword(params.get("keyword"));
        model.addAttribute("keyword", keyword);
        model.addAttribute("returnPageNum", page.pageNum());
        model.addAttribute("returnPageSize", page.pageSize());
        model.addAttribute("listReturnPath", UriComponentsBuilder.fromPath(path).queryParam("pageNum", page.pageNum())
            .queryParam("pageSize", page.pageSize()).queryParam("keyword", "{keyword}").encode().buildAndExpand(keyword).toUriString());
    }
    private String definitionForm(Model model, DictionaryDefinition draft, String code) {
        model.addAttribute("draft", draft);
        model.addAttribute("dictionaryCode", code);
        model.addAttribute("formAction", code == null ? "/dictionaries" : "/dictionaries/" + code);
        return "dictionary-form";
    }
    private String itemForm(Model model, DictionaryItem draft, String code, Long id) {
        model.addAttribute("draft", draft);
        model.addAttribute("dictionary", dictionaries.require(code));
        model.addAttribute("itemId", id);
        model.addAttribute("formAction", "/dictionaries/" + code + "/items" + (id == null ? "" : "/" + id));
        return "dictionary-item-form";
    }
}
