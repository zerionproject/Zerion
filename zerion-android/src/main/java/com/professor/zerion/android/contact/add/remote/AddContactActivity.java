package com.professor.zerion.android.contact.add.remote;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.appcompat.app.ActionBar;
import androidx.lifecycle.ViewModelProvider;

import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.EXTRA_TEXT;
import static android.widget.Toast.LENGTH_LONG;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AddContactActivity extends ZerionActivity implements
		BaseFragmentListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	private AddContactViewModel viewModel;

	// A zerion:// link received via share intent before the ViewModel is ready
	@Nullable
	private String pendingIncomingLink = null;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(AddContactViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_fragment_container_toolbar);
		setUpCustomToolbar(false);

		ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		viewModel.onCreate();

		// Navigate to NicknameFragment once a remote link has been confirmed
		// (triggered by both the URL path and the QR path)
		viewModel.getRemoteLinkEntered().observeEvent(this, entered -> {
			if (entered) showNextFragment(new NicknameFragment());
		});

		// Chooser card selections
		viewModel.getQrExchangeChosen().observeEvent(this,
				v -> showNextFragment(new QrExchangeFragment()));
		viewModel.getLinkExchangeChosen().observeEvent(this,
				v -> showNextFragment(new LinkExchangeFragment()));

		if (state == null) {
			Intent i = getIntent();
			String incomingLink = extractLinkFromIntent(i);
			if (incomingLink != null && viewModel.isValidRemoteContactLink(incomingLink)) {
				// Opened via a shared zerion:// link — skip chooser, go to URL flow
				viewModel.setRemoteHandshakeLink(incomingLink);
				showInitialFragment(new LinkExchangeFragment());
			} else {
				// Normal entry — show the method chooser
				showInitialFragment(new AddContactChooserFragment());
			}
			if (incomingLink != null && !viewModel.isValidRemoteContactLink(incomingLink)) {
				Toast.makeText(this, R.string.invalid_link, LENGTH_LONG).show();
			}
		}
	}

	@Override
	protected void onNewIntent(Intent i) {
		super.onNewIntent(i);
		String link = extractLinkFromIntent(i);
		if (link == null) return;
		handleIncomingLink(link);
	}

	@Nullable
	private String extractLinkFromIntent(Intent i) {
		String action = i.getAction();
		if (ACTION_SEND.equals(action) || ACTION_VIEW.equals(action)) {
			String text = i.getStringExtra(EXTRA_TEXT);
			if (text != null) return text;
			return i.getDataString();
		}
		return null;
	}

	private void handleIncomingLink(String link) {
		String ownLink = viewModel.getHandshakeLink().getValue();
		if (link.equals(ownLink)) {
			Toast.makeText(this, R.string.intent_own_link, LENGTH_LONG).show();
		} else if (viewModel.isValidRemoteContactLink(link)) {
			viewModel.setRemoteHandshakeLink(link);
		} else {
			Toast.makeText(this, R.string.invalid_link, LENGTH_LONG).show();
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

}
