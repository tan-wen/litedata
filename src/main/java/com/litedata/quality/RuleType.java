package com.litedata.quality;

public enum RuleType {
    NOT_NULL("非空", "字段不允许 NULL；多字段用逗号分隔"),
    NOT_BLANK("非空白", "字段不允许 NULL、空串或仅空格"),
    UNIQUE("唯一性 / 联合主键", "逗号分隔联合键；统计重复组中所有行，NULL 也参与分组"),
    RANGE("数值范围", "参数1最小值，参数2最大值，包含边界；NULL 另配非空规则"),
    LENGTH("字符长度", "参数1最小长度，参数2最大长度，包含边界"),
    REGEX("正则格式", "参数1为数据库 REGEXP 表达式；NULL 不参与"),
    ENUM("枚举 / 字典", "参数1为允许值，每行一个；NULL 不参与"),
    NON_NEGATIVE("非负数", "小于0视为异常"),
    POSITIVE("正数", "小于等于0视为异常"),
    FUTURE_TIME("未来时间", "时间晚于扫描时刻视为异常"),
    FRESHNESS("数据新鲜度", "参数1为最大延迟小时；检查 MAX(时间字段)，空表不通过"),
    ROW_COUNT("数据量范围", "参数1最小行数，参数2最大行数"),
    SUM_RANGE("合计范围", "参数1最小合计，参数2最大合计"),
    AVG_RANGE("均值范围", "参数1最小均值，参数2最大均值"),
    COMPARE_COLUMNS("字段间比较", "两字段用逗号分隔；参数1为期望关系：=、<>、>、>=、<、<="),
    REFERENCE("引用完整性", "配置同库引用表及字段，检查找不到引用的非空值"),
    ROW_COUNT_CHANGE("数据量波动", "参数1为相对上次同版本成功扫描的最大变化百分比；首批标记待基线"),
    CUSTOM_SQL("自定义异常 SQL", "仅 Doris；参数1为受控 SELECT，返回一行代表一条异常；不支持命名参数或时间窗口");
    private final String label;
    private final String hint;
    RuleType(String label,String hint) { this.label=label; this.hint=hint; }
    public String getLabel() { return label; }
    public String getHint() { return hint; }
}
