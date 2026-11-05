package com.litedata.web;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

class ListPaginationTest {
    @Test
    void smallListsShowEveryPageAndEmptyListsKeepOneDisabledPage() {
        var model = prepare(68, 2, 20);
        assertThat(numbers(model)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(model).containsEntry("hasPrevious", true).containsEntry("hasMore", true);
        var empty = prepare(0, 99, 20);
        assertThat(numbers(empty)).containsExactly(1L);
        assertThat(empty).containsEntry("pageNum", 1).containsEntry("totalPages", 1L)
            .containsEntry("hasPrevious", false).containsEntry("hasMore", false);
    }

    @Test
    void longListsKeepFirstLastAndCurrentPagesInABoundedWindow() {
        assertThat(numbers(prepare(2000, 1, 20))).containsExactly(1L, 2L, 3L, 4L, 5L, 0L, 100L);
        assertThat(numbers(prepare(2000, 50, 20))).containsExactly(1L, 0L, 49L, 50L, 51L, 0L, 100L);
        assertThat(numbers(prepare(2000, 100, 20))).containsExactly(1L, 0L, 96L, 97L, 98L, 99L, 100L);
        for (int page = 1; page <= 100; page++) {
            var pages = numbers(prepare(2000, page, 20));
            assertThat(pages).hasSize(7).startsWith(1L).endsWith(100L).contains((long) page);
            assertThat(pages.stream().filter(number -> number != 0).toList()).isSorted().doesNotHaveDuplicates();
        }
    }

    @Test
    void hugePageCountsDoNotGenerateAnUnboundedListOrOverflow() {
        var model = prepare(Integer.MAX_VALUE, Integer.MAX_VALUE, 1);
        assertThat(numbers(model)).hasSize(7).endsWith((long) Integer.MAX_VALUE);
        assertThat(model).containsEntry("pageNum", Integer.MAX_VALUE).containsEntry("hasMore", false);
        assertThat(prepare(Long.MAX_VALUE, 1, 20)).containsEntry("totalPages", Long.MAX_VALUE / 20 + 1);
    }

    private static ExtendedModelMap prepare(long total, int page, int size) {
        var model = new ExtendedModelMap();
        ListPagination.prepare(model, "/models", Map.of("pageNum", Integer.toString(page), "pageSize", Integer.toString(size)), total);
        return model;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> numbers(ExtendedModelMap model) {
        return (List<Long>) model.get("pageNumbers");
    }
}
