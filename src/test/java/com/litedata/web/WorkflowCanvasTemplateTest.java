package com.litedata.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WorkflowCanvasTemplateTest {

    @Test
    void canvasTemplatePlacesReturnWorkflowAfterRunHistory() throws IOException {
        String template = Files.readString(Path.of("src/main/resources/templates/workflow-canvas.html"));

        // Ensure the old button is removed from the toolbar
        assertThat(template).doesNotContain("返回加工流程");

        // Ensure 【返回数据加工】 is placed after 【运行记录】 in the page heading actions
        Pattern pattern = Pattern.compile("运行记录</a>\\s*<a\\s+class=\"btn-outline theme-secondary\"\\s+th:href=\"@\\{/workflows}\">返回数据加工</a>");
        Matcher matcher = pattern.matcher(template);
        assertThat(matcher.find()).as("Expected '返回数据加工' link immediately following '运行记录' in workflow-canvas.html").isTrue();
    }
}
