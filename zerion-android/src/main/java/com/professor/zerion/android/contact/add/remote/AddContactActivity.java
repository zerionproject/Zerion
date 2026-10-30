package com.professor.zerion.android.contact.add.remote;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.contact.add.nearby.AddNearbyContactFragment;
import com.professor.zerion.android.contact.request.ScannedCode;
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
		BaseFragmentListener, AddNearbyContactFragment.LinkScannedHandler {

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	private AddContactViewModel viewModel;

	@Nullable
	private String pendingIncomingLink = null;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(AddContactViewModel.class);
	}

	public static final String EXTRA_RE_ADD_CONTACT =
			"com.professor.zerion.RE_ADD_CONTACT";

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_fragment_container_toolbar);
		setUpCustomToolbar(false);

		ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		getSupportFragmentManager().registerFragmentLifecycleCallbacks(
				new androidx.fragment.app.FragmentManager
						.FragmentLifecycleCallbacks() {
					@Override
					public void onFragmentStarted(
							androidx.fragment.app.FragmentManager fm,
							androidx.fragment.app.Fragment f) {
						int title = titleFor(f);
						if (title != 0) setTitle(title);
					}
				}, false);
		viewModel.onCreate();
		if (getIntent().getBooleanExtra(EXTRA_RE_ADD_CONTACT, false)) {
			viewModel.setReAddExisting(true);
		}

		viewModel.getRemoteLinkEntered().observeEvent(this, entered -> {
			if (entered) showNextFragment(new NicknameFragment());
		});

		viewModel.getQrExchangeChosen().observeEvent(this,
				v -> showNextFragment(viewModel.isTorConnected()
						? new QrExchangeFragment()
						: AddNearbyContactFragment.newInstance(true)));
		viewModel.getOfflineScanRoute().observeEvent(this,
				this::onOfflineScanRoute);
		viewModel.getSendInviteChosen().observeEvent(this,
				v -> showNextFragment(new SendInviteFragment()));

		if (state == null) {
			String text = extractLinkFromIntent(getIntent());
			String link = ScannedCode.extractLink(text);
			if (link != null) {
				viewModel.setInvitePrefill(link);
				showInitialFragment(new SendInviteFragment());
			} else {
				showInitialFragment(new AddContactChooserFragment());
				if (text != null) {
					Toast.makeText(this, R.string.invalid_link, LENGTH_LONG)
							.show();
				}
			}
		}
	}

	static int titleFor(androidx.fragment.app.Fragment f) {
		if (f instanceof SendInviteFragment) return R.string.send_invite_title;
		if (f instanceof AddContactChooserFragment
				|| f instanceof QrExchangeFragment
				|| f instanceof AddNearbyContactFragment) {
			return R.string.add_contact_screen_title;
		}
		return 0;
	}

	@Override
	public void onLinkScannedWithoutInternet(String text) {
		viewModel.onLinkScannedWithoutInternet(text);
	}

	private void onOfflineScanRoute(AddContactViewModel.ScanRoute route) {
		switch (route.getKind()) {
			case INVITE:
				viewModel.onSendInviteChosen(route.getText());
				break;
			case REFUSED:
				Toast.makeText(this, route.getMessage(), LENGTH_LONG).show();
				break;
			case IN_PERSON:
				Toast.makeText(this, R.string.scan_qr_link_needs_internet,
						LENGTH_LONG).show();
				break;
			default:
				break;
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

	private void handleIncomingLink(String text) {
		String link = ScannedCode.extractLink(text);
		if (link == null) {
			Toast.makeText(this, R.string.invalid_link, LENGTH_LONG).show();
			return;
		}
		viewModel.setInvitePrefill(link);
		showInitialFragment(new SendInviteFragment());
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
