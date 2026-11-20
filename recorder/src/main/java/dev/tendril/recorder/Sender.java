package dev.tendril.recorder;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Posts events to the IDE on a background thread. It never blocks the application: when the queue is full or the
 * IDE is not listening, events are dropped.
 */
final class Sender {

    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(500);
    private static volatile boolean started;

    private Sender() {
    }

    static void send(String json) {
        if (!Config.enabled()) {
            return;
        }
        start();
        QUEUE.offer(json);
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread thread = new Thread(Sender::loop, "tendril-recorder-sender");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        while (true) {
            try {
                String json = QUEUE.take();
                if (!post(json)) {
                    QUEUE.clear(); // the IDE is not there: do not pile up
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException ignored) {
                // never let the sender die
            }
        }
    }

    private static boolean post(String json) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + Config.port() + "/events").openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(2000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Tendril-Token", Config.token());
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            c.disconnect();
            return status < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
