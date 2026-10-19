package com.professor.zerion.android.navdrawer;

import android.app.Dialog;
import android.os.Bundle;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class HelperTransportsDialogFragment extends DialogFragment {

	public static final String TAG = HelperTransportsDialogFragment.class.getName();
	private static final String ARG_COUNTRY = "country";

	public static HelperTransportsDialogFragment newInstance(String country) {
		HelperTransportsDialogFragment f = new HelperTransportsDialogFragment();
		Bundle args = new Bundle();
		args.putString(ARG_COUNTRY, country);
		f.setArguments(args);
		return f;
	}

	String country() {
		Bundle args = getArguments();
		String c = args == null ? null : args.getString(ARG_COUNTRY);
		return c == null ? "" : c;
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		setCancelable(false);
		String country = country();
		return new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.tor_helper_prompt_title)
				.setMessage(R.string.tor_helper_prompt_message)
				.setCancelable(false)
				.setPositiveButton(R.string.tor_helper_prompt_use,
						(d, w) -> answer(country, true))
				.setNegativeButton(R.string.tor_helper_prompt_not_now,
						(d, w) -> answer(country, false))
				.create();
	}

	private void answer(String country, boolean use) {
		if (getActivity() instanceof NavDrawerActivity) {
			((NavDrawerActivity) getActivity())
					.answerHelperTransports(country, use);
		}
	}
}
