package com.litedata.dictionary;

public class DictionaryItem {
    private Long id;
    private String dictionaryCode = "", value = "", label = "", description = "";
    private int sortOrder, version;
    private boolean enabled = true;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getDictionaryCode() { return dictionaryCode; }
    public void setDictionaryCode(String value) { dictionaryCode = value; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getLabel() { return label; }
    public void setLabel(String value) { label = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int value) { sortOrder = value; }
    public int getVersion() { return version; }
    public void setVersion(int value) { version = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
}
