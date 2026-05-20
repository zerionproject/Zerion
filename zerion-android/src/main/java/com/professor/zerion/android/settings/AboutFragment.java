package com.professor.zerion.android.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.professor.zerion.BuildConfig;
import com.professor.zerion.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AboutFragment extends Fragment {

	final static String TAG = AboutFragment.class.getName();

	private TextView zerionVersion;
	private TextView torVersion;
	private TextView zerionWebsite;
	private TextView zerionSourceCode;
	private TextView zerionPrivacyPolicy;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_about, container,
				false);
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.about_title);
		zerionVersion = requireActivity().findViewById(R.id.zerion_version);
		zerionVersion.setText(
				getString(R.string.zerion_version, BuildConfig.VERSION_NAME));
		torVersion = requireActivity().findViewById(R.id.TorVersion);
		torVersion.setText(
				getString(R.string.tor_version, BuildConfig.TorVersion));
		zerionWebsite = requireActivity().findViewById(R.id.zerion_website);
		zerionSourceCode = requireActivity().findViewById(R.id.zerion_source_code);
		zerionPrivacyPolicy =
				requireActivity().findViewById(R.id.zerion_privacy_policy);
		zerionWebsite.setOnClickListener(v ->
				copyUrlToClipboard("https://zerion.chat"));
		zerionSourceCode.setOnClickListener(v ->
				copyUrlToClipboard("https://github.com/zerionproject"));
		zerionPrivacyPolicy.setOnClickListener(v ->
				copyUrlToClipboard("https://zerion.chat/privacy-policy.html"));
	}

	private void copyUrlToClipboard(String url) {
		ClipboardManager cm = (ClipboardManager)
				requireActivity().getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		ClipData clip = ClipData.newPlainText("Zerion URL", url);
		cm.setPrimaryClip(clip);
		Toast.makeText(requireActivity(),
				R.string.zerion_url_copied_open_in_tor_browser,
				Toast.LENGTH_LONG).show();
	}

}