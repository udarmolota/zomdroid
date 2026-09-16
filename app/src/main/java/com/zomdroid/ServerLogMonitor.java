package com.zomdroid;

/** Incremental line parser. Already consumed save markers must never acknowledge a new request. */
final class ServerLogMonitor {
    private String partial = "";
    boolean ready;
    boolean saving;
    boolean shutdownFinished;
    void accept(String text) {
        String joined = partial + text;
        int start = 0, end;
        while ((end = joined.indexOf('\n', start)) >= 0) {
            String line = joined.substring(start, end);
            if (line.contains("SERVER STARTED")) ready = true;
            if (line.contains("Saving finish")) saving = false;
            if (line.contains("Shutdown handling started")) ready = false;
            if (line.contains("Shutdown handling finished")) { ready = false; shutdownFinished = true; }
            start = end + 1;
        }
        partial = joined.substring(Math.max(start, joined.length() - 8192));
    }
}
