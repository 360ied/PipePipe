package org.schabi.newpipe.player;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;

/** Persists the path inputs consumed by the real app process in click-to-first-frame benchmarks. */
@RunWith(AndroidJUnit4.class)
public final class YoutubeClickBenchmarkSetupTest {
    @Test
    public void configure() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String client = arguments.getString("youtubeClient", "mweb");
        // Anonymous-only: cookieFile input is no longer supported.
        final SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(context)
                .edit()
                .putString(context.getString(R.string.youtube_player_client_key), client);
        assertTrue("Could not persist click benchmark inputs", editor.commit());
    }
}
