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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelManager;
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
	ContactManager contactManager;
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
		adapter = new DelegationsAdapter(this::confirmRevoke);
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
			try {
				certs = channelManager.listActiveDelegations(channelId);
			} catch (DbException ex) {
				certs = new ArrayList<>();
			}
			List<ChannelDelegationCert> finalCerts = certs;
			runOnUiThread(() -> render(finalCerts));
		});
	}

	private void render(List<ChannelDelegationCert> certs) {
		adapter.setItems(certs);
		recycler.setVisibility(certs.isEmpty()
				? View.GONE : View.VISIBLE);
		emptyView.setVisibility(certs.isEmpty()
				? View.VISIBLE : View.GONE);
	}

	private void showAddDialog() {
		ioExecutor.execute(() -> {
			List<Contact> contacts;
			try {
				contacts = new ArrayList<>(contactManager.getContacts());
			} catch (DbException ex) {
				contacts = new ArrayList<>();
			}
			List<Contact> usable = new ArrayList<>();
			for (Contact c : contacts) {
				PublicKey pk = c.getAuthor().getPublicKey();
				if (pk instanceof HybridSignaturePublicKey) usable.add(c);
			}
			List<Contact> finalContacts = usable;
			runOnUiThread(() -> showAddPicker(finalContacts));
		});
	}

	private void showAddPicker(List<Contact> contacts) {
		CharSequence[] labels = new CharSequence[contacts.size() + 1];
		for (int i = 0; i < contacts.size(); i++) {
			labels[i] = contacts.get(i).getAuthor().getName();
		}
		labels[contacts.size()] = getString(
				R.string.channels_delegations_paste_key);
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.channels_delegations_add)
				.setItems(labels, (d, which) -> {
					if (which == contacts.size()) {
						showPasteKeyDialog();
					} else {
						addContactAsEditor(contacts.get(which));
					}
				})
				.show();
	}

	private void addContactAsEditor(Contact contact) {
		PublicKey pk = contact.getAuthor().getPublicKey();
		if (!(pk instanceof HybridSignaturePublicKey)) {
			Toast.makeText(this,
					R.string.channels_delegations_error_key,
					Toast.LENGTH_LONG).show();
			return;
		}
		HybridSignaturePublicKey hybrid = (HybridSignaturePublicKey) pk;
		byte[] ed25519 = hybrid.getEd25519PublicKey();
		byte[] mlDsa = hybrid.getMlDsaPublicKey();
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

	private void showPasteKeyDialog() {
		View dialogView = LayoutInflater.from(this).inflate(
				R.layout.dialog_add_delegation, null);
		TextInputEditText pubKeyInput = dialogView.findViewById(
				R.id.delegationPubKeyInput);
		new MaterialAlertDialogBuilder(this)
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
					R.string.channels_delegations_error_key,
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

	private void confirmRevoke(ChannelDelegationCert cert) {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.channels_delegations_revoke)
				.setPositiveButton(R.string.channels_delegations_revoke,
						(d, w) -> doRevoke(cert))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void doRevoke(ChannelDelegationCert cert) {
		ioExecutor.execute(() -> {
			try {
				channelManager.revokeDelegation(channelId,
						cert.getDelegationSeq());
				runOnUiThread(() -> {
					Toast.makeText(this,
							R.string.channels_delegations_revoked,
							Toast.LENGTH_LONG).show();
					refresh();
				});
			} catch (DbException ignored) {
			}
		});
	}

	private static class DelegationsAdapter
			extends RecyclerView.Adapter<DelegationViewHolder> {

		interface OnRevoke {
			void onRevoke(ChannelDelegationCert cert);
		}

		private List<ChannelDelegationCert> items = new ArrayList<>();
		private final OnRevoke onRevoke;

		DelegationsAdapter(OnRevoke onRevoke) {
			this.onRevoke = onRevoke;
		}

		void setItems(List<ChannelDelegationCert> certs) {
			this.items = certs;
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
			h.bind(cert);
			h.revokeButton.setOnClickListener(
					v -> onRevoke.onRevoke(cert));
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
		final MaterialButton revokeButton;

		DelegationViewHolder(@NonNull View itemView) {
			super(itemView);
			keyView = itemView.findViewById(R.id.delegationKeyView);
			validityView =
					itemView.findViewById(R.id.delegationValidityView);
			revokeButton =
					itemView.findViewById(R.id.delegationRevokeButton);
		}

		void bind(ChannelDelegationCert cert) {
			keyView.setText(toHexShort(
					cert.getDelegateeEd25519PubKey()));
			String validity = cert.isUnbounded()
					? itemView.getContext().getString(
							R.string.channels_delegations_validity_unbounded)
					: itemView.getContext().getString(
							R.string.channels_delegations_validity_label);
			validityView.setText(String.format(Locale.US,
					"%s · seq %d", validity, cert.getDelegationSeq()));
		}

		private static String toHexShort(byte[] b) {
			StringBuilder sb = new StringBuilder();
			int take = Math.min(b.length, 12);
			for (int i = 0; i < take; i++) {
				sb.append(String.format(Locale.US, "%02x", b[i]));
			}
			if (b.length > take) sb.append("…");
			return sb.toString();
		}
	}
}
