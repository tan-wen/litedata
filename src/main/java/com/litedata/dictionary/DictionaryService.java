package com.litedata.dictionary;

import com.litedata.store.DictionaryMapper;
import com.litedata.store.PlatformMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DictionaryService {
    public static final String SUBJECT_AREA = "SUBJECT_AREA";
    private final DictionaryMapper mapper;
    private final PlatformMapper platform;
    public DictionaryService(DictionaryMapper mapper, PlatformMapper platform) {
        this.mapper = mapper;
        this.platform = platform;
    }
    public DictionaryDefinition require(String code) {
        var item = mapper.definition(code);
        if (item == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "字典不存在");
        return item;
    }
    public DictionaryItem requireItem(String code, long id) {
        require(code);
        var item = mapper.item(code, id);
        if (item == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "字典项不存在");
        return item;
    }
    public List<DictionaryItem> subjectAreas() { return mapper.choices(SUBJECT_AREA); }
    public String subjectArea(String value, String previous, boolean required) {
        String key = text(value, 100, "主题域编码", required);
        if (key.isEmpty()) return key;
        var item = mapper.lockValue(SUBJECT_AREA, key);
        if (item == null || (!item.isEnabled() && !item.getValue().equals(previous)))
            throw new IllegalArgumentException("请选择数据字典中已启用的主题域");
        return item.getValue();
    }
    @Transactional
    public void save(String code, DictionaryDefinition draft, String actor) {
        draft.setCode(text(draft.getCode(), 50, "字典编码", true));
        if (!draft.getCode().matches("[A-Z][A-Z0-9_]{0,49}")) throw new IllegalArgumentException("字典编码使用大写字母、数字和下划线，并以字母开头");
        draft.setName(text(draft.getName(), 100, "字典名称", true));
        draft.setDescription(text(draft.getDescription(), 1000, "字典说明", false));
        if (code == null) mapper.insert(draft);
        else {
            code = require(code).getCode();
            if (!code.equals(draft.getCode())) throw new IllegalArgumentException("字典编码创建后不可修改");
            if (mapper.update(draft) != 1) throw stale();
        }
        platform.audit(actor, "DICTIONARY_SAVE", draft.getCode(), "SUCCESS");
    }
    @Transactional
    public void saveItem(String code, Long id, DictionaryItem draft, String actor) {
        code = require(code).getCode();
        draft.setDictionaryCode(code);
        draft.setId(id);
        draft.setValue(text(draft.getValue(), 100, "字典项编码", true));
        draft.setLabel(text(draft.getLabel(), 100, "字典项名称", true));
        draft.setDescription(text(draft.getDescription(), 1000, "字典项说明", false));
        if (draft.getSortOrder() < 0 || draft.getSortOrder() > 100000) throw new IllegalArgumentException("排序须在0到100000之间");
        if (id == null) mapper.insertItem(draft);
        else {
            var existing = mapper.lockItem(code, id);
            if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "字典项不存在");
            if (!existing.getValue().equals(draft.getValue())) throw new IllegalArgumentException("字典项编码创建后不可修改，名称可以调整");
            if (mapper.updateItem(draft) != 1) throw stale();
        }
        platform.audit(actor, "DICTIONARY_ITEM_SAVE", code + ":" + draft.getValue(), "SUCCESS");
    }
    @Transactional
    public void deleteItem(String code, long id, int version, String actor) {
        var item = mapper.lockItem(code, id);
        if (item == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "字典项不存在");
        if (item.getVersion() != version) throw stale();
        if (SUBJECT_AREA.equalsIgnoreCase(item.getDictionaryCode()) && mapper.subjectAreaReferences(item.getValue()) > 0)
            throw new IllegalArgumentException("字典项已被资产使用，请停用或先调整绑定");
        mapper.deleteItem(id);
        platform.audit(actor, "DICTIONARY_ITEM_DELETE", code + ":" + item.getValue(), "SUCCESS");
    }
    @Transactional
    public void delete(String code, int version, String actor) {
        var dictionary = mapper.lockDefinition(code);
        if (dictionary == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "字典不存在");
        if (SUBJECT_AREA.equalsIgnoreCase(dictionary.getCode())) throw new IllegalArgumentException("系统主题域字典不可删除，可自行维护字典项");
        if (dictionary.getVersion() != version) throw stale();
        if (mapper.itemCount(code, "") > 0) throw new IllegalArgumentException("请先删除该字典中的字典项");
        mapper.deleteDefinition(code);
        platform.audit(actor, "DICTIONARY_DELETE", code, "SUCCESS");
    }
    public static String keyword(String input) {
        try { return text(input, 100, "关键词", false).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
    private static String text(String input, int limit, String label, boolean required) {
        String value = Objects.toString(input, "").trim();
        if ((required && value.isEmpty()) || value.length() > limit || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + (required ? "不能为空，且" : "") + "最长" + limit + "字符，不能包含控制字符");
        return value;
    }
    private static IllegalArgumentException stale() { return new IllegalArgumentException("字典已被其他人更新，请刷新后重试"); }
}
