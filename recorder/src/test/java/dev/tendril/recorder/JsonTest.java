package dev.tendril.recorder;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void quotesAndEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\"", Json.quote("a\"b\\c\nd\te"));
        assertEquals("\"\\u0001\"", Json.quote("\u0001"));
    }

    @Test
    void buildsAnObjectAndSkipsNullStrings() {
        String json = Json.object().str("kind", "http_in").str("missing", null).num("status", 200).bool("ok", true).build();
        assertEquals("{\"kind\":\"http_in\",\"status\":200,\"ok\":true}", json);
    }

    @Test
    void masksSensitiveHeaders() {
        List<String[]> headers = Arrays.asList(
                new String[]{"Authorization", "Bearer abc"},
                new String[]{"Cookie", "sid=1"},
                new String[]{"Set-Cookie", "sid=1"},
                new String[]{"X-Api-Key", "k"},
                new String[]{"X-Auth-Token", "t"},
                new String[]{"Accept", "application/json"});
        String json = Json.object().headers("headers", headers).build();
        assertEquals("{\"headers\":[[\"Authorization\",\"***\"],[\"Cookie\",\"***\"],[\"Set-Cookie\",\"***\"],"
                + "[\"X-Api-Key\",\"***\"],[\"X-Auth-Token\",\"***\"],[\"Accept\",\"application/json\"]]}", json);
    }

    @Test
    void masksSecretFieldsInJsonBodies() {
        byte[] body = "{\"name\":\"Acme\",\"password\":\"hunter2\",\"Token\": \"abc\",\"clientSecret\":\"s\"}"
                .getBytes(StandardCharsets.UTF_8);
        String json = Json.object().body("body", body, "application/json").build();
        assertFalse(json.contains("hunter2"));
        assertFalse(json.contains("abc"));
        assertFalse(json.contains("\\\"s\\\""));
        assertTrue(json.contains("\\\"name\\\":\\\"Acme\\\""));
        assertTrue(json.contains("\"bodyEncoding\":\"text\""));
    }

    @Test
    void sendsBinaryBodiesAsBase64() {
        String json = Json.object().body("body", new byte[]{1, 2, 3}, "image/png").build();
        assertTrue(json.contains("\"body\":\"AQID\""));
        assertTrue(json.contains("\"bodyEncoding\":\"base64\""));
    }

    @Test
    void emptyBodiesAreOmitted() {
        assertEquals("{}", Json.object().body("body", new byte[0], "application/json").build());
        assertEquals("{}", Json.object().body("body", null, "application/json").build());
    }

    @Test
    void cutsLongBodiesAndSaysSo() {
        byte[] body = new byte[Config.MAX_BODY + 100];
        Arrays.fill(body, (byte) 'a');
        String json = Json.object().body("body", body, "text/plain").build();
        assertTrue(json.contains("\"bodyTruncated\":true"));
        assertTrue(json.length() < Config.MAX_BODY + 200);
    }

    @Test
    void recognisesTextContentTypes() {
        assertTrue(Json.isText(null));
        assertTrue(Json.isText("application/json;charset=UTF-8"));
        assertTrue(Json.isText("application/problem+json"));
        assertTrue(Json.isText("text/html"));
        assertTrue(Json.isText("application/x-www-form-urlencoded"));
        assertFalse(Json.isText("application/octet-stream"));
        assertFalse(Json.isText("image/png"));
    }
}
