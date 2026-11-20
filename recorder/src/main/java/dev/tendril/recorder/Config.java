package dev.tendril.recorder;

/** Settings handed over by the IDE through the agent arguments. */
final class Config {

    /** Bodies longer than this are cut. */
    static final int MAX_BODY = 64 * 1024;

    private Config() {
    }

    static String port() {
        return System.getProperty("tendril.recorder.port", "");
    }

    static String token() {
        return System.getProperty("tendril.recorder.token", "");
    }

    static String project() {
        return System.getProperty("tendril.recorder.project", "");
    }

    static boolean enabled() {
        return !port().isEmpty();
    }
}
