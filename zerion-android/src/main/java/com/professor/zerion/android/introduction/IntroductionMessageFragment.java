package com.professor.zerion.android.introduction;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contact.ContactItem;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.view.TextInputView;
import com.professor.zerion.android.view.TextSendController;
import com.professor.zerion.android.view.TextSendController.SendListener;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.List;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import de.hdodenhof.circleimageview.CircleImageView;

import static android.app.Activity.RESULT_OK;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.professor.zerion.android.util.UiUtils.getContactDisplayName;
import static com.professor.zerion.android.util.UiUtils.hideSoftKeyboard;
import static com.professor.zerion.android.view.AuthorView.setAvatar;
import static com.professor.zerion.android.view.TextSendController.SendState;
import static com.professor.zerion.android.view.TextSendController.SendState.SENT;
import static org.briarproject.briar.api.introduction.IntroductionConstants.MAX_INTRODUCTION_TEXT_LENGTH;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class IntroductionMessageFragment extends BaseFragment
		implements SendListener {

	private static final String TAG =
			IntroductionMessageFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private IntroductionViewModel viewModel;

	private ViewHolder ui;

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(IntroductionViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {

		requireActivity().setTitle(R.string.introduction_message_title);

		View v = inflater.inflate(R.layout.introduction_message, container,
				false);
		ui = new ViewHolder(v);
		TextSendController sendController =
				new TextSendController(ui.message, this, true);
		ui.message.setSendController(sendController);
		ui.message.setMaxTextLength(MAX_INTRODUCTION_TEXT_LENGTH);
		ui.message.setReady(false);

		viewModel.getIntroductionInfo().observe(getViewLifecycleOwner(), ii -> {
			if (ii == null) {
				return;
			}
			setUpViews(ii.getContact1(), ii.getContact2(),
					ii.isPossible());
		});

		return v;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	private void setUpViews(ContactItem c1, ContactItem c2, boolean possible) {
		setAvatar(ui.avatar1, c1);
		setAvatar(ui.avatar2, c2);

		ui.contactName1.setText(getContactDisplayName(c1.getContact()));
		ui.contactName2.setText(getContactDisplayName(c2.getContact()));

		ui.progressBar.setVisibility(GONE);

		if (possible) {
			ui.notPossible.setVisibility(GONE);
			ui.message.setVisibility(VISIBLE);
			ui.message.setReady(true);
			ui.message.showSoftKeyboard();
		} else {
			ui.notPossible.setVisibility(VISIBLE);
			ui.message.setVisibility(GONE);
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			hideSoftKeyboard(ui.message);
			requireActivity().onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public LiveData<SendState> onSendClick(@Nullable String text,
			List<AttachmentHeader> headers, long expectedAutoDeleteTimer) {
		ui.message.setReady(false);

		viewModel.makeIntroduction(text);

		hideSoftKeyboard(ui.message);
		FragmentActivity activity = requireActivity();
		activity.setResult(RESULT_OK);
		activity.supportFinishAfterTransition();
		return new MutableLiveData<>(SENT);
	}

	private static class ViewHolder {

		private final ProgressBar progressBar;
		private final CircleImageView avatar1, avatar2;
		private final TextView contactName1, contactName2;
		private final TextView notPossible;
		private final TextInputView message;

		private ViewHolder(View v) {
			progressBar = v.findViewById(R.id.progressBar);
			avatar1 = v.findViewById(R.id.avatarContact1);
			avatar2 = v.findViewById(R.id.avatarContact2);
			contactName1 = v.findViewById(R.id.nameContact1);
			contactName2 = v.findViewById(R.id.nameContact2);
			notPossible = v.findViewById(R.id.introductionNotPossibleView);
			message = v.findViewById(R.id.introductionMessageView);
		}
	}
}
