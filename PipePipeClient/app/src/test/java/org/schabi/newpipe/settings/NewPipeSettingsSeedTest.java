package org.schabi.newpipe.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure seed-guard decisions: probe only from a pristine stored set equal to the static
 * default (absent key defers without marking done); write only when the current set
 * still equals the launch snapshot.
 */
public class NewPipeSettingsSeedTest {

    private static final Set<String> STATIC_DEFAULT =
            new HashSet<>(Collections.singletonList("VP9"));

    @Test
    public void pristineDefaultStartsProbe() {
        assertTrue(NewPipeSettings.shouldStartProbe(
                new HashSet<>(STATIC_DEFAULT), STATIC_DEFAULT));
    }

    @Test
    public void customizedSetSkipsProbe() {
        assertFalse(NewPipeSettings.shouldStartProbe(
                new HashSet<>(Arrays.asList("VP9", "AV01")), STATIC_DEFAULT));
        assertFalse(NewPipeSettings.shouldStartProbe(
                new HashSet<>(Collections.singletonList("AV01")), STATIC_DEFAULT));
        assertFalse(NewPipeSettings.shouldStartProbe(
                new HashSet<>(), STATIC_DEFAULT));
    }

    @Test
    public void absentKeyDoesNotStartProbe() {
        assertFalse(NewPipeSettings.shouldStartProbe(null, STATIC_DEFAULT));
    }

    @Test
    public void unchangedCurrentAllowsWrite() {
        final Set<String> snapshot = new HashSet<>(STATIC_DEFAULT);
        assertTrue(NewPipeSettings.shouldWriteSeed(new HashSet<>(snapshot), snapshot));
    }

    @Test
    public void changedOrAbsentCurrentAbortsWrite() {
        final Set<String> snapshot = new HashSet<>(STATIC_DEFAULT);
        assertFalse(NewPipeSettings.shouldWriteSeed(
                new HashSet<>(Arrays.asList("VP9", "AV01")), snapshot));
        assertFalse(NewPipeSettings.shouldWriteSeed(Collections.emptySet(), snapshot));
        assertFalse(NewPipeSettings.shouldWriteSeed(null, snapshot));
    }
}
