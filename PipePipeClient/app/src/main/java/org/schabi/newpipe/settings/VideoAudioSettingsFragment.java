package org.schabi.newpipe.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.Snackbar;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.CodecSupportDetector;
import org.schabi.newpipe.util.PermissionHelper;

import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class VideoAudioSettingsFragment extends BasePreferenceFragment {
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final CompositeDisposable disposables = new CompositeDisposable();

    @Override
    public void onCreatePreferences(@Nullable final Bundle savedInstanceState,
                                    @Nullable final String rootKey) {
        addPreferencesFromResourceRegistry();

        listener = (sharedPreferences, s) -> {
            if (s.equals(getString(R.string.minimize_on_exit_key))) {
                final String newSetting = sharedPreferences.getString(s, null);
                if (newSetting != null
                        && newSetting.equals(getString(R.string.minimize_on_exit_popup_key))
                        && !Settings.canDrawOverlays(getContext())) {

                    Snackbar.make(getListView(), R.string.permission_display_over_apps,
                                    Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.settings, view ->
                                    PermissionHelper.checkSystemAlertWindowPermission(getContext()))
                            .show();

                }
            }
        };

        final Preference detectPreference = findPreference(getString(R.string.detect_codecs_key));
        if (detectPreference != null) {
            detectPreference.setOnPreferenceClickListener(preference -> {
                runDetection(preference);
                return true;
            });
        }
    }

    /**
     * Runs the codec probe off the UI thread and presents the outcome in a confirm dialog.
     * Nothing is written until the user presses Apply; re-running never modifies the
     * stored set by itself.
     */
    private void runDetection(final Preference detectPreference) {
        detectPreference.setEnabled(false);
        detectPreference.setSummary(R.string.detect_codecs_running);
        disposables.add(Single.fromCallable(CodecSupportDetector::detectSync)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(results -> {
                    if (!isAdded()) {
                        return;
                    }
                    detectPreference.setEnabled(true);
                    detectPreference.setSummary(R.string.detect_codecs_summary);
                    showDetectResultDialog(results);
                }, error -> {
                    if (!isAdded()) {
                        return;
                    }
                    detectPreference.setEnabled(true);
                    detectPreference.setSummary(R.string.detect_codecs_summary);
                    Toast.makeText(requireContext(), R.string.general_error,
                            Toast.LENGTH_SHORT).show();
                }));
    }

    private void showDetectResultDialog(
            final Map<String, CodecSupportDetector.DetectionResult> results) {
        final CharSequence[] items = {
                rowLabel(CodecSupportDetector.TOGGLE_VP9,
                        results.get(CodecSupportDetector.TOGGLE_VP9)),
                rowLabel(CodecSupportDetector.TOGGLE_AV1,
                        results.get(CodecSupportDetector.TOGGLE_AV1))
        };
        final boolean[] checked = {
                CodecSupportDetector.isChecked(results.get(CodecSupportDetector.TOGGLE_VP9)),
                CodecSupportDetector.isChecked(results.get(CodecSupportDetector.TOGGLE_AV1))
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.codec_detect_dialog_title)
                .setMultiChoiceItems(items, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.codec_detect_apply, (dialog, which) -> {
                    final SharedPreferences prefs = PreferenceManager
                            .getDefaultSharedPreferences(requireContext());
                    final String formatsKey = getString(R.string.advanced_formats_key);
                    final Set<String> stored = prefs.getStringSet(formatsKey, null);
                    prefs.edit().putStringSet(formatsKey,
                            CodecSupportDetector.applyToStoredSet(
                                    stored, checked[0], checked[1])).apply();
                    Toast.makeText(requireContext(), R.string.codec_detect_applied,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private String rowLabel(final String toggle,
                            final CodecSupportDetector.DetectionResult result) {
        if (result != null
                && result.getSupport() == CodecSupportDetector.Support.SUPPORTED_HW) {
            return getString(R.string.codec_detect_row_hw, toggle);
        }
        if (result != null
                && result.getSupport() == CodecSupportDetector.Support.SUPPORTED_SW_ONLY) {
            return getString(R.string.codec_detect_row_sw_only, toggle);
        }
        return getString(R.string.codec_detect_row_unsupported, toggle);
    }

    @Override
    public boolean onPreferenceTreeClick(final Preference preference) {
        if (getString(R.string.caption_settings_key).equals(preference.getKey())) {
            try {
                startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
            } catch (final ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.general_error, Toast.LENGTH_SHORT).show();
            }
        }

        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void onDestroy() {
        disposables.clear();
        super.onDestroy();
    }
}
