    @BeforeEach void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : List.of(
                    "quality_result","quality_run","quality_rule","quality_task",
                    "metadata_column","metadata_table","metadata_scope",
                    "sql_task_run","sql_task_dependency","sql_pipeline","sql_task",
                    "data_service","service_call")) {
                jdbc.execute("DROP TABLE IF EXISTS " + table);
            }
            jdbc.execute("DROP TABLE IF EXISTS quality_fixture"); jdbc.execute("DROP TABLE IF EXISTS quality_reference");
            jdbc.execute("DROP TABLE IF EXISTS quality_empty"); jdbc.execute("DROP TABLE IF EXISTS quality_window");
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        jdbc.execute("CREATE TABLE quality_reference(id BIGINT PRIMARY KEY)"); jdbc.update("INSERT INTO quality_reference VALUES(1)");
        jdbc.execute("CREATE TABLE quality_fixture(id BIGINT, code VARCHAR(20), amount DECIMAL(12,2), note VARCHAR(30), category VARCHAR(20), ref_id BIGINT, occurred_at DATETIME, ends_at INT)");
        jdbc.update("INSERT INTO quality_fixture VALUES(1,'A',10,'ok','good',1,?,?)", now().minusHours(1), 10);
        jdbc.update("INSERT INTO quality_fixture VALUES(1,'',-2,'too-long-note','bad',99,?,?)", now().plusHours(2), 1);
        jdbc.execute("CREATE TABLE quality_empty(occurred_at DATETIME)");
        jdbc.execute("CREATE TABLE quality_window(occurred_at DATETIME)");
        jdbc.update("INSERT INTO quality_window VALUES(?)", now().minusHours(2));
        jdbc.update("INSERT INTO quality_window VALUES(?)", now().minusMinutes(30));
        jdbc.update("INSERT INTO quality_window VALUES(?)", now().plusHours(2));
    }
