package com.litedata.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ServiceCurlCommandTest {
    @Test
    void createsRunnableCommandForServicesWithoutBusinessParameters() {
        assertThat(ServiceCurlCommand.create("https://data.example.test/api/data/orders", "ld_token", List.of()))
            .isEqualTo("curl --get 'https://data.example.test/api/data/orders' --header 'X-API-Key: ld_token'"
                + " --data-urlencode 'pageNum=1' --data-urlencode 'pageSize=20'");
    }

    @Test
    void preservesParameterOrderAndShellSpecialCharactersSafely() {
        String command = ServiceCurlCommand.create("https://data.example.test/api/data/o'rder?label=$`", "ld_'$`",
            List.of("customerId", "status", "customerId"));

        assertThat(command).isEqualTo("curl --get 'https://data.example.test/api/data/o'\"'\"'rder?label=$`'"
            + " --header 'X-API-Key: ld_'\"'\"'$`' --data-urlencode 'customerId=<customerId>'"
            + " --data-urlencode 'status=<status>' --data-urlencode 'pageNum=1' --data-urlencode 'pageSize=20'");
    }
}
