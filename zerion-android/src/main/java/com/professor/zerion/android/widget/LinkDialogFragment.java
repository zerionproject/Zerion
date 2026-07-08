package com.professor.zerion.android.widget;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.professor.zerion.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;


import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import static java.util.Objects.requireNonNull;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class LinkDialogFragment extends DialogFragment {

	private static final String TAG = LinkDialogFragment.class.getName();

	private String url;

	public static LinkDialogFragment newInstance(String url) {
		LinkDialogFragment f = new LinkDialogFragment();

		Bundle args = new Bundle();
		args.putString("url", url);
		f.setArguments(args);

		return f;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Bundle args = requireArguments();
		url = requireNonNull(args.getString("url"));

		setStyle(STYLE_NO_TITLE, R.style.ZerionDialogTheme);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {

		View v = inflater.inflate(R.layout.fragment_link_dialog, container,
				false);

		TextView urlView = v.findViewById(R.id.urlView);
		urlView.setText(url);

		Context ctx = requireContext();

		Button openButton = v.findViewById(R.id.openButton);
		openButton.setOnClickListener(v1 -> {
			com.professor.zerion.android.util.BrowserGuard.openUrl(ctx, url);
			android.app.Dialog d = getDialog();
			if (d != null) d.dismiss();
		});

		Button cancelButton = v.findViewById(R.id.cancelButton);
		cancelButton.setOnClickListener(v1 -> {
			android.app.Dialog d = getDialog();
			if (d != null) d.cancel();
		});

		return v;
	}

	public String getUniqueTag() {
		return TAG;
	}

}
