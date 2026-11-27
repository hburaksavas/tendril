package dev.tendril.recorder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The mocked responses the IDE sent, matched against outgoing Feign calls and incoming HTTP requests. The IDE sends
 * the whole set at once, already ordered (most specific first), and again whenever the application reconnects with
 * another version than the IDE's.
 */
final class Mocks {

    /** One mocked response and the calls it answers. */
    static final class Rule {
        String id = "";
        /** "feign" or "http". */
        String kind = "";
        /** Feign only: the configKey of the client method, as in {@code OrdersClient#get(String)}. */
        String key;
        String label = "";
        String method;
        /** Spring-style pattern: {@code {x}} or {@code *} is one segment, {@code **} the rest. Feign: a suffix of the path. */
        String path;
        final Map<String, String> query = new LinkedHashMap<>();
        final Map<String, String> headers = new LinkedHashMap<>();
        /** Feign only: the request body must contain this text. */
        String bodyContains;

        int status = 200;
        final List<String[]> responseHeaders = new ArrayList<>();
        byte[] body = new byte[0];
        long delayMs;
        /** Feign only: null, "timeout" (throws a read timeout after the delay) or "refused" (connection refused). */
        String fault;
    }

    private static volatile List<Rule> rules = List.of();
    private static volatile String version = "";

    private Mocks() {
    }

    static String version() {
        return version;
    }

    static List<Rule> rules() {
        return rules;
    }

    static void replace(List<Rule> newRules, String newVersion) {
        rules = List.copyOf(newRules);
        version = newVersion == null ? "" : newVersion;
    }

    /** The rule answering a Feign call, or null to make the real call. */
    static Rule feign(String configKey, String method, String url, Map<String, Collection<String>> headers, byte[] body) {
        List<Rule> current = rules;
        if (current.isEmpty()) {
            return null;
        }
        URI uri = parseUri(url);
        String path = uri == null ? "" : nullToEmpty(uri.getRawPath());
        Map<String, String> query = queryOf(uri == null ? null : uri.getRawQuery());
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        for (Rule rule : current) {
            if (!"feign".equals(rule.kind) || rule.key == null || !rule.key.equals(configKey)) {
                continue;
            }
            if (rule.method != null && !rule.method.equalsIgnoreCase(method)) {
                continue;
            }
            if (rule.path != null && !pathMatches(rule.path, path, true)) {
                continue;
            }
            if (!queryMatches(rule, query) || !headersMatch(rule, name -> first(headers, name))) {
                continue;
            }
            if (rule.bodyContains != null && !rule.bodyContains.isEmpty() && !text.contains(rule.bodyContains)) {
                continue;
            }
            return rule;
        }
        return null;
    }

    /** The rule answering an incoming request, or null to let the application serve it. */
    static Rule http(String method, String requestUri, String rawQuery, Function<String, String> header) {
        List<Rule> current = rules;
        if (current.isEmpty()) {
            return null;
        }
        Map<String, String> query = queryOf(rawQuery);
        for (Rule rule : current) {
            if (!"http".equals(rule.kind) || rule.path == null) {
                continue;
            }
            if (rule.method != null && !rule.method.equalsIgnoreCase(method)) {
                continue;
            }
            if (pathMatches(rule.path, nullToEmpty(requestUri), false) && queryMatches(rule, query) && headersMatch(rule, header)) {
                return rule;
            }
        }
        return null;
    }

    /** Waits for the delay of [rule]; an interrupt ends the wait and is kept. */
    static void delay(Rule rule) {
        if (rule.delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(rule.delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean pathMatches(String pattern, String path, boolean suffix) {
        List<String> p = segments(pattern);
        List<String> a = new ArrayList<>();
        for (String s : segments(path)) {
            a.add(URLDecoder.decode(s, StandardCharsets.UTF_8));
        }
        int rest = p.indexOf("**");
        if (rest >= 0) {
            // "**" takes the rest: compare what comes before it, from the start (or anywhere for a suffix)
            List<String> head = p.subList(0, rest);
            if (!suffix) {
                return a.size() >= head.size() && segmentsMatch(head, a.subList(0, head.size()));
            }
            for (int start = 0; start + head.size() <= a.size(); start++) {
                if (segmentsMatch(head, a.subList(start, start + head.size()))) {
                    return true;
                }
            }
            return false;
        }
        if (suffix) {
            return a.size() >= p.size() && segmentsMatch(p, a.subList(a.size() - p.size(), a.size()));
        }
        return a.size() == p.size() && segmentsMatch(p, a);
    }

    private static boolean segmentsMatch(List<String> pattern, List<String> actual) {
        for (int i = 0; i < pattern.size(); i++) {
            String s = pattern.get(i);
            boolean variable = s.equals("*") || (s.startsWith("{") && s.endsWith("}"));
            if (!variable && !s.equals(actual.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean queryMatches(Rule rule, Map<String, String> query) {
        for (Map.Entry<String, String> e : rule.query.entrySet()) {
            if (!e.getValue().equals(query.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean headersMatch(Rule rule, Function<String, String> header) {
        for (Map.Entry<String, String> e : rule.headers.entrySet()) {
            if (!e.getValue().equals(header.apply(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    static Map<String, String> queryOf(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(name, value);
        }
        return out;
    }

    private static String first(Map<String, Collection<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, Collection<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().iterator().next();
            }
        }
        return null;
    }

    private static URI parseUri(String url) {
        try {
            return url == null ? null : URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }

    /**
     * The form the IDE posts to {@code /mocks}: {@code version}, {@code count}, then per rule {@code <i>.<field>},
     * {@code <i>.q.<name>} and {@code <i>.h.<name>} for conditions and {@code <i>.rh.<n>} = {@code Name: value}
     * for response headers.
     */
    static List<Rule> parse(Map<String, String> form) {
        int count = Integer.parseInt(form.getOrDefault("count", "0"));
        List<Rule> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String p = i + ".";
            Rule rule = new Rule();
            rule.id = form.getOrDefault(p + "id", "");
            rule.kind = form.getOrDefault(p + "kind", "").toLowerCase(Locale.ROOT);
            rule.key = blankToNull(form.get(p + "key"));
            rule.label = form.getOrDefault(p + "label", "");
            rule.method = blankToNull(form.get(p + "method"));
            rule.path = blankToNull(form.get(p + "path"));
            rule.bodyContains = blankToNull(form.get(p + "bodyContains"));
            rule.status = Integer.parseInt(form.getOrDefault(p + "status", "200"));
            rule.delayMs = Long.parseLong(form.getOrDefault(p + "delayMs", "0"));
            rule.fault = blankToNull(form.get(p + "fault"));
            Map<String, String> body = new LinkedHashMap<>();
            body.put("body", form.getOrDefault(p + "body", ""));
            if (form.containsKey(p + "bodyEncoding")) {
                body.put("bodyEncoding", form.get(p + "bodyEncoding"));
            }
            rule.body = ControlServer.body(body);
            List<Map.Entry<Integer, String[]>> headers = new ArrayList<>();
            for (Map.Entry<String, String> e : form.entrySet()) {
                String k = e.getKey();
                if (k.startsWith(p + "q.")) {
                    rule.query.put(k.substring(p.length() + 2), e.getValue());
                } else if (k.startsWith(p + "h.")) {
                    rule.headers.put(k.substring(p.length() + 2), e.getValue());
                } else if (k.startsWith(p + "rh.")) {
                    int colon = e.getValue().indexOf(':');
                    if (colon > 0) {
                        headers.add(Map.entry(Integer.parseInt(k.substring(p.length() + 3)),
                                new String[]{e.getValue().substring(0, colon).trim(), e.getValue().substring(colon + 1).trim()}));
                    }
                }
            }
            headers.sort(Map.Entry.comparingByKey());
            headers.forEach(h -> rule.responseHeaders.add(h.getValue()));
            out.add(rule);
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
