package com.litedata.scheduling;

import java.util.regex.Pattern;

/** Deliberately suppress credential/configuration lines instead of echoing arbitrary secret values. */
public final class LogRedactor {
    private static final Pattern SENSITIVE=Pattern.compile("(?i)(password|passwd|pwd|token|secret|api[-_]?key|authorization|credential|encryption[-_]?key|environmentConfig)\\s*[\\\"']?\\s*[:=]");
    private static final Pattern CONNECTION=Pattern.compile("(?i)(jdbc:[^\\s\\\"']+|https?://[^\\s/@]+:[^\\s/@]+@[^\\s]+)");
    private static final Pattern CONTROL=Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]|[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private LogRedactor() {}
    public static String redact(String text) {
        StringBuilder result=new StringBuilder();
        for(String line:text.split("\n",-1)) {
            String clean=CONTROL.matcher(line).replaceAll("");
            result.append(SENSITIVE.matcher(clean).find()?"[敏感配置已隐藏]":CONNECTION.matcher(clean).replaceAll("[连接地址已隐藏]")).append('\n');
        }
        if(!result.isEmpty()) result.setLength(result.length()-1);
        return result.toString();
    }
}
