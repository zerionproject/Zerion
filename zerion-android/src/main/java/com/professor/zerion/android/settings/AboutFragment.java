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

	private TextView briarVersion;
	private TextView torVersion;
	private TextView briarWebsite;
	private TextView briarSourceCode;
	private TextView briarChangelog;
	private TextView briarPrivacyPolicy;

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
		briarVersion = requireActivity().findViewById(R.id.BriarVersion);
		briarVersion.setText(
				getString(R.string.zerion_version, BuildConfig.VERSION_NAME));
		torVersion = requireActivity().findViewById(R.id.TorVersion);
		torVersion.setText(
				getString(R.string.tor_version, BuildConfig.TorVersion));
		briarWebsite = requireActivity().findViewById(R.id.BriarWebsite);
		briarSourceCode = requireActivity().findViewById(R.id.BriarSourceCode);
		briarChangelog = requireActivity().findViewById(R.id.BriarChangelog);
		briarPrivacyPolicy =
				requireActivity().findViewById(R.id.BriarPrivacyPolicy);
		briarWebsite.setOnClickListener(View -> {
			String url = "https://zerionapp.com";
			goToUrl(url);
		});
		briarSourceCode.setOnClickListener(View -> {
			String url = "https://github.com/zerion/zerion";
			goToUrl(url);
		});
		briarChangelog.setOnClickListener(View -> {
			String url = "https://zerionapp.com/changelog";
			goToUrl(url);
		});
		briarPrivacyPolicy.setOnClickListener(View -> {
			String url = "https://zerionapp.com/privacy";
			goToUrl(url);
		});
	}

	private void goToUrl(String url) {
		Intent i = new Intent(ACTION_VIEW);
		i.setData(Uri.parse(url));
		tryToStartActivity(requireActivity(), i);
	}

}