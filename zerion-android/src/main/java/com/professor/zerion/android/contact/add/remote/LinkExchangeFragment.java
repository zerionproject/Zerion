package com.professor.zerion.android.contact.add.remote;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.view.InfoView;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.regex.Matcher;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.core.app.ShareCompat.IntentBuilder;
import androidx.lifecycle.ViewModelProvider;

import static android.content.Context.CLIPBOARD_SERVICE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.LINK_REGEX;
import static com.professor.zerion.android.util.UiUtils.hideViewOnSmallScreen;
import static com.professor.zerion.android.util.UiUtils.observeOnce;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class LinkExchangeFragment extends BaseFragment {

	private static final String TAG = LinkExchangeFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private AddContactViewModel viewModel;


	private ClipboardManager clipboard;
	private TextInputLayout linkInputLayout;
	private TextInputEditText linkInput;
	private final Handler clipboardHandler = new Handler(Looper.getMainLooper());

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(AddContactViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		if (getActivity() == null || getContext() == null) return null;

		View v = inflater.inflate(R.layout.fragment_link_exchange,
				container, false);

		linkInputLayout = v.findViewById(R.id.linkInputLayout);
		linkInput = v.findViewById(R.id.linkInput);

		if (viewModel.getRemoteHandshakeLink() != null) {
			linkInput.setText(viewModel.getRemoteHandshakeLink());
		}

		clipboard = (ClipboardManager)
				requireContext().getSystemService(CLIPBOARD_SERVICE);

		Button pasteButton = v.findViewById(R.id.pasteButton);
		pasteButton.setOnClickListener(view -> {
			ClipData clipData = clipboard.getPrimaryClip();
			if (clipData != null && clipData.getItemCount() > 0)
				linkInput.setText(clipData.getItemAt(0).getText());
		});

		observeOnce(viewModel.getHandshakeLink(), this,
				this::onHandshakeLinkLoaded);
		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		hideViewOnSmallScreen(requireView().findViewById(R.id.imageView));
	}

	private void onHandshakeLinkLoaded(String link) {
		View v = requireView();

		TextView linkView = v.findViewById(R.id.linkView);
		linkView.setText(link);

		Button copyButton = v.findViewById(R.id.copyButton);
		copyButton.setOnClickListener(view -> {
			com.professor.zerion.android.util.SecureClipboard.copy(
					requireContext(),
					getString(R.string.link_clip_label), link);
			Toast.makeText(getContext(), R.string.link_copied_toast,
					LENGTH_SHORT).show();
		});
		copyButton.setEnabled(true);

		Button shareButton = v.findViewById(R.id.shareButton);
		shareButton.setOnClickListener(view -> {
			try {
				IntentBuilder.from(requireActivity())
						.setText(link)
						.setType("text/plain")
						.startChooser();
			} catch (ActivityNotFoundException e) {
				Toast.makeText(requireContext(),
						R.string.error_start_activity, LENGTH_LONG).show();
			}
		});
		shareButton.setEnabled(true);

		InfoView infoText = v.findViewById(R.id.infoView);
		infoText.setText(R.string.info_both_must_enter_links);

		Button continueButton = v.findViewById(R.id.addButton);
		continueButton.setOnClickListener(view -> onContinueButtonClicked());
		continueButton.setEnabled(true);
	}

	@Override
	public void onDestroyView() {
		clipboardHandler.removeCallbacksAndMessages(null);
		View v = getView();
		if (v != null) {
			TextView linkView = v.findViewById(R.id.linkView);
			if (linkView != null) linkView.setText("");
			if (linkInput != null) linkInput.setText("");
			Button copyButton = v.findViewById(R.id.copyButton);
			if (copyButton != null) copyButton.setOnClickListener(null);
			Button shareButton = v.findViewById(R.id.shareButton);
			if (shareButton != null) shareButton.setOnClickListener(null);
			Button addButton = v.findViewById(R.id.addButton);
			if (addButton != null) addButton.setOnClickListener(null);
			Button pasteButton = v.findViewById(R.id.pasteButton);
			if (pasteButton != null) pasteButton.setOnClickListener(null);
		}
		clipboard = null;
		linkInput = null;
		linkInputLayout = null;
		super.onDestroyView();
		System.gc();
	}

	@Nullable
	private String getRemoteHandshakeLinkOrNull() {
		CharSequence link = linkInput.getText();
		if (link == null || link.length() == 0) {
			linkInputLayout.setError(getString(R.string.missing_link));
			linkInput.requestFocus();
			return null;
		}

		Matcher matcher = LINK_REGEX.matcher(link);
		if (matcher.find()) {
			String linkWithoutSchema = matcher.group(1);
			if (("zerion://" + linkWithoutSchema)
					.equals(viewModel.getHandshakeLink().getValue())) {
				linkInputLayout.setError(getString(R.string.own_link_error));
				linkInput.requestFocus();
				return null;
			}
			linkInputLayout.setError(null);
			return "zerion://" + linkWithoutSchema;
		}
		linkInputLayout.setError(getString(R.string.invalid_link));
		linkInput.requestFocus();
		return null;
	}

	private void onContinueButtonClicked() {
		String link = getRemoteHandshakeLinkOrNull();
		if (link == null) return;

		viewModel.setRemoteHandshakeLink(link);
		viewModel.onRemoteLinkEntered();
	}

}
