package com.zomdroid;

import org.junit.Test;
import static org.junit.Assert.*;

public class ServerLogMonitorTest {
    @Test public void startupAndShutdownMayBeSplitAcrossReads() {
        ServerLogMonitor m = new ServerLogMonitor();
        m.accept("*** SERVER STA"); assertFalse(m.ready);
        m.accept("RTED ****\n"); assertTrue(m.ready);
        m.accept("Shutdown handling started\n"); assertFalse(m.ready); assertFalse(m.shutdownFinished);
        m.accept("Shutdown handling finished\n"); assertTrue(m.shutdownFinished);
    }
    @Test public void oldSaveMustNotAcknowledgeANewRequest() {
        ServerLogMonitor m = new ServerLogMonitor();
        m.accept("Saving finish\n"); m.saving = true;
        m.accept(""); assertTrue(m.saving);
        m.accept("normal server output\n"); assertTrue(m.saving);
        m.accept("Saving fin"); assertTrue(m.saving);
        m.accept("ish\n"); assertFalse(m.saving);
    }
    @Test public void saveDoesNotMeanShutdown() {
        ServerLogMonitor m = new ServerLogMonitor();
        m.accept("*** SERVER STARTED ****\nSaving finish\n");
        assertTrue(m.ready); assertFalse(m.shutdownFinished);
    }
}
