package org.schabi.newpipe.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.CodecSupportDetector;
import org.schabi.newpipe.util.DeviceUtils;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

/*
 * Created by k3b on 07.01.2016.
 *
 * Copyright (C) Christian Schabesberger 2015 <chris.schabesberger@mailbox.org>
 * NewPipeSettings.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * Helper class for global settings.
 */
public final class NewPipeSettings {
    private static final String TAG = "NewPipeSettings";

    private NewPipeSettings() { }

    public static void initSettings(final Context context) {
        // check if there are entries in the prefs to determine whether this is the first app run
        Boolean isFirstRun = null;
        final Set<String> prefsKeys = PreferenceManager.getDefaultSharedPreferences(context)
                .getAll().keySet();
        for (final String key: prefsKeys) {
            // ACRA stores some info in the prefs during app initialization
            // which happens before this method is called. Therefore ignore ACRA-related keys.
            if (!key.toLowerCase().startsWith("acra")) {
                isFirstRun = false;
                break;
            }
        }
        if (isFirstRun == null) {
            isFirstRun = true;
        }

        // first run migrations, then setDefaultValues, since the latter requires the correct types
        SettingMigrations.initMigrations(context, isFirstRun);
        PipePipeMigrations.initMigrations(context, isFirstRun);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String rotationFullscreenKey = context.getString(
                R.string.rotation_controls_fullscreen_key);
        if (!prefs.contains(rotationFullscreenKey)) {
            prefs.edit().putBoolean(rotationFullscreenKey, !DeviceUtils.isTablet(context)).apply();
        }

        // readAgain is true so that if new settings are added their default value is set
        PreferenceManager.setDefaultValues(context, R.xml.main_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.video_audio_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.download_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.appearance_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.history_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.content_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.player_notification_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.debug_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.sponsor_block_category_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.sponsor_block_settings, true);
        PreferenceManager.setDefaultValues(context, R.xml.advanced_settings, true);

        maybeSeedCodecSupport(context);

        saveDefaultVideoDownloadDirectory(context);
        saveDefaultAudioDownloadDirectory(context);
    }

    /**
     * One-time seed of the {@code advanced_formats} VP9/AV01 toggles from the device's
     * actual decoders, run off the UI thread. The static {@code [VP9]} default stays as
     * the safe value until the probe lands; playback menus rebuild per video, so the
     * window is self-healing.
     *
     * <p>Three best-effort guards protect manual choices, all required.</p>
     * <ol>
     * <li>Launch guard: the probe only starts from a pristine stored set (a fresh
     * install still holding the static default, or an untouched upgrade). A set equal
     * to the static default — explicit same-as-default choices included, they are
     * indistinguishable — is treated as pristine. A customized set is never probed;
     * it is just marked done. An absent key (backup-restore, partial clear) defers
     * without marking done, so the next launch retries.</li>
     * <li>Completion guard: at write time the current set must still equal the launch
     * snapshot, so a toggle made during the async probe window aborts the write.
     * Best-effort: {@code SharedPreferences} offers no compare-and-set, so a write
     * racing the final {@code apply()} itself can still win; the window is that call,
     * after a multi-hundred-ms probe.</li>
     * <li>Durability: {@code codec_seed_done} is written only on completion. The prefs
     * already exist by the second launch, therefore a kill inside the probe window
     * retries whenever the flag is absent.</li>
     * </ol>
     */
    private static void maybeSeedCodecSupport(final Context context) {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(context);
        final String seedDoneKey = context.getString(R.string.codec_seed_done_key);
        if (prefs.getBoolean(seedDoneKey, false)) {
            return;
        }
        final String formatsKey = context.getString(R.string.advanced_formats_key);
        if (!prefs.contains(formatsKey)) {
            // Absent key (backup-restore, partial clear): retry next launch,
            // do not mark done.
            return;
        }
        final Set<String> staticDefault = new HashSet<>(Arrays.asList(
                context.getResources()
                        .getStringArray(R.array.default_advanced_formats_values)));
        final Set<String> launchSnapshot = copyStringSet(prefs.getStringSet(formatsKey, null));
        if (!shouldStartProbe(launchSnapshot, staticDefault)) {
            // Customized set (upgraded install, or a choice made before seeding):
            // never overwrite it, just record that seeding is done.
            prefs.edit().putBoolean(seedDoneKey, true).apply();
            return;
        }
        final Context appContext = context.getApplicationContext();
        Completable.fromAction(() -> seedCodecSupportSync(
                        appContext, formatsKey, seedDoneKey, launchSnapshot))
                .subscribeOn(Schedulers.io())
                .subscribe(() -> {
                }, error -> Log.w(TAG, "Codec seed failed", error));
    }

    private static void seedCodecSupportSync(final Context context,
                                             final String formatsKey,
                                             final String seedDoneKey,
                                             final Set<String> launchSnapshot) {
        final Map<String, CodecSupportDetector.DetectionResult> results =
                CodecSupportDetector.detectSync();
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.contains(formatsKey)) {
            // Key vanished mid-probe: do not mark done, retry next launch.
            return;
        }
        final Set<String> current = copyStringSet(prefs.getStringSet(formatsKey, null));
        final SharedPreferences.Editor editor = prefs.edit();
        if (shouldWriteSeed(current, launchSnapshot)) {
            final Set<String> seeded = CodecSupportDetector.applyToStoredSet(
                    CodecSupportDetector.isChecked(
                            results.get(CodecSupportDetector.TOGGLE_VP9)),
                    CodecSupportDetector.isChecked(
                            results.get(CodecSupportDetector.TOGGLE_AV1)));
            editor.putStringSet(formatsKey, seeded);
            // Field triage lives here (Android-only caller), not in the JVM-tested
            // detector: log the outcome, never the probe internals.
            Log.i(TAG, "Codec seed wrote advanced formats: " + seeded);
        } else {
            Log.i(TAG, "Codec seed skipped: stored set changed during probe");
        }
        editor.putBoolean(seedDoneKey, true);
        editor.apply();
    }

    /**
     * Pure launch decision for {@link #maybeSeedCodecSupport}: probe only from a stored
     * set equal to the static default. A {@code null} stored set returns
     * {@code false}; absent-key deferral (retry without marking done) is owned by
     * the {@code contains} early-returns in the callers, which never pass
     * {@code null} here. Package-visible for JVM tests.
     */
    static boolean shouldStartProbe(@Nullable final Set<String> stored,
                                    final Set<String> staticDefault) {
        return stored != null && stored.equals(staticDefault);
    }

    /**
     * Pure write decision for {@link #seedCodecSupportSync}: write only when the
     * current set still equals the launch snapshot. A {@code null} current set
     * returns {@code false}. Package-visible for JVM tests.
     */
    static boolean shouldWriteSeed(@Nullable final Set<String> current,
                                   final Set<String> launchSnapshot) {
        return current != null && current.equals(launchSnapshot);
    }

    private static Set<String> copyStringSet(@Nullable final Set<String> value) {
        return value == null ? new HashSet<>() : new HashSet<>(value);
    }

    static void saveDefaultVideoDownloadDirectory(final Context context) {
        saveDefaultDirectory(context, R.string.download_path_video_key,
                Environment.DIRECTORY_MOVIES);
    }

    static void saveDefaultAudioDownloadDirectory(final Context context) {
        saveDefaultDirectory(context, R.string.download_path_audio_key,
                Environment.DIRECTORY_MUSIC);
    }

    private static void saveDefaultDirectory(final Context context, final int keyID,
                                             final String defaultDirectoryName) {
        if (!useStorageAccessFramework(context)) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            final String key = context.getString(keyID);
            final String downloadPath = prefs.getString(key, null);
            if (!isNullOrEmpty(downloadPath)) {
                return;
            }

            final SharedPreferences.Editor spEditor = prefs.edit();
            spEditor.putString(key, getDefaultChildFolderPathForDir(getDir(defaultDirectoryName)));
            spEditor.apply();
        }
    }

    @NonNull
    public static File getDir(final String defaultDirectoryName) {
        return new File(Environment.getExternalStorageDirectory(), defaultDirectoryName);
    }

    private static String getDefaultChildFolderPathForDir(final File dir) {
        return new File(dir, "NanoPipe").toURI().toString();
    }

    public static boolean useStorageAccessFramework(final Context context) {
        // There's a FireOS bug which prevents SAF open/close dialogs from being confirmed with a
        // remote (see #6455).
        if (DeviceUtils.isFireTv()) {
            return false;
        }
        return true;
    }

    private static boolean showSearchSuggestions(final Context context,
                                                 final SharedPreferences sharedPreferences,
                                                 @StringRes final int key) {
        final Set<String> enabledSearchSuggestions = sharedPreferences.getStringSet(
                context.getString(R.string.show_search_suggestions_key), null);

        if (enabledSearchSuggestions == null) {
            return true; // defaults to true
        } else {
            return enabledSearchSuggestions.contains(context.getString(key));
        }
    }

    public static boolean showLocalSearchSuggestions(final Context context,
                                                     final SharedPreferences sharedPreferences) {
        return showSearchSuggestions(context, sharedPreferences,
                R.string.show_local_search_suggestions_key);
    }

    public static boolean showRemoteSearchSuggestions(final Context context,
                                                      final SharedPreferences sharedPreferences) {
        return showSearchSuggestions(context, sharedPreferences,
                R.string.show_remote_search_suggestions_key);
    }

    public static int getSearchSuggestionsCount(final Context context,
                                               final SharedPreferences sharedPreferences) {
        final String countString = sharedPreferences.getString(
                context.getString(R.string.search_suggestions_count_key), "25");
        try {
            final int count = Integer.parseInt(countString);
            return Math.max(1, Math.min(count, 1000));
        } catch (final NumberFormatException e) {
            return 25;
        }
    }
}
