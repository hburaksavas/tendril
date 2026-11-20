package dev.tendril.recorder;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ControlServerTest {

    @Test
    void parsesAFormAndDecodesValues() {
        Map<String, String> form = ControlServer.parse("transport=rabbit&routingKey=a%2Fb&h.X-Id=1+2&empty=");
        assertEquals("rabbit", form.get("transport"));
        assertEquals("a/b", form.get("routingKey"));
        assertEquals("1 2", form.get("h.X-Id"));
    }

    @Test
    void decodesTextAndBase64Bodies() {
        assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8),
                ControlServer.body(Map.of("body", "héllo")));
        assertArrayEquals(new byte[]{1, 2, 3},
                ControlServer.body(Map.of("body", "AQID", "bodyEncoding", "base64")));
        assertArrayEquals(new byte[0], ControlServer.body(Map.of()));
    }
}
