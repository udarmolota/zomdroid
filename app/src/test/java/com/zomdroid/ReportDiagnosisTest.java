package com.zomdroid;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReportDiagnosisTest {
    private static boolean has(List<String> findings, String part) {
        for (String f : findings) if (f.contains(part)) return true;
        return false;
    }

    @Test
    public void quietLogGivesNothing() {
        List<String> f = ReportDiagnosis.diagnoseText("42", "NG_GL4ES", null,
                "LOG  : General > STATE: enter zombie.gameStates.MainScreenState\n");
        assertEquals(Collections.emptyList(), f);
        assertEquals("Diagnosis: nothing known found\n", ReportDiagnosis.format(f));
    }

    @Test
    public void knownSignaturesAreNamed() {
        String log = "java.lang.SecurityException: class \"zombie.core.Core\"'s signer information does not match\n"
                + "java.lang.NoSuchFieldError: FloorBloodTypes\n"
                + "LOG  : Mod          f:0 st:1> loading NC_NaturesCall\n"
                + "LOG  : Mod          f:0 st:1> loading SomeHarmlessMod\n";
        List<String> f = ReportDiagnosis.diagnoseText("42", "ZINK_ZFA", "killed by signal SIGABRT (6)", log);
        assertTrue(has(f, "signed projectzomboid.jar"));
        assertTrue(has(f, "BetterFPS"));
        assertTrue(has(f, "NC_NaturesCall"));
        assertTrue(has(f, "native crash/abort"));
        assertEquals(4, f.size());
    }

    @Test
    public void rendererForTheOtherBuild() {
        assertTrue(has(ReportDiagnosis.diagnoseText("42", "GL4ES", null, ""), "GL4ES is set on a Build 42"));
        assertTrue(has(ReportDiagnosis.diagnoseText("41", "MOBILEGLUES_EXPERIMENTAL", null, ""), "Build 41"));
        assertTrue(has(ReportDiagnosis.diagnoseText("42", "NG_GL4ES", "LOW MEMORY kill", ""), "killed the app for memory"));
    }
}
