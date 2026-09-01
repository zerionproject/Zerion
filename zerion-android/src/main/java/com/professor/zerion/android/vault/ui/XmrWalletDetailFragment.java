package com.professor.zerion.android.vault.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contact.add.remote.QrCodeUtils;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.wallet.xmr.MoneroEngine;
import com.professor.zerion.android.vault.wallet.xmr.MoneroUri;
import com.professor.zerion.android.vault.wallet.xmr.XmrError;
import com.professor.zerion.android.vault.wallet.xmr.XmrPrice;
import com.professor.zerion.android.vault.wallet.xmr.XmrReceiveAddress;
import com.professor.zerion.android.vault.wallet.xmr.XmrSendUiState;
import com.professor.zerion.android.vault.wallet.xmr.XmrSyncState;
import com.professor.zerion.android.vault.wallet.xmr.XmrSyncStatus;
import com.professor.zerion.android.vault.wallet.xmr.XmrTxInfo;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

/**
 * Opened Monero wallet screen, structured to match the Bitcoin wallet: a
 * centered balance and sync status, the same circular Send / Receive / Refresh
 * actions (Send is present but disabled until a later phase), and a history
 * list using the same row layout. Receive is subaddress-first.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class XmrWalletDetailFragment extends BaseFragment {

	public static final String TAG = "XmrWalletDetailFragment";
	private static final String ARG_ID = "walletId";
	private static final String ARG_NAME = "walletName";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private XmrViewModel viewModel;
	private String walletId = "";
	private String walletName = "";

	private TextView balanceValue;
	private TextView unlockedValue;
	private TextView syncStatus;
	private TextView backupNotice;
	private LinearLayout historyContainer;
	private TextView historyEmpty;

	private boolean synced = false;
	private long unlockedAtomic = 0;
	private int priority = 0;
	private int sendUnit = 0;
	private boolean sendFlowActive = false;
	@Nullable
	private XmrPrice.Rates sendRates;
	@Nullable
	private Runnable refreshEquiv;
	@Nullable
	private XmrSendUiState.Review lastReview;
	@Nullable
	private androidx.appcompat.app.AlertDialog sendDialog;
	private final java.util.List<android.app.Dialog> trackedDialogs =
			new java.util.ArrayList<>();
	@Nullable
	private String pendingScanAddress;
	@Nullable
	private String pendingScanAmount;

	private final androidx.activity.result.ActivityResultLauncher<
			android.content.Intent> scanLauncher = registerForActivityResult(
			new androidx.activity.result.contract.ActivityResultContracts
					.StartActivityForResult(), result -> {
				if (result.getResultCode() != android.app.Activity.RESULT_OK
						|| result.getData() == null) {
					return;
				}
				String link = result.getData().getStringExtra(
						com.professor.zerion.android.settings
								.TransferQrScannerActivity.EXTRA_SCANNED_LINK);
				if (link == null) return;
				applyScannedUri(link);
				showSendDialog();
			});

	public static XmrWalletDetailFragment newInstance(String walletId,
			String walletName) {
		XmrWalletDetailFragment f = new XmrWalletDetailFragment();
		Bundle b = new Bundle();
		b.putString(ARG_ID, walletId);
		b.putString(ARG_NAME, walletName);
		f.setArguments(b);
		return f;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container, @Nullable Bundle state) {
		Bundle args = getArguments();
		if (args != null) {
			walletId = args.getString(ARG_ID, "");
			walletName = args.getString(ARG_NAME, "");
		}
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(XmrViewModel.class);

		View v = inflater.inflate(R.layout.fragment_xmr_detail, container, false);
		((TextView) v.findViewById(R.id.xmr_header_title)).setText(walletName);
		balanceValue = v.findViewById(R.id.xmr_balance_value);
		unlockedValue = v.findViewById(R.id.xmr_unlocked_value);
		syncStatus = v.findViewById(R.id.xmr_sync_status);
		historyContainer = v.findViewById(R.id.xmr_history_container);
		historyEmpty = v.findViewById(R.id.xmr_history_empty);

		v.findViewById(R.id.xmr_back).setOnClickListener(x -> onBack());
		v.findViewById(R.id.xmr_btn_receive).setOnClickListener(
				x -> viewModel.newReceiveAddress(walletId));
		View sendBtn = v.findViewById(R.id.xmr_btn_send);
		sendBtn.setAlpha(1f);
		sendBtn.setOnClickListener(x -> showSendDialog());
		v.findViewById(R.id.xmr_btn_refresh).setOnClickListener(
				x -> viewModel.refreshNow());
		v.findViewById(R.id.xmr_settings).setOnClickListener(
				x -> showSettings());
		backupNotice = v.findViewById(R.id.xmr_backup_notice);
		backupNotice.setOnClickListener(x -> showRecovery());

		observe();
		viewModel.loadHistory(walletId);
		viewModel.loadReceiveList(walletId);
		viewModel.loadBackupState(walletId);
		viewModel.loadCachedPrice();
		viewModel.loadPrice();
		return v;
	}

	private void observe() {
		viewModel.getSyncStatus().observe(getViewLifecycleOwner(),
				this::renderStatus);
		viewModel.getHistory().observe(getViewLifecycleOwner(),
				this::renderHistory);
		viewModel.getReceiveAddress().observe(getViewLifecycleOwner(), ev -> {
			XmrReceiveAddress a = ev == null ? null : ev.getIfNotHandled();
			if (a != null && a.walletId.equals(walletId)) showReceive(a);
		});
		viewModel.getSeedReveal().observe(getViewLifecycleOwner(), ev -> {
			String id = ev == null ? null : ev.getIfNotHandled();
			if (id != null && id.equals(walletId)) {
				showNextFragment(XmrRecoveryPhraseFragment.newInstance(
						walletId, walletName, false));
			}
		});
		viewModel.getBackupVerified().observe(getViewLifecycleOwner(), v -> {
			boolean verified = v != null && v;
			backupNotice.setVisibility(verified ? View.GONE : View.VISIBLE);
			backupNotice.setText(R.string.wallet_xmr_phrase_not_verified);
		});
		viewModel.getWalletDeleted().observe(getViewLifecycleOwner(), ev -> {
			String id = ev == null ? null : ev.getIfNotHandled();
			if (id != null && id.equals(walletId)) {
				toast(getString(R.string.wallet_deleted));
				returnToList();
			}
		});
		viewModel.getError().observe(getViewLifecycleOwner(), ev -> {
			com.professor.zerion.android.vault.wallet.xmr.XmrError e =
					ev == null ? null : ev.getIfNotHandled();
			if (e != null) toast(messageFor(e));
		});
		viewModel.getSendState().observe(getViewLifecycleOwner(), st -> {
			if (st != null) onSendState(st);
		});
		viewModel.getXmrRates().observe(getViewLifecycleOwner(), r -> {
			sendRates = r;
			if (refreshEquiv != null) refreshEquiv.run();
		});
	}

	private String messageFor(
			com.professor.zerion.android.vault.wallet.xmr.XmrError e) {
		switch (e) {
			case WRONG_PASSWORD:
				return getString(R.string.wallet_wrong_password);
			case SESSION_INVALIDATED:
				return getString(R.string.wallet_offline);
			case BUSY:
				return getString(R.string.wallet_xmr_busy);
			default:
				return getString(R.string.wallet_xmr_node_invalid);
		}
	}

	private void renderStatus(@Nullable XmrSyncStatus s) {
		if (s == null) return;
		synced = s.state == XmrSyncState.SYNCED && s.scanComplete();
		unlockedAtomic = s.unlockedAtomic;
		balanceValue.setText(formatXmr(s.balanceAtomic) + " XMR");
		if (s.state == XmrSyncState.SYNCED && s.scanComplete()
				&& s.unlockedAtomic != s.balanceAtomic) {
			unlockedValue.setVisibility(View.VISIBLE);
			unlockedValue.setText(formatXmr(s.unlockedAtomic) + " XMR available");
		} else {
			unlockedValue.setVisibility(View.GONE);
		}
		syncStatus.setText(statusText(s));
		boolean offline = s.state == XmrSyncState.OFFLINE
				|| s.state == XmrSyncState.ERROR;
		syncStatus.setClickable(offline);
		syncStatus.setOnClickListener(offline ? x -> viewModel.retrySync() : null);
	}

	private String statusText(XmrSyncStatus s) {
		switch (s.state) {
			case STARTING_TOR:
				return getString(R.string.wallet_xmr_starting_tor);
			case CONNECTING:
				return getString(R.string.wallet_connecting_tor);
			case CONNECTED:
				return getString(R.string.wallet_xmr_connected);
			case SYNCHRONIZING:
				return getString(R.string.wallet_xmr_synchronizing,
						formatCount(s.walletHeight), formatCount(s.daemonHeight));
			case SYNCED:
				if (s.checking) return getString(R.string.wallet_xmr_checking);
				return getString(R.string.wallet_xmr_synced_block,
						formatCount(s.walletHeight));
			case OFFLINE:
				return getString(R.string.wallet_xmr_offline_retry);
			default:
				return "";
		}
	}

	private static String formatCount(long value) {
		return String.format(Locale.US, "%,d", Math.max(value, 0));
	}

	private void renderHistory(@Nullable List<XmrTxInfo> txs) {
		historyContainer.removeAllViews();
		if (txs == null || txs.isEmpty()) {
			historyEmpty.setVisibility(View.VISIBLE);
			return;
		}
		historyEmpty.setVisibility(View.GONE);
		LayoutInflater inf = LayoutInflater.from(requireContext());
		for (XmrTxInfo tx : txs) {
			View row = inf.inflate(R.layout.item_vault_wallet_tx,
					historyContainer, false);
			ImageView icon = row.findViewById(R.id.tx_direction_icon);
			TextView amount = row.findViewById(R.id.tx_amount);
			TextView txid = row.findViewById(R.id.tx_txid);
			TextView status = row.findViewById(R.id.tx_status);
			boolean in = tx.direction == XmrTxInfo.Direction.IN;
			icon.setImageResource(in ? R.drawable.ic_call_received
					: R.drawable.ic_call_made);
			icon.setColorFilter(getResources().getColor(
					in ? R.color.zerion_success : R.color.zerion_red_500, null));
			amount.setText((in ? "+" : "-") + formatXmr(tx.amountAtomic) + " XMR");
			amount.setTextColor(getResources().getColor(
					in ? R.color.zerion_success : R.color.zerion_text_primary,
					null));
			txid.setText(tx.txid.substring(0, 12) + "…");
			status.setText(tx.pending ? getString(R.string.wallet_tx_pending)
					: tx.confirmations + " conf");
			final String rowTxid = tx.txid;
			row.setOnClickListener(x -> showTxDetails(rowTxid));
			historyContainer.addView(row);
		}
	}

	/**
	 * Show canonical details for one transaction. The row is looked up fresh from
	 * the current wallet2 history by txid, so the screen reflects the authoritative
	 * state and survives a refresh or restart rather than a transient row object.
	 */
	private void showTxDetails(String txid) {
		List<XmrTxInfo> history = viewModel.getHistory().getValue();
		XmrTxInfo tx = null;
		if (history != null) {
			for (XmrTxInfo t : history) {
				if (t.txid.equals(txid)) {
					tx = t;
					break;
				}
			}
		}
		if (tx == null) {
			toast(getString(R.string.wallet_xmr_tx_gone));
			return;
		}
		boolean in = tx.direction == XmrTxInfo.Direction.IN;
		View v = getLayoutInflater().inflate(R.layout.dialog_xmr_tx_detail,
				null, false);

		((TextView) v.findViewById(R.id.detail_direction)).setText(
				getString(in ? R.string.wallet_xmr_tx_received
						: R.string.wallet_xmr_tx_sent));
		TextView amount = v.findViewById(R.id.detail_amount);
		amount.setText((in ? "+" : "-") + formatXmr(tx.amountAtomic) + " XMR");
		amount.setTextColor(getResources().getColor(
				in ? R.color.zerion_success : R.color.zerion_text_primary, null));

		TextView state = v.findViewById(R.id.detail_state);
		if (tx.failed) {
			state.setText(R.string.wallet_xmr_tx_state_failed);
			state.setTextColor(getResources().getColor(
					R.color.zerion_red_500, null));
		} else if (tx.pending) {
			state.setText(R.string.wallet_xmr_tx_state_pending);
			state.setTextColor(getResources().getColor(
					R.color.zerion_warning, null));
		} else {
			state.setText(R.string.wallet_xmr_tx_state_confirmed);
			state.setTextColor(getResources().getColor(
					R.color.zerion_success, null));
		}

		LinearLayout rows = v.findViewById(R.id.detail_rows);
		if (!tx.pending && !tx.failed) {
			detailRow(rows, getString(R.string.wallet_xmr_tx_confirmations),
					String.valueOf(tx.confirmations));
			if (tx.height > 0) {
				detailRow(rows, getString(R.string.wallet_xmr_tx_height),
						String.valueOf(tx.height));
			}
		}
		if (!in && tx.feeAtomic > 0) {
			detailRow(rows, getString(R.string.wallet_xmr_send_fee),
					formatXmr(tx.feeAtomic) + " XMR");
		}
		if (tx.timestamp > 0) {
			detailRow(rows, getString(R.string.wallet_xmr_tx_date),
					java.text.DateFormat.getDateTimeInstance().format(
							new java.util.Date(tx.timestamp * 1000L)));
		}
		((TextView) v.findViewById(R.id.detail_id)).setText(tx.txid);

		final String copyTxid = tx.txid;
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_xmr_tx_details)
				.setView(v)
				.setPositiveButton(R.string.wallet_xmr_copy,
						(d, w) -> copy(copyTxid))
				.setNegativeButton(android.R.string.cancel, null)
				.create()).show();
	}

	private void detailRow(LinearLayout box, String label, String value) {
		LinearLayout row = new LinearLayout(requireContext());
		row.setOrientation(LinearLayout.HORIZONTAL);
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		rlp.bottomMargin = dp(8);
		row.setLayoutParams(rlp);
		TextView l = new TextView(requireContext());
		l.setText(label);
		l.setTextColor(getResources().getColor(
				R.color.zerion_text_secondary, null));
		l.setTextSize(14);
		l.setLayoutParams(new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		row.addView(l);
		TextView v = new TextView(requireContext());
		v.setText(value);
		v.setTextColor(getResources().getColor(
				R.color.zerion_text_primary, null));
		v.setTextSize(14);
		row.addView(v);
		box.addView(row);
	}

	/**
	 * Monero Send as a modal card over the wallet, matching the Bitcoin flow:
	 * an input card (recipient, amount, priority), then a review card that shows
	 * the exact already-signed transaction and asks for the wallet password to
	 * authorize that transaction after it is shown, then a result card. The card
	 * only reads the send state the manager posts and drives the one core send
	 * flow; it never touches the native transaction, the journal or the relay.
	 */
	private void showSendDialog() {
		if (!isAdded()) return;
		if (viewModel.isSpendQuarantined(walletId)) {
			showSendQuarantined();
			return;
		}
		View form = getLayoutInflater().inflate(R.layout.dialog_xmr_send,
				null, false);

		((TextView) form.findViewById(R.id.xmr_send_available))
				.setText(formatXmr(unlockedAtomic) + " XMR");

		TextInputLayout tilAddress = form.findViewById(R.id.xmr_til_address);
		final TextInputEditText recipient =
				form.findViewById(R.id.xmr_send_address);
		recipient.setSaveEnabled(false);
		final TextInputLayout tilAmount = form.findViewById(R.id.xmr_til_amount);
		final TextInputEditText amount = form.findViewById(R.id.xmr_send_amount);
		amount.setSaveEnabled(false);
		final TextView equiv = form.findViewById(R.id.xmr_send_equiv);
		final TextView unitToggle = form.findViewById(R.id.xmr_send_unit_toggle);

		if (pendingScanAddress != null) {
			recipient.setText(pendingScanAddress);
			pendingScanAddress = null;
		}
		if (pendingScanAmount != null) {
			sendUnit = 0;
			amount.setText(pendingScanAmount);
			pendingScanAmount = null;
		}
		if (rateFor(1) <= 0 && rateFor(2) <= 0) sendUnit = 0;

		final Runnable refresh = () -> {
			tilAmount.setSuffixText(unitName(sendUnit));
			unitToggle.setText(unitName(sendUnit) + "  ⇄");
			unitToggle.setVisibility(
					rateFor(1) > 0 || rateFor(2) > 0 ? View.VISIBLE : View.GONE);
			equiv.setText(equivText(
					amount.getText() == null ? "" : amount.getText().toString(),
					sendUnit));
		};
		refreshEquiv = refresh;
		refresh.run();

		amount.addTextChangedListener(new android.text.TextWatcher() {
			public void beforeTextChanged(CharSequence s, int a, int b, int c) {
			}

			public void onTextChanged(CharSequence s, int a, int b, int c) {
			}

			public void afterTextChanged(android.text.Editable s) {
				refresh.run();
			}
		});
		unitToggle.setOnClickListener(v -> {
			sendUnit = nextUnit(sendUnit);
			refresh.run();
		});

		form.findViewById(R.id.xmr_send_paste).setOnClickListener(v -> {
			String pasted = clipboardText();
			if (!pasted.isEmpty()) recipient.setText(pasted);
		});

		form.findViewById(R.id.xmr_pct_10).setOnClickListener(
				v -> setAmountFraction(amount, 10));
		form.findViewById(R.id.xmr_pct_25).setOnClickListener(
				v -> setAmountFraction(amount, 25));
		form.findViewById(R.id.xmr_pct_50).setOnClickListener(
				v -> setAmountFraction(amount, 50));

		MaterialButtonToggleGroup prio =
				form.findViewById(R.id.xmr_priority_toggle);
		prio.check(priority == 1 ? R.id.xmr_prio_low
				: priority == 3 ? R.id.xmr_prio_high : R.id.xmr_prio_normal);
		prio.addOnButtonCheckedListener((group, id, checked) -> {
			if (!checked) return;
			if (id == R.id.xmr_prio_low) priority = 1;
			else if (id == R.id.xmr_prio_high) priority = 3;
			else priority = 0;
		});

		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(requireContext())
						.setTitle(R.string.wallet_xmr_send_title)
						.setView(form)
						.setPositiveButton(R.string.wallet_xmr_send_continue,
								null)
						.setNegativeButton(android.R.string.cancel, null)
						.setOnDismissListener(d -> refreshEquiv = null)
						.create();
		tilAddress.setEndIconOnClickListener(v -> {
			dlg.dismiss();
			launchScanner();
		});
		dlg.setOnShowListener(d -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> onSendContinue(
						recipient.getText() == null ? ""
								: recipient.getText().toString().trim(),
						amount.getText() == null ? ""
								: amount.getText().toString().trim(),
						sendUnit)));
		sendDialog = dlg;
		dlg.show();
	}

	private void onSendContinue(String recipientRaw, String amountText,
			int unit) {
		if (!synced || unlockedAtomic <= 0) {
			toast(getString(R.string.wallet_xmr_send_wait_sync));
			return;
		}
		String address = recipientRaw;
		Long uriAtomic = null;
		try {
			MoneroUri uri = MoneroUri.parse(recipientRaw);
			if (uri != null) {
				address = uri.address();
				if (uri.hasAmount() && amountText.isEmpty()) {
					uriAtomic = uri.amountAtomic();
				}
			}
		} catch (XmrError.XmrException badUri) {
			toast(getString(R.string.wallet_xmr_send_bad_address));
			return;
		}
		if (!viewModel.isValidXmrAddress(address)) {
			toast(getString(R.string.wallet_xmr_send_bad_address));
			return;
		}
		long amountAtomic = uriAtomic != null ? uriAtomic
				: atomicFromUnit(amountText, unit);
		if (amountAtomic <= 0) {
			toast(getString(R.string.wallet_xmr_send_bad_amount));
			return;
		}
		if (amountAtomic > unlockedAtomic) {
			toast(getString(R.string.wallet_xmr_send_insufficient));
			return;
		}
		final String dest = address;
		final long amt = amountAtomic;
		final int pri = priority;
		promptPassword(R.string.wallet_xmr_send_continue, pw -> {
			sendFlowActive = true;
			showSendProgress(R.string.wallet_xmr_send_preparing);
			viewModel.prepareSend(walletId, walletName, dest, amt, pri, pw);
		});
	}

	private String unitName(int u) {
		return u == 1 ? "USD" : u == 2 ? "EUR" : "XMR";
	}

	private double rateFor(int u) {
		XmrPrice.Rates r = sendRates;
		if (r == null) return 0;
		return u == 1 ? r.usd : u == 2 ? r.eur : 0;
	}

	private int nextUnit(int u) {
		for (int i = 0; i < 3; i++) {
			u = (u + 1) % 3;
			if (u == 0 || rateFor(u) > 0) return u;
		}
		return 0;
	}

	/** XMR atomic amount from a number the user typed in unit {@code u}
	 *  (0 XMR, 1 USD, 2 EUR); -1 when the text or the needed rate is missing. */
	private long atomicFromUnit(String text, int u) {
		String t = text.trim();
		if (t.isEmpty()) return -1;
		if (u == 0) {
			try {
				return MoneroUri.parseXmrToAtomic(t);
			} catch (XmrError.XmrException e) {
				return -1;
			}
		}
		double rate = rateFor(u);
		if (rate <= 0) return -1;
		try {
			java.math.BigDecimal fiat = new java.math.BigDecimal(t);
			if (fiat.signum() <= 0) return -1;
			java.math.BigDecimal xmr = fiat.divide(
					java.math.BigDecimal.valueOf(rate), 12,
					java.math.RoundingMode.DOWN);
			return xmr.multiply(
					java.math.BigDecimal.valueOf(1_000_000_000_000L))
					.setScale(0, java.math.RoundingMode.DOWN).longValueExact();
		} catch (Exception e) {
			return -1;
		}
	}

	private void setAmountFraction(TextInputEditText amount, int pct) {

		sendUnit = 0;
		setAmountAtomic(amount, unlockedAtomic / 100 * pct);
	}

	/** Put an XMR atomic value into the amount field, written in the currently
	 *  selected unit so the field and its suffix stay consistent. */
	private void setAmountAtomic(TextInputEditText amount, long atomic) {
		String text;
		double rate = rateFor(sendUnit);
		if (sendUnit == 0 || rate <= 0) {
			text = formatXmr(atomic);
		} else {
			text = String.format(Locale.US, "%.2f", (atomic / 1e12) * rate);
		}
		amount.setText(text);
		if (amount.getText() != null) {
			amount.setSelection(amount.getText().length());
		}
	}

	private String equivText(String amountText, int u) {
		long atomic = atomicFromUnit(amountText, u);
		if (atomic <= 0) return "";
		if (u == 0) {
			double rate = rateFor(1) > 0 ? rateFor(1) : rateFor(2);
			if (rate <= 0) return "";
			String cur = rateFor(1) > 0 ? "USD" : "EUR";
			return "≈ " + String.format(Locale.US, "%.2f",
					(atomic / 1e12) * rate) + " " + cur;
		}
		return "≈ " + formatXmr(atomic) + " XMR";
	}

	private void applyScannedUri(String scanned) {
		String s = scanned.trim();
		try {
			MoneroUri uri = MoneroUri.parse(s);
			if (uri != null) {
				pendingScanAddress = uri.address();
				pendingScanAmount = uri.hasAmount()
						? formatXmr(uri.amountAtomic()) : null;
				return;
			}
		} catch (XmrError.XmrException ignored) {
		}
		int scheme = s.indexOf(':');
		if (scheme >= 0 && s.regionMatches(true, 0, "monero", 0, 6)) {
			s = s.substring(scheme + 1);
		}
		int q = s.indexOf('?');
		if (q >= 0) s = s.substring(0, q);
		pendingScanAddress = s.trim();
		pendingScanAmount = null;
	}

	private void launchScanner() {
		if (getActivity() instanceof VaultActivity) {
			((VaultActivity) getActivity()).setExpectingChildResult();
		}
		android.content.Intent i = new android.content.Intent(requireContext(),
				com.professor.zerion.android.settings.TransferQrScannerActivity
						.class);
		i.putExtra(com.professor.zerion.android.settings
				.TransferQrScannerActivity.EXTRA_ACCEPT_ANY, true);
		scanLauncher.launch(i);
	}

	private void onSendState(XmrSendUiState st) {
		if (!sendFlowActive) return;
		switch (st.kind) {
			case PREPARING:
				showSendProgress(R.string.wallet_xmr_send_preparing);
				break;
			case REVIEW:
				lastReview = st.review();
				if (lastReview != null) showReviewDialog(lastReview);
				break;
			case AUTHENTICATING:
				showSendProgress(R.string.wallet_xmr_send_authenticating);
				break;
			case RELAYING:
				showSendProgress(R.string.wallet_xmr_send_relaying);
				break;
			case SUCCESS:
				showSendSuccess(st.txids());
				break;
			case RELAY_UNCERTAIN:
				showSendUncertain();
				break;
			case FAILED:
				showSendFailed(st.error);
				break;
			case QUARANTINED:
				showSendQuarantined();
				break;
			case CANCELLED:
			case INPUT:
			default:
				break;
		}
	}

	private void showReviewDialog(XmrSendUiState.Review r) {
		if (!isAdded()) return;
		View v = getLayoutInflater().inflate(R.layout.dialog_xmr_send_review,
				null, false);
		((TextView) v.findViewById(R.id.review_amount))
				.setText(formatXmr(r.amountAtomic) + " XMR");
		((TextView) v.findViewById(R.id.review_to)).setText(r.destination);
		((TextView) v.findViewById(R.id.review_type))
				.setText(addressType(r.destinationKind));
		((TextView) v.findViewById(R.id.review_fee))
				.setText(formatXmr(r.networkFeeAtomic) + " XMR");
		((TextView) v.findViewById(R.id.review_total))
				.setText(formatXmr(r.totalDebitAtomic) + " XMR");
		((TextView) v.findViewById(R.id.review_from)).setText(
				getString(R.string.wallet_xmr_send_from) + "  "
						+ r.fromWalletLabel);
		if (r.isMultiTx()) {
			TextView multi = v.findViewById(R.id.review_multi);
			multi.setText(getString(R.string.wallet_xmr_send_multi, r.txCount));
			multi.setVisibility(View.VISIBLE);
		}
		final TextInputEditText pw = v.findViewById(R.id.review_password);
		pw.setSaveEnabled(false);

		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(requireContext())
						.setTitle(R.string.wallet_xmr_send_review)
						.setView(v)
						.setPositiveButton(R.string.wallet_xmr_send_confirm,
								null)
						.setNegativeButton(R.string.wallet_xmr_send_edit, null)
						.setOnCancelListener(d -> {
							viewModel.cancelSend();
							sendFlowActive = false;
						})
						.create();
		dlg.setOnShowListener(d -> {
			dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
					.setOnClickListener(x -> {
						char[] cred = extract(pw);
						if (cred.length == 0) {
							toast(getString(R.string.wallet_wrong_password));
							return;
						}
						dlg.dismiss();
						viewModel.confirmSend(cred);
					});
			dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
					.setOnClickListener(x -> {
						sendFlowActive = false;
						viewModel.cancelSend();
						dlg.dismiss();
						showSendDialog();
					});
		});
		swapSendDialog(dlg);
	}

	private void showSendProgress(int labelRes) {
		if (!isAdded()) return;
		int p = dp(24);
		LinearLayout box = new LinearLayout(requireContext());
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
		box.setPadding(p, dp(28), p, dp(20));
		android.widget.ProgressBar bar =
				new android.widget.ProgressBar(requireContext());
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				dp(40), dp(40));
		box.addView(bar, lp);
		TextView label = new TextView(requireContext());
		label.setText(labelRes);
		label.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
		label.setPadding(0, dp(16), 0, 0);
		label.setTextColor(getResources().getColor(
				R.color.zerion_text_secondary, null));
		box.addView(label);
		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(requireContext())
						.setView(box)
						.setCancelable(false)
						.create();
		swapSendDialog(dlg);
	}

	private void showSendSuccess(List<String> txids) {
		String amount = lastReview == null ? null
				: formatXmr(lastReview.amountAtomic) + " XMR";
		showSendResult("✓", R.color.zerion_success,
				getString(R.string.wallet_xmr_send_sent), amount,
				getString(R.string.wallet_xmr_send_pending), txids,
				getString(R.string.wallet_xmr_send_done), this::finishSend,
				null, null);
		viewModel.refreshNow();
	}

	private void showSendUncertain() {
		showSendResult("!", R.color.zerion_warning,
				getString(R.string.wallet_xmr_send_unresolved), null,
				getString(R.string.wallet_xmr_send_unresolved_body), null,
				getString(R.string.wallet_xmr_send_done), this::finishSend,
				null, null);
		viewModel.refreshNow();
	}

	private void showSendFailed(@Nullable XmrError error) {
		showSendResult("×", R.color.zerion_red_500,
				getString(R.string.wallet_xmr_send_failed), null,
				error == null ? getString(R.string.wallet_xmr_send_failed)
						: sendMessageFor(error), null,
				getString(R.string.wallet_xmr_send_edit), () -> {
					finishSend();
					showSendDialog();
				},
				getString(R.string.wallet_xmr_send_done), this::finishSend);
	}

	private void showSendQuarantined() {
		sendFlowActive = false;
		showSendResult("×", R.color.zerion_red_500,
				getString(R.string.wallet_xmr_send_quarantine), null,
				getString(R.string.wallet_xmr_send_quarantine_body), null,
				getString(R.string.wallet_xmr_send_done), this::finishSend,
				null, null);
	}

	private void showSendResult(String glyph, int badgeColorRes,
			String headline, @Nullable String amount, String subtext,
			@Nullable List<String> txids, String primaryText,
			Runnable primaryAction, @Nullable String secondaryText,
			@Nullable Runnable secondaryAction) {
		if (!isAdded()) return;
		View v = getLayoutInflater().inflate(R.layout.fragment_xmr_send_result,
				null, false);

		TextView badge = v.findViewById(R.id.result_badge);
		badge.setText(glyph);
		badge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
				getResources().getColor(badgeColorRes, null)));
		((TextView) v.findViewById(R.id.result_headline)).setText(headline);
		if (amount != null && !amount.isEmpty()) {
			TextView amt = v.findViewById(R.id.result_amount);
			amt.setText(amount);
			amt.setVisibility(View.VISIBLE);
		}
		((TextView) v.findViewById(R.id.result_subtext)).setText(subtext);

		if (txids != null && !txids.isEmpty()) {
			LinearLayout card = v.findViewById(R.id.result_txid_card);
			card.setVisibility(View.VISIBLE);
			boolean first = true;
			for (String txid : txids) {
				if (!first) {
					View div = new View(requireContext());
					LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
							ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
					dlp.topMargin = dp(12);
					dlp.bottomMargin = dp(12);
					div.setLayoutParams(dlp);
					div.setBackgroundColor(getResources().getColor(
							R.color.zerion_divider, null));
					card.addView(div);
				}
				first = false;
				TextView label = new TextView(requireContext());
				label.setText(R.string.wallet_xmr_tx_id);
				label.setTextColor(getResources().getColor(
						R.color.zerion_text_secondary, null));
				label.setTextSize(12);
				card.addView(label);
				TextView id = new TextView(requireContext());
				id.setText(txid);
				id.setTypeface(android.graphics.Typeface.MONOSPACE);
				id.setTextIsSelectable(true);
				id.setTextColor(getResources().getColor(
						R.color.zerion_text_primary, null));
				id.setTextSize(13);
				card.addView(id);
				final String copyTxid = txid;
				android.widget.Button copyBtn = new android.widget.Button(
						requireContext(), null,
						android.R.attr.borderlessButtonStyle);
				copyBtn.setText(R.string.wallet_xmr_copy);
				copyBtn.setAllCaps(false);
				copyBtn.setOnClickListener(x -> copyTxid(copyTxid));
				card.addView(copyBtn);
			}
		}

		MaterialButton primary = v.findViewById(R.id.result_primary);
		primary.setText(primaryText);
		primary.setOnClickListener(x -> primaryAction.run());
		if (secondaryText != null && secondaryAction != null) {
			MaterialButton secondary = v.findViewById(R.id.result_secondary);
			secondary.setText(secondaryText);
			secondary.setVisibility(View.VISIBLE);
			secondary.setOnClickListener(x -> secondaryAction.run());
		}

		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(requireContext())
						.setView(v)
						.setCancelable(true)
						.setOnCancelListener(d -> {
							sendFlowActive = false;
							lastReview = null;
							sendDialog = null;
						})
						.create();
		swapSendDialog(dlg);
	}

	private void copyTxid(String value) {
		com.professor.zerion.android.util.SecureClipboard.copy(
				requireContext(), "txid", value);
		toast(getString(R.string.wallet_xmr_copied));
	}

	private void swapSendDialog(androidx.appcompat.app.AlertDialog next) {
		dismissSendDialog();
		sendDialog = next;
		next.show();
	}

	private void dismissSendDialog() {
		if (sendDialog != null) {
			sendDialog.dismiss();
			sendDialog = null;
		}
	}

	private <T extends android.app.Dialog> T track(T d) {
		trackedDialogs.add(d);
		d.setOnDismissListener(x -> trackedDialogs.remove(d));
		return d;
	}

	private void dismissTrackedDialogs() {
		for (android.app.Dialog d : new java.util.ArrayList<>(trackedDialogs)) {
			try {
				if (d.isShowing()) d.dismiss();
			} catch (Throwable ignored) {
			}
		}
		trackedDialogs.clear();
	}

	private void finishSend() {
		sendFlowActive = false;
		lastReview = null;
		dismissSendDialog();
	}

	private String addressType(MoneroEngine.AddressKind kind) {
		switch (kind) {
			case SUBADDRESS:
				return getString(R.string.wallet_xmr_send_type_subaddress);
			case INTEGRATED:
				return getString(R.string.wallet_xmr_send_type_integrated);
			default:
				return getString(R.string.wallet_xmr_send_type_standard);
		}
	}

	private String sendMessageFor(XmrError e) {
		switch (e) {
			case WRONG_PASSWORD:
				return getString(R.string.wallet_wrong_password);
			case SPEND_QUARANTINED:
				return getString(R.string.wallet_xmr_send_quarantine);
			case NODE_UNREACHABLE:
				return getString(R.string.wallet_xmr_node_unreachable);
			case BUSY:
				return getString(R.string.wallet_xmr_busy);
			case SESSION_INVALIDATED:
				return getString(R.string.wallet_xmr_send_session_gone);
			default:
				return getString(R.string.wallet_xmr_send_failed);
		}
	}

	private String clipboardText() {
		ClipboardManager cm = (ClipboardManager)
				requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm == null || cm.getPrimaryClip() == null
				|| cm.getPrimaryClip().getItemCount() == 0) {
			return "";
		}
		CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(
				requireContext());
		return cs == null ? "" : cs.toString().trim();
	}

	private void showReceive(XmrReceiveAddress a) {
		if (!isAdded()) return;
		int p = dp(20);
		LinearLayout box = new LinearLayout(requireContext());
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
		box.setPadding(p, dp(8), p, 0);

		TextView hint = new TextView(requireContext());
		hint.setText(R.string.wallet_xmr_receive_hint);
		hint.setTextSize(13);
		hint.setPadding(0, 0, 0, dp(14));
		box.addView(hint);

		ImageView qr = new ImageView(requireContext());
		Bitmap bmp = QrCodeUtils.generateQrCode(uriFor(a.address));
		if (bmp != null) qr.setImageBitmap(bmp);
		int q = dp(220);
		LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(q, q);
		qr.setLayoutParams(qp);
		box.addView(qr);

		TextView addr = new TextView(requireContext());
		addr.setText(a.address);
		addr.setTypeface(android.graphics.Typeface.MONOSPACE);
		addr.setTextSize(12);
		addr.setTextIsSelectable(true);
		addr.setGravity(android.view.Gravity.CENTER);
		addr.setPadding(0, dp(12), 0, dp(4));
		box.addView(addr);

		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_xmr_receive_title)
				.setView(box)
				.setPositiveButton(R.string.wallet_xmr_copy,
						(d, w) -> copy(a.address))
				.setNeutralButton(R.string.wallet_xmr_share,
						(d, w) -> share(a.address))
				.setNegativeButton(R.string.wallet_xmr_previous_addresses,
						(d, w) -> showPrevious())
				.create()).show();
	}

	private void showPrevious() {
		List<XmrReceiveAddress> all = viewModel.getReceiveList().getValue();
		List<XmrReceiveAddress> list = new java.util.ArrayList<>();
		if (all != null) {
			for (XmrReceiveAddress a : all) {
				if (a.walletId.equals(walletId)) list.add(a);
			}
		}
		if (list.isEmpty()) {
			toast(getString(R.string.wallet_xmr_no_previous));
			return;
		}
		String[] items = new String[list.size()];
		for (int i = 0; i < list.size(); i++) {
			XmrReceiveAddress a = list.get(i);
			String label = a.label == null ? "" : " · " + a.label;
			items[i] = "#" + a.index + label + "\n" + a.shortPreview();
		}
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_xmr_previous_addresses)
				.setMessage(R.string.wallet_xmr_reuse_note)
				.setItems(items, (d, which) -> showReceive(list.get(which)))
				.create()).show();
	}

	private String uriFor(String address) {
		return "monero:" + address;
	}

	private void copy(String address) {
		com.professor.zerion.android.util.SecureClipboard.copy(
				requireContext(), "monero", address);
		toast(getString(R.string.wallet_xmr_address_copied));
	}

	private void share(String address) {
		Intent i = new Intent(Intent.ACTION_SEND);
		i.setType("text/plain");
		i.putExtra(Intent.EXTRA_TEXT, address);
		startActivity(Intent.createChooser(i,
				getString(R.string.wallet_xmr_share)));
	}

	private void showSettings() {
		String[] items = {
				getString(R.string.wallet_xmr_node_settings),
				getString(R.string.wallet_show_recovery),
				getString(R.string.wallet_rename),
				getString(R.string.wallet_xmr_rescan),
				getString(R.string.wallet_delete)
		};
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(walletName)
				.setItems(items, (d, which) -> {
					if (which == 0) showNodeSettings();
					else if (which == 1) showRecovery();
					else if (which == 2) showRename();
					else if (which == 3) showRescan();
					else showDelete();
				})
				.create()).show();
	}

	private void showRescan() {
		final android.widget.EditText height =
				new android.widget.EditText(requireContext());
		height.setHint(R.string.wallet_xmr_rescan_height_hint);
		height.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
		height.setSingleLine(true);
		final android.widget.EditText pw =
				new android.widget.EditText(requireContext());
		pw.setHint(R.string.wallet_settings_title);
		pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT
				| android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
		pw.setSaveEnabled(false);
		TextView note = new TextView(requireContext());
		note.setText(R.string.wallet_xmr_rescan_note);
		note.setTextSize(13);
		note.setPadding(0, 0, 0, dp(12));
		LinearLayout box = new LinearLayout(requireContext());
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);
		box.addView(note);
		android.widget.Button pickDate = new android.widget.Button(
				requireContext(), null,
				android.R.attr.borderlessButtonStyle);
		pickDate.setText(R.string.wallet_xmr_rescan_from_date);
		pickDate.setAllCaps(false);
		pickDate.setOnClickListener(v -> {
			java.util.Calendar c = java.util.Calendar.getInstance();
			track(new android.app.DatePickerDialog(requireContext(), (dp, y, m, d) -> {
				java.util.Calendar picked = java.util.Calendar.getInstance();
				picked.set(y, m, d, 0, 0, 0);
				long h = com.professor.zerion.android.vault.wallet.xmr
						.XmrBirthday.heightForDate(picked.getTimeInMillis());
				height.setText(String.valueOf(h));
			}, c.get(java.util.Calendar.YEAR),
					c.get(java.util.Calendar.MONTH),
					c.get(java.util.Calendar.DAY_OF_MONTH))).show();
		});
		box.addView(pickDate);
		box.addView(height);
		box.addView(pw);
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_xmr_rescan)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					char[] p1 = extract(pw);
					if (p1.length == 0) return;
					viewModel.rescan(walletId, p1, parseHeight(height.getText()));
					toast(getString(R.string.wallet_xmr_rescan_started));
					returnToList();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create()).show();
	}

	private static long parseHeight(@Nullable CharSequence text) {
		if (text == null) return -1;
		String s = text.toString().trim();
		if (s.isEmpty()) return -1;
		try {
			long h = Long.parseLong(s);
			return h >= 0 ? h : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private void showNodeSettings() {
		XmrNodeSettings.choose(this, viewModel);
	}

	private void showRecovery() {
		promptPassword(R.string.wallet_show_recovery,
				pw -> viewModel.revealSeed(walletId, pw));
	}

	private void showRename() {
		final android.widget.EditText name =
				new android.widget.EditText(requireContext());
		name.setHint(R.string.wallet_rename);
		name.setSingleLine(true);
		final android.widget.EditText pw =
				new android.widget.EditText(requireContext());
		pw.setHint(R.string.wallet_settings_title);
		pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT
				| android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
		pw.setSaveEnabled(false);
		LinearLayout box = new LinearLayout(requireContext());
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);
		box.addView(name);
		box.addView(pw);
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_rename)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					String nm = name.getText().toString().trim();
					char[] p1 = extract(pw);
					if (!nm.isEmpty() && p1.length > 0) {
						viewModel.renameWallet(walletId, nm, p1);
					} else {
						java.util.Arrays.fill(p1, '\0');
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create()).show();
	}

	private void showDelete() {
		promptPassword(R.string.wallet_delete, pw -> {
			viewModel.deleteWallet(walletId, pw);
		});
	}

	private interface PwSink {
		void accept(char[] password);
	}

	private void promptPassword(int titleRes, PwSink sink) {
		final android.widget.EditText pw =
				new android.widget.EditText(requireContext());
		pw.setHint(R.string.wallet_password_prompt);
		pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT
				| android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
		pw.setSaveEnabled(false);
		int p = dp(20);
		LinearLayout box = new LinearLayout(requireContext());
		box.setPadding(p, dp(8), p, 0);
		box.addView(pw);
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(titleRes)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					char[] p1 = extract(pw);
					if (p1.length > 0) sink.accept(p1);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create()).show();
	}

	private static char[] extract(android.widget.EditText e) {
		android.text.Editable ed = e.getText();
		if (ed == null) return new char[0];
		char[] out = new char[ed.length()];
		ed.getChars(0, ed.length(), out, 0);
		e.setText("");
		return out;
	}

	@Override
	public void onDestroyView() {
		dismissSendDialog();
		dismissTrackedDialogs();
		super.onDestroyView();
	}

	private void onBack() {
		returnToList();
	}

	/**
	 * After a committed delete this screen must not remain reachable: pop it
	 * off the back stack so Back cannot reopen the deleted wallet, landing on
	 * the list beneath (whose view reloads from persisted state).
	 */
	private void returnToList() {
		if (!isAdded()) return;
		androidx.fragment.app.FragmentManager fm =
				requireActivity().getSupportFragmentManager();
		if (fm.getBackStackEntryCount() > 0) {
			fm.popBackStack();
		} else {
			showNextFragment(XmrWalletFragment.newInstance());
		}
	}

	private static String formatXmr(long atomic) {
		long whole = atomic / 1_000_000_000_000L;
		long frac = atomic % 1_000_000_000_000L;
		String f = String.format(Locale.US, "%012d", frac);
		while (f.length() > 4 && f.endsWith("0")) f = f.substring(0, f.length() - 1);
		return whole + "." + f;
	}

	private int dp(int val) {
		return Math.round(val * getResources().getDisplayMetrics().density);
	}

	private void toast(String s) {
		if (isAdded()) {
			Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
		}
	}
}
