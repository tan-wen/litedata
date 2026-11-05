package com.litedata.web;

import java.util.LinkedHashSet;
import java.util.List;

final class ServiceCurlCommand {
    private ServiceCurlCommand() {
    }

    static String create(String endpoint, String apiKey, List<String> parameters) {
        var command = new StringBuilder("curl --get ").append(quote(endpoint))
            .append(" --header ").append(quote("X-API-Key: " + apiKey));
        for (String parameter : new LinkedHashSet<>(parameters))
            command.append(" --data-urlencode ").append(quote(parameter + "=<" + parameter + ">"));
        return command.append(" --data-urlencode 'pageNum=1' --data-urlencode 'pageSize=20'").toString();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
