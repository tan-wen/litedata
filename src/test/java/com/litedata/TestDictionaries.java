package com.litedata;

import org.springframework.jdbc.core.JdbcTemplate;

final class TestDictionaries {
    static void seed(JdbcTemplate jdbc) {
        for (String value : java.util.List.of("交易", "交易域", "交易主题", "测试", "测试域", "园区", "交易 + 核心&资产", "unclassified"))
            jdbc.update("INSERT IGNORE INTO data_dictionary_item(dictionary_code,value,label) VALUES('SUBJECT_AREA',?,?)", value, value);
    }
}
