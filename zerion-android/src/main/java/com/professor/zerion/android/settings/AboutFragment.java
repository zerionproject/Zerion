package com.professor.zerion.android.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.professor.zerion.BuildConfig;
import com.professor.zerion.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import static android.content.Intent.ACTION_VIEW;
import static com.professor.zerion.android.util.UiUtils.tryToStartActivity;

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
		zerionWebsite.setOnClickListener(v -> {
			String url = "https://zerion.chat";
			goToUrl(url);
		});
		zerionSourceCode.setOnClickListener(v -> {
			String url = "https://github.com/zerionproject";
			goToUrl(url);
		});
		zerionPrivacyPolicy.setOnClickListener(v -> {
			String url = "https://zerion.chat/privacy-policy.html";
			goToUrl(url);
		});
	}

	private void goToUrl(String url) {
		Intent i = new Intent(ACTION_VIEW);
		i.setData(Uri.parse(url));
		tryToStartActivity(requireActivity(), i);
	}

}