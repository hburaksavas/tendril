package dev.tendril.recorder;

import java.lang.instrument.Instrumentation;

/**
 * Entry point of {@code -javaagent:tendril-recorder.jar=port=17843,token=abc,project=xyz}.
 * It only stores its arguments as system properties. The JVM puts the agent jar on the system class path,
 * so Spring Boot finds {@link RecorderAutoConfiguration} through the jar's AutoConfiguration.imports file.
 */
public final class RecorderAgent {

    private RecorderAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        if (args != null) {
            for (String pair : args.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    System.setProperty("tendril.recorder." + pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        System.err.println("[tendril] recorder attached, sending events to port " + Config.port());
    }
}
