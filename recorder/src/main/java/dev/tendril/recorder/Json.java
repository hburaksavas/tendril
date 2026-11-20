package dev.tendril.recorder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/** Just enough JSON to write events, so the recorder needs no library on the application's class path. */
final class Json {

    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(\"(?:password|passwd|pwd|secret|client_secret|clientSecret|token|access_token|refresh_token|"
                    + "apiKey|api_key|authorization)\"\\s*:\\s*)\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    private final StringBuilder out = new StringBuilder("{");
    private boolean first = true;

    static Json object() {
        return new Json();
    }

    Json str(String key, String value) {
        if (value == null) {
            return this;
        }
        key(key).append(quote(value));
        return this;
    }

    Json num(String key, long value) {
        key(key).append(value);
        return this;
    }

    Json bool(String key, boolean value) {
        key(key).append(value);
        return this;
    }

    /** Header pairs as [["Name","value"], ...]; sensitive headers are masked. */
    Json headers(String key, List<String[]> headers) {
        StringBuilder sb = key(key).append('[');
        boolean firstHeader = true;
        for (String[] h : headers) {
            if (!firstHeader) {
                sb.append(',');
            }
            firstHeader = false;
            sb.append('[').append(quote(h[0])).append(',').append(quote(mask(h[0], h[1]))).append(']');
        }
        sb.append(']');
        return this;
    }

    /** A body as text when it looks like text, otherwise as base64; also reports truncation. */
    Json body(String key, byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return this;
        }
        boolean truncated = bytes.length > Config.MAX_BODY;
        int length = Math.min(bytes.length, Config.MAX_BODY);
        if (isText(contentType)) {
            str(key, maskBody(new String(bytes, 0, length, StandardCharsets.UTF_8)));
            str(key + "Encoding", "text");
        } else {
            str(key, Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes, length)));
            str(key + "Encoding", "base64");
        }
        if (truncated) {
            bool(key + "Truncated", true);
        }
        return this;
    }

    String build() {
        return out.append('}').toString();
    }

    private StringBuilder key(String key) {
        if (!first) {
            out.append(',');
        }
        first = false;
        return out.append(quote(key)).append(':');
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static boolean isText(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return true;
        }
        String t = contentType.toLowerCase();
        return t.contains("json") || t.contains("xml") || t.startsWith("text/") || t.contains("x-www-form-urlencoded")
                || t.contains("javascript") || t.contains("yaml");
    }

    private static String mask(String name, String value) {
        String n = name.toLowerCase();
        if (n.equals("authorization") || n.equals("proxy-authorization") || n.equals("cookie")
                || n.equals("set-cookie") || n.contains("api-key") || n.contains("apikey") || n.contains("token")) {
            return "***";
        }
        return value;
    }

    private static String maskBody(String body) {
        return SECRET_FIELD.matcher(body).replaceAll("$1\"***\"");
    }
}
