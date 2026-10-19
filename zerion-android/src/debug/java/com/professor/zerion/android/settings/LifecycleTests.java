package com.professor.zerion.android.settings;

import android.app.Activity;
import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.professor.zerion.R;
import com.professor.zerion.android.navdrawer.PluginViewModel;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.plugin.tor.B4DebugAccess;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.plugin.tor.B4RotationDiagnostics;
import org.zerionproject.core.plugin.tor.auth.OnionAuthDebugAccess;
import org.zerionproject.core.plugin.tor.auth.OnionAuthRecord;
import org.zerionproject.core.plugin.tor.auth.OnionAuthStore;
import org.zerionproject.core.plugin.tor.auth.OnionAuthStore.ServiceRecord;
import org.zerionproject.core.plugin.tor.auth.OnionClientAuthManagerImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;

@NotNullByDefault
public class LifecycleTests {

	static final long[] AGE_STEPS_MS = {
			TimeUnit.HOURS.toMillis(1),
			TimeUnit.DAYS.toMillis(1),
			TimeUnit.DAYS.toMillis(7),
			TimeUnit.DAYS.toMillis(30)
	};
	static final int PREFIX_LENGTH = 6;

	@Inject
	B4OnionRotation rotation;
	@Inject
	OnionClientAuthManagerImpl clientAuth;
	@Inject
	OnionAuthStore authStore;
	@Inject
	SettingsManager settingsManager;
	@Inject
	DatabaseComponent db;
	@Inject
	AccountManager accountManager;
	@Inject
	ContactManager contactManager;
	@Inject
	TransportPropertyManager transportPropertyManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;
	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Nullable
	private Fragment fragment;

	LifecycleTests() {
	}

	public static void attach(Fragment fragment, View root) {
		if (!(root instanceof ViewGroup)) return;
		ViewGroup scroll = (ViewGroup) root;
		if (scroll.getChildCount() == 0
				|| !(scroll.getChildAt(0) instanceof ViewGroup)) {
			return;
		}
		ViewGroup content = (ViewGroup) scroll.getChildAt(0);
		LifecycleTests tests = new LifecycleTests();
		getAndroidComponent(fragment.requireContext()).inject(tests);
		tests.bind(fragment, content);
	}

	private void bind(Fragment f, ViewGroup content) {
		fragment = f;
		View card = LayoutInflater.from(f.requireContext()).inflate(
				R.layout.debug_lifecycle_tests, content, false);
		content.addView(card);
		card.findViewById(R.id.lifecycle_age_card)
				.setOnClickListener(v -> chooseAge());
		card.findViewById(R.id.lifecycle_renew_card)
				.setOnClickListener(v -> renew());
		card.findViewById(R.id.lifecycle_details_card)
				.setOnClickListener(v -> chooseContact());
		card.findViewById(R.id.lifecycle_force_card)
				.setOnClickListener(v -> confirmForceComplete());
		bindSwitch(card.findViewById(R.id.lifecycle_skip_auth_switch),
				LifecycleSwitches.SKIP_NEW_NEGOTIATIONS);
		bindSwitch(card.findViewById(R.id.lifecycle_hold_switch),
				LifecycleSwitches.HOLD_CONFIRMATIONS);
	}

	@Nullable
	private Context context() {
		Fragment f = fragment;
		return f == null ? null : f.getContext();
	}

	private void runOnUi(Runnable r) {
		Fragment f = fragment;
		Activity a = f == null ? null : f.getActivity();
		if (a == null) return;
		a.runOnUiThread(() -> {
			if (f.getContext() != null) r.run();
		});
	}

	private void toast(Context app, String text) {
		runOnUi(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
	}

	private void bindSwitch(SwitchMaterial s, String key) {
		ioExecutor.execute(() -> {
			boolean on = false;
			try {
				on = settingsManager.getSettings(LifecycleSwitches.NAMESPACE)
						.getBoolean(key, false);
			} catch (DbException ignored) {
			}
			boolean value = on;
			runOnUi(() -> {
				s.setChecked(value);
				s.setOnCheckedChangeListener((b, checked) -> store(key,
						checked));
			});
		});
	}

	private void store(String key, boolean on) {
		ioExecutor.execute(() -> {
			Settings s = new Settings();
			s.putBoolean(key, on);
			try {
				settingsManager.mergeSettings(s, LifecycleSwitches.NAMESPACE);
			} catch (DbException ignored) {
			}
		});
	}

	private void chooseAge() {
		Context ctx = context();
		if (ctx == null) return;
		String[] labels = ctx.getResources().getStringArray(
				R.array.lifecycle_age_choices);
		new SecureAlertDialogBuilder(ctx)
				.setTitle(R.string.lifecycle_age_title)
				.setItems(labels, (d, which) -> age(AGE_STEPS_MS[which],
						labels[which]))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void age(long deltaMs, String label) {
		Context ctx = context();
		if (ctx == null) return;
		Context app = ctx.getApplicationContext();
		ioExecutor.execute(() -> {
			String text;
			try {
				LifecycleClock.age(db, settingsManager, accountManager,
						authStore, deltaMs);
				B4DebugAccess.evaluateNow(rotation);
				OnionAuthDebugAccess.checkNow(clientAuth);
				text = app.getString(R.string.lifecycle_age_done, label);
			} catch (DbException | RuntimeException e) {
				text = app.getString(R.string.lifecycle_failed);
			}
			toast(app, text);
		});
	}

	private void renew() {
		Context ctx = context();
		if (ctx == null) return;
		Context app = ctx.getApplicationContext();
		ioExecutor.execute(() -> {
			int message;
			try {
				ServiceRecord svc = db.transactionWithResult(true,
						authStore::loadService);
				if (svc.onion == null) {
					message = R.string.lifecycle_renew_no_address;
				} else if (svc.oldOnion != null || svc.revocationPending) {
					message = R.string.lifecycle_renew_running;
				} else {
					clientAuth.rotateAuthorizedService();
					message = R.string.lifecycle_renew_started;
				}
			} catch (DbException e) {
				message = R.string.lifecycle_failed;
			}
			toast(app, app.getString(message));
		});
	}

	private void confirmForceComplete() {
		Context ctx = context();
		if (ctx == null) return;
		new SecureAlertDialogBuilder(ctx)
				.setTitle(R.string.lifecycle_force_confirm_title)
				.setMessage(R.string.lifecycle_force_confirm_message)
				.setPositiveButton(R.string.lifecycle_force_confirm_action,
						(d, w) -> forceComplete())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void forceComplete() {
		Context ctx = context();
		if (ctx == null) return;
		Context app = ctx.getApplicationContext();
		ioExecutor.execute(() -> {
			boolean promoted = false;
			try {
				promoted = B4DebugAccess.forceComplete(rotation);
			} catch (DbException ignored) {
			}
			toast(app, app.getString(promoted
					? R.string.lifecycle_force_done
					: R.string.lifecycle_force_not_announcing));
			boolean refresh = promoted;
			runOnUi(() -> {
				Fragment f = fragment;
				if (refresh && f != null && f.getActivity() != null) {
					new ViewModelProvider(f.requireActivity(),
							viewModelFactory).get(PluginViewModel.class)
							.refreshTorState();
				}
			});
		});
	}

	private void chooseContact() {
		Context ctx = context();
		if (ctx == null) return;
		Context app = ctx.getApplicationContext();
		ioExecutor.execute(() -> {
			List<Contact> contacts;
			try {
				contacts = new ArrayList<>(contactManager.getContacts());
			} catch (DbException e) {
				contacts = Collections.emptyList();
			}
			List<Contact> list = contacts;
			if (list.isEmpty()) {
				toast(app, app.getString(R.string.lifecycle_details_none));
				return;
			}
			String[] names = new String[list.size()];
			for (int i = 0; i < names.length; i++) {
				Contact c = list.get(i);
				String alias = c.getAlias();
				names[i] = alias == null ? c.getAuthor().getName() : alias;
			}
			runOnUi(() -> {
				Context now = context();
				if (now == null) return;
				new SecureAlertDialogBuilder(now)
						.protectSecrets()
						.setTitle(R.string.lifecycle_details_choose)
						.setItems(names, (d, which) ->
								showDetails(list.get(which).getId()))
						.setNegativeButton(R.string.cancel, null)
						.show();
			});
		});
	}

	private void showDetails(ContactId c) {
		Context ctx = context();
		if (ctx == null) return;
		Context app = ctx.getApplicationContext();
		ioExecutor.execute(() -> {
			String text;
			try {
				text = describe(app, c);
			} catch (DbException | RuntimeException e) {
				text = app.getString(R.string.lifecycle_failed);
			}
			String body = text;
			runOnUi(() -> {
				Context now = context();
				if (now == null) return;
				new SecureAlertDialogBuilder(now)
						.protectSecrets()
						.setTitle(R.string.lifecycle_details_title)
						.setMessage(body)
						.setPositiveButton(android.R.string.ok, null)
						.show();
			});
		});
	}

	static String prefix(@Nullable String onion, String none) {
		if (onion == null || onion.isEmpty()) return none;
		return onion.substring(0, Math.min(PREFIX_LENGTH, onion.length()));
	}

	private String describe(Context app, ContactId c) throws DbException {
		String none = app.getString(R.string.lifecycle_detail_none);
		String yes = app.getString(R.string.lifecycle_detail_yes);
		String no = app.getString(R.string.lifecycle_detail_no);
		B4RotationDiagnostics rot = rotation.diagnostics(c);
		String pending = rotation.getPendingOnionForContact(c);
		String[] sealed = db.transactionWithResult(true, txn -> {
			Settings s = settingsManager.getSettings(txn,
					B4_SETTINGS_NAMESPACE);
			SecretKey key = accountManager.getDatabaseKey();
			if (key == null) return new String[2];
			return new String[] {
					LifecycleClock.open(key, s.get(
							B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX
									+ c.getInt())),
					LifecycleClock.open(key, s.get(
							B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX
									+ c.getInt()))};
		});
		String dial = transportPropertyManager.getRemoteProperties(c,
				TorConstants.ID).get(TorConstants.PROP_ONION_V3);
		boolean authorizedRoute = clientAuth.getDialOnion(c) != null;
		OnionAuthRecord r = db.transactionWithResult(true,
				txn -> authStore.load(txn, c));
		ServiceRecord svc = db.transactionWithResult(true,
				authStore::loadService);
		String retry = none;
		if (sealed[1] != null) {
			try {
				retry = DateUtils.getRelativeTimeSpanString(
						Long.parseLong(sealed[1]), System.currentTimeMillis(),
						DateUtils.MINUTE_IN_MILLIS).toString();
			} catch (NumberFormatException ignored) {
			}
		}
		int route = authorizedRoute ? R.string.lifecycle_route_authorized
				: pending != null && pending.equals(dial)
				? R.string.lifecycle_route_announced
				: R.string.lifecycle_route_open;
		StringBuilder b = new StringBuilder();
		b.append(app.getString(R.string.lifecycle_detail_own_rotation,
				rot.getPhase().name(), rot.isAnnounced() ? yes : no));
		b.append('\n').append(app.getString(
				R.string.lifecycle_detail_peer_state,
				rot.getPeerState().name()));
		b.append('\n').append(app.getString(R.string.lifecycle_detail_pending,
				prefix(pending, none)));
		b.append('\n').append(app.getString(
				R.string.lifecycle_detail_failures,
				sealed[0] == null ? "0" : sealed[0]));
		b.append('\n').append(app.getString(R.string.lifecycle_detail_retry,
				retry));
		b.append('\n').append(app.getString(
				R.string.lifecycle_detail_next_dial, prefix(dial, none),
				app.getString(route)));
		b.append("\n\n").append(app.getString(
				R.string.lifecycle_detail_auth_state, r.state.name()));
		b.append('\n').append(app.getString(
				R.string.lifecycle_detail_auth_flags,
				r.lockedIn() ? yes : no, r.commitSent ? yes : no,
				r.peerCommitReceived ? yes : no, r.rekeying ? yes : no,
				r.probing ? yes : no, r.rotateAcked ? yes : no));
		b.append('\n').append(app.getString(
				R.string.lifecycle_detail_auth_repairs, r.repairs,
				r.silenceRepairs, r.openDials));
		b.append('\n').append(app.getString(
				R.string.lifecycle_detail_generations, svc.gen, r.localGen,
				r.peerGen));
		b.append('\n').append(app.getString(
				R.string.lifecycle_detail_old_authorized,
				svc.oldOnion != null ? yes : no,
				svc.revocationPending ? yes : no));
		return b.toString();
	}
}
