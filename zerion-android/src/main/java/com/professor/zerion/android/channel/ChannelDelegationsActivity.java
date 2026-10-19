package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelDelegationsActivity extends ZerionActivity {

	private static final String EXTRA_CHANNEL_ID =
			"com.professor.zerion.android.channel.DELEGATIONS_CHANNEL_ID";

	public static Intent intent(Context ctx, byte[] channelId) {
		Intent i = new Intent(ctx, ChannelDelegationsActivity.class);
		i.putExtra(EXTRA_CHANNEL_ID, channelId);
		return i;
	}

	@Inject
	ChannelManager channelManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private byte[] channelId = new byte[0];
	private RecyclerView recycler;
	private TextView emptyView;
	private DelegationsAdapter adapter;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		setContentView(R.layout.activity_channel_delegations);

		byte[] cid = getIntent().getByteArrayExtra(EXTRA_CHANNEL_ID);
		if (cid != null) channelId = cid;

		Toolbar toolbar = findViewById(R.id.delegationsToolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());

		recycler = findViewById(R.id.delegationsRecycler);
		emptyView = findViewById(R.id.delegationsEmptyView);
		adapter = new DelegationsAdapter(this::showEditor);
		recycler.setLayoutManager(new LinearLayoutManager(this));
		recycler.setAdapter(adapter);

		View addRow = findViewById(R.id.delegationsAddRow);
		addRow.setOnClickListener(v -> showAddDialog());

		TextView ownerName = findViewById(R.id.delegationsOwnerName);
		ownerName.setText(R.string.channels_delegations_role_you);
	}

	@Override
	public void onResume() {
		super.onResume();
		refresh();
	}

	private void refresh() {
		ioExecutor.execute(() -> {
			List<ChannelDelegationCert> certs;
			java.util.Map<String, String> names = new java.util.HashMap<>();
			try {
				certs = channelManager.listActiveDelegations(channelId);
			} catch (DbException ex) {
				certs = new ArrayList<>();
			}
			try {
				for (ChannelSubscriber sub
						: channelManager.getAnnouncedSubscribers(channelId)) {
					String name = sub.getDisplayName().trim();
					if (!name.isEmpty()) {
						names.put(hex(sub.getEd25519PubKey()), name);
					}
				}
			} catch (DbException ignored) {
			}
			List<ChannelDelegationCert> finalCerts = certs;
			runOnUiThread(() -> render(finalCerts, names));
		});
	}

	private static String hex(byte[] key) {
		return org.zerionproject.core.util.StringUtils.toHexString(key);
	}

	private void showEditor(ChannelDelegationCert cert, String name) {
		ChannelMemberSheet.open(this, channelManager, ioExecutor, channelId,
				cert.getDelegateeEd25519PubKey(), name,
				cert.getDelegateeMlDsaPubKey(), this::refresh);
	}

	private void render(List<ChannelDelegationCert> certs,
			java.util.Map<String, String> names) {
		adapter.setItems(certs, names);
		recycler.setVisibility(certs.isEmpty()
				? View.GONE : View.VISIBLE);
		emptyView.setVisibility(certs.isEmpty()
				? View.VISIBLE : View.GONE);
	}

	private void showAddDialog() {
		ioExecutor.execute(() -> {
			List<ChannelSubscriber> subs;
			try {
				subs = new ArrayList<>(
						channelManager.getAnnouncedSubscribers(channelId));
			} catch (DbException ex) {
				subs = new ArrayList<>();
			}
			List<ChannelSubscriber> usable = new ArrayList<>();
			for (ChannelSubscriber sub : subs) {
				if (!sub.isBanned()) usable.add(sub);
			}
			runOnUiThread(() -> showAddPicker(usable));
		});
	}

	private void showAddPicker(List<ChannelSubscriber> subs) {
		List<String> names = pickerLabels(this, subs);
		CharSequence[] labels = new CharSequence[subs.size() + 1];
		for (int i = 0; i < subs.size(); i++) labels[i] = names.get(i);
		labels[subs.size()] = getString(
				R.string.channels_delegations_paste_id);
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.channels_delegations_add)
				.setItems(labels, (d, which) -> {
					if (which == subs.size()) {
						showPasteKeyDialog();
					} else {
						ChannelSubscriber sub = subs.get(which);
						ChannelMemberSheet.open(this, channelManager,
								ioExecutor, channelId, sub.getEd25519PubKey(),
								sub.getDisplayName(), sub.getMlDsaPubKey(),
								this::refresh);
					}
				})
				.show();
	}

	static List<String> pickerLabels(Context ctx,
			List<ChannelSubscriber> subs) {
		List<String> names = new ArrayList<>();
		java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		for (ChannelSubscriber sub : subs) {
			String name = sub.getDisplayName().trim();
			if (name.isEmpty()) {
				name = ctx.getString(
						R.string.channels_delegations_unnamed_person);
			}
			names.add(name);
			Integer seen = counts.get(name);
			counts.put(name, seen == null ? 1 : seen + 1);
		}
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < subs.size(); i++) {
			String name = names.get(i);
			long at = subs.get(i).getJoinedAtHourMs();
			Integer same = counts.get(name);
			if (same != null && same > 1 && at > 0L) {
				name = ctx.getString(R.string.channels_label_parts, name,
						ctx.getString(R.string.channels_delegations_shared_on,
								android.text.format.DateUtils.formatDateTime(
										ctx, at, android.text.format.DateUtils
												.FORMAT_SHOW_DATE
												| android.text.format
												.DateUtils.FORMAT_ABBREV_MONTH)));
			}
			labels.add(name);
		}
		return labels;
	}

	private void showPasteKeyDialog() {
		View dialogView = LayoutInflater.from(this).inflate(
				R.layout.dialog_add_delegation, null);
		TextInputEditText pubKeyInput = dialogView.findViewById(
				R.id.delegationPubKeyInput);
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.channels_delegations_add)
				.setView(dialogView)
				.setPositiveButton(R.string.channels_delegations_add,
						(d, w) -> handleAdd(pubKeyInput))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void handleAdd(TextInputEditText input) {
		String pasted = input.getText() == null
				? "" : input.getText().toString().trim();
		byte[] decoded;
		try {
			decoded = decodeHybridPubKey(pasted);
		} catch (IllegalArgumentException ex) {
			Toast.makeText(this,
					R.string.channels_delegations_error_id,
					Toast.LENGTH_LONG).show();
			return;
		}
		byte[] ed25519 = new byte[32];
		byte[] mlDsa = new byte[decoded.length - 32];
		System.arraycopy(decoded, 0, ed25519, 0, 32);
		System.arraycopy(decoded, 32, mlDsa, 0, mlDsa.length);

		ioExecutor.execute(() -> {
			try {
				channelManager.delegatePublisher(channelId, ed25519,
						mlDsa, 0L);
				runOnUiThread(() -> {
					Toast.makeText(this,
							R.string.channels_delegations_added,
							Toast.LENGTH_SHORT).show();
					refresh();
				});
			} catch (DbException ex) {
				runOnUiThread(() -> Toast.makeText(this,
						R.string.channels_delegations_error_full,
						Toast.LENGTH_LONG).show());
			}
		});
	}

	private byte[] decodeHybridPubKey(String pasted) {
		String compact = pasted.replaceAll("\\s+", "")
				.toLowerCase(Locale.ROOT);
		if (compact.isEmpty()) {
			throw new IllegalArgumentException();
		}
		byte[] decoded = decodeBase32(compact);
		if (decoded.length < 64) {
			throw new IllegalArgumentException();
		}
		return decoded;
	}

	private static byte[] decodeBase32(String s) {
		int[] dec = new int[128];
		java.util.Arrays.fill(dec, -1);
		String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
		for (int i = 0; i < alphabet.length(); i++) {
			dec[alphabet.charAt(i)] = i;
		}
		int bitLen = s.length() * 5;
		byte[] out = new byte[bitLen / 8];
		int buffer = 0;
		int bitsLeft = 0;
		int outIdx = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= dec.length || dec[c] < 0) {
				throw new IllegalArgumentException();
			}
			buffer = (buffer << 5) | dec[c];
			bitsLeft += 5;
			if (bitsLeft >= 8) {
				out[outIdx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
				bitsLeft -= 8;
			}
		}
		return out;
	}

	private static class DelegationsAdapter
			extends RecyclerView.Adapter<DelegationViewHolder> {

		interface OnOpen {
			void onOpen(ChannelDelegationCert cert, String name);
		}

		private List<ChannelDelegationCert> items = new ArrayList<>();
		private java.util.Map<String, String> names =
				new java.util.HashMap<>();
		private final OnOpen onOpen;

		DelegationsAdapter(OnOpen onOpen) {
			this.onOpen = onOpen;
		}

		void setItems(List<ChannelDelegationCert> certs,
				java.util.Map<String, String> names) {
			this.items = certs;
			this.names = names;
			notifyDataSetChanged();
		}

		@NonNull
		@Override
		public DelegationViewHolder onCreateViewHolder(
				@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext()).inflate(
					R.layout.list_item_channel_delegation, parent,
					false);
			return new DelegationViewHolder(v);
		}

		@Override
		public void onBindViewHolder(@NonNull DelegationViewHolder h,
				int position) {
			ChannelDelegationCert cert = items.get(position);
			String name = names.get(hex(cert.getDelegateeEd25519PubKey()));
			h.bind(cert, name);
			h.itemView.setOnClickListener(v -> onOpen.onOpen(cert,
					name == null ? "" : name));
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private static class DelegationViewHolder
			extends RecyclerView.ViewHolder {

		final TextView keyView;
		final TextView validityView;

		DelegationViewHolder(@NonNull View itemView) {
			super(itemView);
			keyView = itemView.findViewById(R.id.delegationKeyView);
			validityView =
					itemView.findViewById(R.id.delegationValidityView);
		}

		void bind(ChannelDelegationCert cert, @Nullable String name) {
			Context ctx = itemView.getContext();
			keyView.setText(name != null ? name
					: ctx.getString(R.string.channels_delegations_unnamed));
			validityView.setText(cert.isUnbounded()
					? ctx.getString(
							R.string.channels_delegations_validity_unbounded)
					: ctx.getString(R.string.channels_delegations_valid_until,
							android.text.format.DateUtils.formatDateTime(ctx,
									cert.getValidUntilHourMs(),
									android.text.format.DateUtils
											.FORMAT_SHOW_DATE
											| android.text.format.DateUtils
											.FORMAT_SHOW_YEAR)));
		}
	}
}
