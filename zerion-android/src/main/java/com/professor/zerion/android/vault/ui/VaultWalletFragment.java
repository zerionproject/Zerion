package com.professor.zerion.android.vault.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contact.add.remote.QrCodeUtils;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.btc.BtcWallet;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyAnalyzer;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultWalletFragment extends BaseFragment {

	private static final long POLL_INTERVAL_MS = 20_000L;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;
	private ScrollView listScroll;
	private ScrollView detailScroll;
	private View settingsGear;
	private LinearLayout walletsContainer;
	private TextView walletEmpty;
	private TextView headerTitle;
	private TextView balanceValue;
	private TextView balanceFiat;
	private TextView syncStatus;
	private boolean busyNow;
	@Nullable
	private com.professor.zerion.android.vault.wallet.btc.BtcPrice.Rates rates;
	private String fiatCurrency = "EUR";
	private boolean priceStale;
	private boolean spEnabled;
	private String displayUnit = "BTC";
	@Nullable
	private java.util.List<String> nodeList;
	@Nullable
	private String selectedNode;
	private final java.util.Map<String, TextView> nodeDots =
			new java.util.HashMap<>();
	private final java.util.Map<String, Boolean> nodeHealth =
			new java.util.HashMap<>();
	@Nullable
	private String spAddress;
	@Nullable
	private long[] spInfo;
	@Nullable
	private List<com.professor.zerion.android.vault.wallet.btc.privacy
			.PrivacyMeta> coins;
	private boolean showCoinsWhenLoaded;
	private boolean extremeMode;
	@Nullable
	private java.util.Set<String> manualCoins;
	private LinearLayout historyContainer;
	private TextView historyEmpty;
	private View progress;
	private FloatingActionButton fabAdd;
	@Nullable
	private OnBackPressedCallback backCallback;

	@Nullable
	private String openWalletId;
	@Nullable
	private String receiveAddress;
	private boolean reopenReceive;
	private boolean walletOnline = true;
	private boolean showNodesWhenLoaded;
	private double feeRate = 4.0;
	@Nullable
	private double[] feeOptions;
	private int feeChoice = 1;
	private boolean feeRequested;
	@Nullable
	private String lastToastError;
	@Nullable
	private String lastToastTxid;
	@Nullable
	private String pendingScanAddress;
	@Nullable
	private String pendingScanAmount;
	@Nullable
	private String pendingPayjoinUri;
	@Nullable
	private String lastPjUri;
	@Nullable
	private String lastPjTo;
	private long lastPjSat;
	private double lastPjRate;
	private boolean lastPjSweep;
	@Nullable
	private androidx.appcompat.app.AlertDialog payjoinProgressDialog;
	private androidx.appcompat.app.AlertDialog preparingDialog;
	private androidx.appcompat.app.AlertDialog unlockingDialog;
	private WalletRecord pendingOpen;
	private final java.util.List<android.content.DialogInterface> liveDialogs =
			new java.util.ArrayList<>();

	private <T extends android.content.DialogInterface> T track(T dialog) {
		liveDialogs.add(dialog);
		return dialog;
	}

	private void dismissTrackedDialogs() {
		for (android.content.DialogInterface d : liveDialogs) {
			try {
				d.dismiss();
			} catch (Throwable ignored) {
			}
		}
		liveDialogs.clear();
	}
	@Nullable
	private androidx.activity.result.ActivityResultLauncher<android.content.Intent>
			scanLauncher;
	private final Handler pollHandler = new Handler(Looper.getMainLooper());
	@Nullable
	private Runnable pollRunnable;

	public static VaultWalletFragment newInstance() {
		return new VaultWalletFragment();
	}

	@Override
	public String getUniqueTag() {
		return "VaultWalletFragment";
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		scanLauncher = registerForActivityResult(
				new androidx.activity.result.contract.ActivityResultContracts
						.StartActivityForResult(),
				result -> {
					if (result.getResultCode() == android.app.Activity.RESULT_OK
							&& result.getData() != null) {
						String scanned = result.getData().getStringExtra(
								com.professor.zerion.android.settings
										.TransferQrScannerActivity
										.EXTRA_SCANNED_LINK);
						if (scanned != null) {
							handleScanned(scanned);
						}
					}
				});
	}

	private void handleScanned(String raw) {
		String addr = raw.trim();
		String amt = null;
		if (addr.toLowerCase(java.util.Locale.US).startsWith("bitcoin:")) {
			addr = addr.substring("bitcoin:".length());
			int q = addr.indexOf('?');
			String query = "";
			if (q >= 0) {
				query = addr.substring(q + 1);
				addr = addr.substring(0, q);
			}
			for (String param : query.split("&")) {
				int eq = param.indexOf('=');
				if (eq > 0 && param.substring(0, eq)
						.equalsIgnoreCase("amount")) {
					amt = android.net.Uri.decode(param.substring(eq + 1));
				}
			}
		}
		pendingScanAddress = addr;
		pendingScanAmount = amt;
		pendingPayjoinUri = com.professor.zerion.android.vault.wallet.btc.payjoin
				.PayjoinAvailability.canOffer(raw) ? raw : null;
		showSendDialog();
	}

	private void launchScanner() {
		if (scanLauncher == null) {
			return;
		}
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

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_vault_wallet, container,
				false);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		listScroll = v.findViewById(R.id.list_scroll);
		detailScroll = v.findViewById(R.id.detail_scroll);
		walletsContainer = v.findViewById(R.id.wallets_container);
		walletEmpty = v.findViewById(R.id.wallet_empty);
		headerTitle = v.findViewById(R.id.wallet_header_title);
		balanceValue = v.findViewById(R.id.balance_value);
		balanceFiat = v.findViewById(R.id.balance_fiat);
		balanceValue.setOnClickListener(x -> cycleDisplayUnit());
		balanceFiat.setOnClickListener(x -> cycleDisplayUnit());
		historyContainer = v.findViewById(R.id.history_container);
		historyEmpty = v.findViewById(R.id.history_empty);
		progress = v.findViewById(R.id.wallet_status_row);
		syncStatus = v.findViewById(R.id.wallet_sync_status);
		syncStatus.setText(getString(R.string.wallet_sync_synced));
		fabAdd = v.findViewById(R.id.fab_add);

		fabAdd.setOnClickListener(x -> showAddDialog());
		v.findViewById(R.id.wallet_back).setOnClickListener(x -> onBack());
		v.findViewById(R.id.btn_receive).setOnClickListener(x -> showReceiveDialog());
		v.findViewById(R.id.btn_send).setOnClickListener(x -> showSendDialog());
		v.findViewById(R.id.btn_refresh).setOnClickListener(
				x -> viewModel.refreshBtcWallet());
		v.findViewById(R.id.btn_scan).setOnClickListener(x -> launchScanner());
		settingsGear = v.findViewById(R.id.wallet_settings);
		settingsGear.setOnClickListener(x -> showWalletSettings());
		settingsGear.setVisibility(View.GONE);

		backCallback = new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				onBack();
			}
		};
		requireActivity().getOnBackPressedDispatcher()
				.addCallback(getViewLifecycleOwner(), backCallback);

		observe();
		viewModel.loadWallets();
		return v;
	}

	private void observe() {
		viewModel.getWallets().observe(getViewLifecycleOwner(),
				this::renderWallets);
		viewModel.getWalletBusy().observe(getViewLifecycleOwner(), busy -> {
			busyNow = Boolean.TRUE.equals(busy);
			progress.setVisibility(busyNow ? View.VISIBLE : View.GONE);
			updateSyncStatus();
		});
		viewModel.getWalletError().observe(getViewLifecycleOwner(), ev -> {
			String err = ev == null ? null : ev.getIfNotHandled();
			if (err != null && !err.isEmpty()) {
				toast(err);
			}
			if (err != null && !err.isEmpty() && pendingOpen != null) {
				pendingOpen = null;
				dismissUnlocking();
			}
		});
		viewModel.getBtcBalanceSat().observe(getViewLifecycleOwner(), sat -> {
			renderBalance();
			if (sat != null && !feeRequested) {
				feeRequested = true;
				viewModel.loadCachedPrice();
				viewModel.loadFeeOptions();
				viewModel.loadPrice();
			}
		});
		viewModel.getPriceStale().observe(getViewLifecycleOwner(), s -> {
			priceStale = s != null && s;
		});
		viewModel.getBtcRates().observe(getViewLifecycleOwner(), r -> {
			if (r != null) {
				rates = r;
				renderBalance();
			}
		});
		viewModel.getWalletCurrency().observe(getViewLifecycleOwner(), cur -> {
			if (cur != null) {
				fiatCurrency = cur;
				renderBalance();
			}
		});
		viewModel.loadWalletCurrency();
		viewModel.loadCachedPrice();
		viewModel.getWalletNodeList().observe(getViewLifecycleOwner(),
				list -> {
					nodeList = list;
					if (showNodesWhenLoaded && list != null && !list.isEmpty()) {
						showNodesWhenLoaded = false;
						showNodesDialog();
					}
				});
		viewModel.getWalletSelectedNode().observe(getViewLifecycleOwner(),
				sel -> selectedNode = sel);
		viewModel.getWalletNodeHealth().observe(getViewLifecycleOwner(), h -> {
			if (h == null) {
				return;
			}
			int bar = h.lastIndexOf('|');
			if (bar < 0) {
				return;
			}
			String node = h.substring(0, bar);
			nodeHealth.put(node, "ok".equals(h.substring(bar + 1)));
			TextView dot = nodeDots.get(node);
			if (dot != null) {
				dot.setTextColor(colorForHealth(node));
			}
		});
		viewModel.getWalletPinPrompt().observe(getViewLifecycleOwner(), p -> {
			if (p == null) {
				return;
			}
			int bar = p.lastIndexOf('|');
			if (bar < 0) {
				return;
			}
			String node = p.substring(0, bar);
			String fp = p.substring(bar + 1);
			if ("none".equals(fp)) {
				toast(getString(R.string.wallet_pin_not_tls));
			} else if ("error".equals(fp)) {
				toast(getString(R.string.wallet_pin_read_failed));
			} else {
				showPinConfirmDialog(node, fp);
			}
		});
		viewModel.getWalletCoins().observe(getViewLifecycleOwner(), list -> {
			coins = list;
			if (showCoinsWhenLoaded) {
				showCoinsWhenLoaded = false;
				showCoinControlDialog();
			}
		});
		viewModel.getWalletExtremeMode().observe(getViewLifecycleOwner(),
				on -> extremeMode = Boolean.TRUE.equals(on));
		viewModel.getWalletMergePrompt().observe(getViewLifecycleOwner(),
				this::showMergeWarningDialog);
		viewModel.getWalletSendReview().observe(getViewLifecycleOwner(), ev -> {
			VaultViewModel.SendReview r = ev == null ? null
					: ev.getIfNotHandled();
			if (r != null) {
				dismissPreparing();
				showAuthSendDialog(r);
			}
		});
		viewModel.getWalletPreparing().observe(getViewLifecycleOwner(), p -> {
			if (p != null && p) {
				showPreparing();
			} else {
				dismissPreparing();
			}
		});
		viewModel.getWalletDeleted().observe(getViewLifecycleOwner(), ev -> {
			String delId = ev == null ? null : ev.getIfNotHandled();
			if (delId != null) {
				toast(getString(R.string.wallet_deleted));
				backToList();
			}
		});
		viewModel.getWalletOpened().observe(getViewLifecycleOwner(), ev -> {
			String id = ev == null ? null : ev.getIfNotHandled();
			if (id != null) {
				onWalletOpened(id);
			}
		});
		viewModel.getPayjoinState().observe(getViewLifecycleOwner(),
				this::onPayjoinState);
		viewModel.getPayjoinReview().observe(getViewLifecycleOwner(),
				this::showPayjoinReview);
		viewModel.getPayjoinFailure().observe(getViewLifecycleOwner(),
				this::showPayjoinFailure);
		viewModel.getSpAddress().observe(getViewLifecycleOwner(),
				a -> spAddress = a);
		viewModel.getSpInfo().observe(getViewLifecycleOwner(),
				info -> spInfo = info);
		viewModel.getWalletSpEnabled().observe(getViewLifecycleOwner(),
				on -> spEnabled = on != null && on);
		viewModel.getSpTxid().observe(getViewLifecycleOwner(), ev -> {
			String txid = ev == null ? null : ev.getIfNotHandled();
			if (txid != null && !txid.isEmpty()) {
				toast(getString(R.string.wallet_sent, shorten(txid)));
			}
		});
		viewModel.getBtcFeeOptions().observe(getViewLifecycleOwner(), opts -> {
			if (opts != null && opts.length == 3) {
				feeOptions = opts;
			}
		});
		viewModel.getBtcReceiveAddress().observe(getViewLifecycleOwner(),
				addr -> {
					receiveAddress = addr;
					if (reopenReceive && addr != null) {
						reopenReceive = false;
						showReceiveDialog();
					}
				});
		viewModel.getWalletOnline().observe(getViewLifecycleOwner(), online -> {
			walletOnline = online == null || online;
			if (syncStatus != null) {
				syncStatus.setText(walletOnline
						? getString(R.string.wallet_sync_synced)
						: getString(R.string.wallet_receive_offline));
			}
		});
		viewModel.getBtcHistory().observe(getViewLifecycleOwner(),
				this::renderHistory);
		viewModel.getBtcFeeRate().observe(getViewLifecycleOwner(), r -> {
			if (r != null) {
				feeRate = r;
			}
		});
		viewModel.getBtcTxid().observe(getViewLifecycleOwner(), ev -> {
			String txid = ev == null ? null : ev.getIfNotHandled();
			if (txid != null && !txid.isEmpty()) {
				toast(getString(R.string.wallet_sent, shorten(txid)));
			}
		});
		viewModel.getWalletSeedReveal().observe(getViewLifecycleOwner(), seed -> {
			if (seed != null && !seed.isEmpty()) {
				viewModel.clearSeedReveal();
				showSeedDialog(seed);
			}
		});
	}

	private void renderWallets(List<WalletRecord> list) {
		walletsContainer.removeAllViews();
		walletEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
		for (WalletRecord w : list) {
			walletsContainer.addView(buildWalletRow(w));
		}
	}

	private View buildWalletRow(WalletRecord w) {
		View row = LayoutInflater.from(requireContext())
				.inflate(R.layout.item_vault_wallet, walletsContainer, false);
		((TextView) row.findViewById(R.id.wallet_row_name)).setText(w.name);
		((TextView) row.findViewById(R.id.wallet_row_coin))
				.setText(w.coin.getLabel());
		row.setOnClickListener(x -> openWallet(w));
		row.setOnLongClickListener(x -> {
			confirmDelete(w);
			return true;
		});
		return row;
	}

	private void openWallet(WalletRecord w) {
		if (w.hasPassword) {
			promptWalletPassword(w);
		} else {
			requestOpen(w, null);
		}
	}

	private void requestOpen(WalletRecord w, @Nullable char[] password) {
		pendingOpen = w;
		showUnlocking();
		viewModel.openBtcWallet(w.id, password);
	}

	private void onWalletOpened(String walletId) {
		dismissUnlocking();
		WalletRecord w = pendingOpen;
		if (w == null || !w.id.equals(walletId)) {
			return;
		}
		pendingOpen = null;
		openWalletId = walletId;
		headerTitle.setText(w.name);
		balanceValue.setText("");
		historyContainer.removeAllViews();
		historyEmpty.setVisibility(View.GONE);
		showDetail(true);
		startPolling();
	}

	private void onBack() {
		if (openWalletId != null) {
			backToList();
		} else {
			if (!isAdded()) {
				return;
			}
			showNextFragment(VaultDashboardFragment.newInstance());
		}
	}

	private void promptWalletPassword(WalletRecord w) {
		Context ctx = requireContext();
		TextInputLayout til = new TextInputLayout(ctx);
		TextInputEditText input = new TextInputEditText(til.getContext());
		input.setHint(R.string.wallet_password_prompt);
		input.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		til.addView(input);
		int p = dp(20);
		til.setPadding(p, 0, p, 0);
		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(ctx)
						.setTitle(R.string.wallet_password_prompt)
						.setView(til)
						.setCancelable(false)
						.setPositiveButton(android.R.string.ok, null)
						.setNegativeButton(android.R.string.cancel,
								(d, wch) -> backToList())
						.create();
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
					char[] pw = chars(input);
					if (pw.length == 0) {
						toast(getString(R.string.wallet_password_required));
						return;
					}
					requestOpen(w, pw);
					dlg.dismiss();
				}));
		track(dlg);
		dlg.show();
	}

	private void renderHistory(@Nullable List<BtcWallet.TxSummary> history) {
		historyContainer.removeAllViews();
		if (history == null || history.isEmpty()) {
			historyEmpty.setVisibility(View.VISIBLE);
			return;
		}
		historyEmpty.setVisibility(View.GONE);
		LayoutInflater inf = LayoutInflater.from(requireContext());
		for (BtcWallet.TxSummary tx : history) {
			View row = inf.inflate(R.layout.item_vault_wallet_tx,
					historyContainer, false);
			ImageView icon = row.findViewById(R.id.tx_direction_icon);
			TextView amount = row.findViewById(R.id.tx_amount);
			TextView txid = row.findViewById(R.id.tx_txid);
			TextView status = row.findViewById(R.id.tx_status);

			boolean received = tx.netSat >= 0;
			if (!tx.netKnown) {
				amount.setText("—");
				amount.setTextColor(colorRes(R.color.zerion_text_secondary));
				icon.setImageResource(R.drawable.ic_call_received);
				icon.setColorFilter(colorRes(R.color.zerion_text_secondary));
			} else if (received) {
				amount.setText("+" + formatBtc(tx.netSat));
				amount.setTextColor(colorRes(R.color.zerion_success));
				icon.setImageResource(R.drawable.ic_call_received);
				icon.setColorFilter(colorRes(R.color.zerion_success));
			} else {
				amount.setText("-" + formatBtc(-tx.netSat));
				amount.setTextColor(colorRes(R.color.zerion_red_500));
				icon.setImageResource(R.drawable.ic_call_made);
				icon.setColorFilter(colorRes(R.color.zerion_red_500));
			}

			txid.setText(shorten(tx.txid));
			if (BtcWallet.STATE_BROADCASTING.equals(tx.state)) {
				status.setText(R.string.wallet_tx_state_broadcasting);
				status.setTextColor(colorRes(R.color.zerion_warning));
			} else if (BtcWallet.STATE_POSSIBLY_SENT.equals(tx.state)) {
				status.setText(R.string.wallet_tx_state_possibly_sent);
				status.setTextColor(colorRes(R.color.zerion_warning));
			} else if (BtcWallet.STATE_FAILED.equals(tx.state)) {
				status.setText(R.string.wallet_tx_state_failed);
				status.setTextColor(colorRes(R.color.zerion_red_500));
			} else if (tx.isPending()) {
				status.setText(R.string.wallet_pending);
				status.setTextColor(colorRes(R.color.zerion_warning));
			} else {
				status.setText(getString(R.string.wallet_confirmations,
						tx.confirmations));
				status.setTextColor(colorRes(R.color.zerion_text_secondary));
			}
			row.setOnClickListener(v -> showTxDetailDialog(tx));
			historyContainer.addView(row);
		}
	}

	private static final String MEMPOOL_ONION =
			"http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad"
					+ ".onion/tx/";

	private void showTxDetailDialog(BtcWallet.TxSummary tx) {
		Context ctx = requireContext();
		String direction = tx.netKnown
				? (tx.netSat >= 0 ? getString(R.string.wallet_received)
						: getString(R.string.wallet_sent_label))
				: "—";
		String amount = tx.netKnown
				? (tx.netSat >= 0 ? "+" + formatBtc(tx.netSat)
						: "-" + formatBtc(-tx.netSat)) + " BTC"
				: "—";
		String statusLine = tx.isPending()
				? getString(R.string.wallet_pending)
				: getString(R.string.wallet_confirmations, tx.confirmations);
		StringBuilder msg = new StringBuilder();
		msg.append(getString(R.string.wallet_tx_amount_label)).append(": ")
				.append(amount).append('\n');
		msg.append(getString(R.string.wallet_tx_direction_label)).append(": ")
				.append(direction).append('\n');
		msg.append(getString(R.string.wallet_tx_status_label)).append(": ")
				.append(statusLine);
		if (!tx.isPending() && tx.height > 0) {
			msg.append('\n').append(getString(R.string.wallet_tx_block_label))
					.append(": ").append(tx.height);
		}
		msg.append("\n\n").append(getString(R.string.wallet_tx_id_label))
				.append(":\n").append(tx.txid);

		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_tx_detail_title)
				.setMessage(msg.toString())
				.setPositiveButton(R.string.wallet_tx_copy_id, (d, w) -> {
					copyToClipboard(tx.txid);
					toast(getString(R.string.wallet_tx_id_copied));
				})
				.setNeutralButton(R.string.wallet_tx_mempool, (d, w) ->
						openMempool(tx.txid))
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void openMempool(String txid) {
		try {
			startActivity(new android.content.Intent(
					android.content.Intent.ACTION_VIEW,
					android.net.Uri.parse(MEMPOOL_ONION + txid)));
		} catch (Throwable e) {
			copyToClipboard(txid);
			toast(getString(R.string.wallet_tx_mempool_no_browser));
		}
	}

	private void copyToClipboard(String text) {
		android.content.ClipboardManager cm =
				(android.content.ClipboardManager) requireContext()
						.getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm != null) {
			cm.setPrimaryClip(
					android.content.ClipData.newPlainText("txid", text));
		}
	}

	private Runnable pendingSeedClipClear;

	private void copySensitiveToClipboard(String text) {
		Context ctx = getContext();
		if (ctx == null) {
			return;
		}
		ClipboardManager cm = (ClipboardManager) ctx
				.getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm == null) {
			return;
		}
		ClipData clip = ClipData.newPlainText("", text);
		if (android.os.Build.VERSION.SDK_INT
				>= android.os.Build.VERSION_CODES.TIRAMISU) {
			android.os.PersistableBundle extras =
					new android.os.PersistableBundle();
			extras.putBoolean(
					android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
			clip.getDescription().setExtras(extras);
		}
		cm.setPrimaryClip(clip);
		if (pendingSeedClipClear != null) {
			pollHandler.removeCallbacks(pendingSeedClipClear);
		}
		pendingSeedClipClear = () -> {
			pendingSeedClipClear = null;
			try {
				if (cm.hasPrimaryClip()) {
					ClipData cur = cm.getPrimaryClip();
					if (cur != null && cur.getItemCount() > 0) {
						CharSequence ct = cur.getItemAt(0).getText();
						if (ct != null && ct.toString().equals(text)) {
							cm.setPrimaryClip(
									ClipData.newPlainText("", "\u200B"));
						}
					}
				}
			} catch (Throwable ignored) {
			}
		};
		pollHandler.postDelayed(pendingSeedClipClear, 30_000L);
	}

	private void showAddDialog() {
		CharSequence[] options = {
				getString(R.string.wallet_create_new),
				getString(R.string.wallet_import_existing)
		};
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_add)
				.setItems(options, (d, which) -> {
					if (which == 0) {
						showCreateDialog();
					} else {
						showImportDialog();
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showCreateDialog() {
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);

		TextInputEditText name = field(ctx, box, R.string.wallet_name_hint,
				InputType.TYPE_CLASS_TEXT);
		TextInputEditText pass = field(ctx, box, R.string.wallet_password_prompt,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		TextInputEditText confirm = field(ctx, box,
				R.string.wallet_password_confirm_hint,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);

		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(ctx)
						.setTitle(R.string.wallet_create_new)
						.setView(box)
						.setPositiveButton(R.string.wallet_create, null)
						.setNegativeButton(android.R.string.cancel, null)
						.create();
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
					char[] pw = chars(pass);
					if (pw.length == 0) {
						toast(getString(R.string.wallet_password_required));
						return;
					}
					if (!java.util.Arrays.equals(pw, chars(confirm))) {
						toast(getString(R.string.wallet_password_mismatch));
						return;
					}
					String n = text(name);
					if (n.isEmpty()) {
						n = getString(R.string.wallet_card_title);
					}
					viewModel.createBtcWallet(n, pw);
					dlg.dismiss();
				}));
		track(dlg);
		dlg.show();
	}

	private void showImportDialog() {
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);

		TextInputEditText name = field(ctx, box, R.string.wallet_name_hint,
				InputType.TYPE_CLASS_TEXT);
		TextInputEditText phrase = field(ctx, box,
				R.string.wallet_recovery_phrase_hint,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
						| InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		phrase.setMinLines(2);
		TextInputEditText pass = field(ctx, box, R.string.wallet_password_prompt,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		TextInputEditText confirm = field(ctx, box,
				R.string.wallet_password_confirm_hint,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);

		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(ctx)
						.setTitle(R.string.wallet_import_existing)
						.setView(box)
						.setPositiveButton(R.string.wallet_import, null)
						.setNegativeButton(android.R.string.cancel, null)
						.create();
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
					char[] ph = chars(phrase);
					if (ph.length == 0) {
						toast(getString(R.string.wallet_import_invalid));
						return;
					}
					char[] pw = chars(pass);
					if (pw.length == 0) {
						toast(getString(R.string.wallet_password_required));
						return;
					}
					if (!java.util.Arrays.equals(pw, chars(confirm))) {
						toast(getString(R.string.wallet_password_mismatch));
						return;
					}
					String n = text(name);
					if (n.isEmpty()) {
						n = getString(R.string.wallet_card_title);
					}
					viewModel.importBtcWallet(n, ph, pw);
					dlg.dismiss();
				}));
		track(dlg);
		dlg.show();
	}

	private void showReceiveDialog() {
		String addr = receiveAddress;
		if (addr == null || addr.isEmpty()) {
			reopenReceive = true;
			viewModel.ensureReceiveAddress();
			return;
		}
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(Gravity.CENTER_HORIZONTAL);
		int p = dp(20);
		box.setPadding(p, p, p, 0);

		Bitmap qr = QrCodeUtils.generateQrCode(addr, dp(220));
		if (qr != null) {
			ImageView img = new ImageView(ctx);
			img.setImageBitmap(qr);
			LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
					dp(220), dp(220));
			img.setLayoutParams(ip);
			box.addView(img);
		}
		TextView addrView = new TextView(ctx);
		addrView.setText(addr);
		addrView.setTextIsSelectable(true);
		addrView.setPadding(0, dp(16), 0, 0);
		addrView.setTextColor(colorAttr(android.R.attr.textColorPrimary));
		box.addView(addrView);

		if (!walletOnline) {
			TextView offline = new TextView(ctx);
			offline.setText(R.string.wallet_receive_offline);
			offline.setPadding(0, dp(12), 0, 0);
			offline.setGravity(Gravity.CENTER);
			offline.setTextColor(colorRes(R.color.zerion_warning));
			box.addView(offline);
		}

		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_receive_title)
				.setMessage(R.string.wallet_receive_hint)
				.setView(box)
				.setPositiveButton(R.string.wallet_copy_address, (d, w) -> {
					ClipboardManager cm = (ClipboardManager) ctx
							.getSystemService(Context.CLIPBOARD_SERVICE);
					if (cm != null) {
						cm.setPrimaryClip(ClipData.newPlainText("btc", addr));
						toast(getString(R.string.wallet_address_copied));
					}
				})
				.setNeutralButton(R.string.wallet_receive_new, (d, w) -> {
					reopenReceive = true;
					viewModel.newReceiveAddress();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showSendDialog() {
		final java.util.Set<String> manual = manualCoins;
		manualCoins = null;
		Context ctx = requireContext();
		View form = LayoutInflater.from(ctx)
				.inflate(R.layout.dialog_wallet_send, null);
		TextView available = form.findViewById(R.id.send_available);
		TextInputLayout tilAddress = form.findViewById(R.id.til_address);
		TextInputEditText address = form.findViewById(R.id.send_address);
		TextInputEditText amount = form.findViewById(R.id.send_amount);
		View maxBtn = form.findViewById(R.id.send_max);
		com.google.android.material.button.MaterialButtonToggleGroup feeToggle =
				form.findViewById(R.id.fee_toggle);
		TextView feeDetail = form.findViewById(R.id.fee_detail);

		Long bal = viewModel.getBtcBalanceSat().getValue();
		available.setText(bal == null ? "—" : formatBtc(bal));

		final int[] feeBtnIds = {R.id.fee_eco, R.id.fee_norm, R.id.fee_prio};
		feeToggle.check(feeBtnIds[feeChoice]);
		feeDetail.setText(feeDetailText());
		feeToggle.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
			if (isChecked) {
				for (int i = 0; i < 3; i++) {
					if (feeBtnIds[i] == checkedId) {
						feeChoice = i;
					}
				}
				feeDetail.setText(feeDetailText());
			}
		});

		TextView fiatEquiv = form.findViewById(R.id.send_fiat_equiv);
		View unitToggle = form.findViewById(R.id.send_unit_toggle);
		TextInputLayout tilAmount = form.findViewById(R.id.til_amount);
		final int[] unit = {0};
		Runnable refreshEquiv = () -> {
			tilAmount.setSuffixText(unitName(unit[0]));
			fiatEquiv.setText(sendEquivText(unit[0], text(amount)));
		};
		unitToggle.setVisibility(rates != null && fiatPrice() > 0
				? View.VISIBLE : View.GONE);
		unitToggle.setOnClickListener(v -> {
			if (rates == null || fiatPrice() <= 0) {
				unit[0] = 0;
			} else {
				unit[0] = (unit[0] + 1) % 3;
			}
			refreshEquiv.run();
		});
		refreshEquiv.run();

		final boolean[] ignore = {false};
		final boolean[] sweep = {false};
		maxBtn.setOnClickListener(v -> {
			Long b = viewModel.getBtcBalanceSat().getValue();
			if (b == null || b <= 0) {
				return;
			}
			sweep[0] = true;
			unit[0] = 0;
			ignore[0] = true;
			amount.setText(BigDecimal.valueOf(b).movePointLeft(8).toPlainString());
			if (amount.getText() != null) {
				amount.setSelection(amount.getText().length());
			}
			ignore[0] = false;
			refreshEquiv.run();
		});
		amount.addTextChangedListener(new android.text.TextWatcher() {
			public void beforeTextChanged(CharSequence s, int a, int b, int c) {
			}

			public void onTextChanged(CharSequence s, int a, int b, int c) {
			}

			public void afterTextChanged(android.text.Editable s) {
				if (!ignore[0]) {
					sweep[0] = false;
				}
				refreshEquiv.run();
			}
		});

		if (pendingScanAddress != null) {
			address.setText(pendingScanAddress);
			pendingScanAddress = null;
		}
		if (pendingScanAmount != null) {
			amount.setText(pendingScanAmount);
			pendingScanAmount = null;
		}

		viewModel.loadFeeOptions();

		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(ctx)
						.setTitle(R.string.wallet_send_title)
						.setView(form)
						.setPositiveButton(R.string.wallet_send_review, null)
						.setNegativeButton(android.R.string.cancel, null)
						.create();
		form.findViewById(R.id.send_paste).setOnClickListener(v -> {
			String clip = readClipboard();
			if (clip != null && !clip.isEmpty()) {
				address.setText(clip.trim());
			}
		});
		tilAddress.setEndIconOnClickListener(v -> {
			dlg.dismiss();
			launchScanner();
		});
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
					String to = text(address);
					if (to.isEmpty()) {
						toast(getString(R.string.wallet_send_no_recipient));
						return;
					}
					long sat = 0;
					if (!sweep[0]) {
						sat = computeSats(unit[0], text(amount));
						if (sat <= 0) {
							toast(getString(R.string.wallet_amount_invalid));
							return;
						}
					}
					double rate = feeOptions != null
							&& feeChoice < feeOptions.length
							? feeOptions[feeChoice] : feeRate;
					dlg.dismiss();
					confirmSend(to, sat, sweep[0], rate, manual);
				}));
		track(dlg);
		dlg.show();
	}

	private void confirmSend(String to, long sat, boolean sweep, double rate,
			@Nullable java.util.Set<String> manual) {
		if (!com.professor.zerion.android.vault.wallet.btc.BtcKeys
				.isValidAddress(to)) {
			toast(getString(R.string.wallet_send_bad_address));
			return;
		}
		String pj = pendingPayjoinUri;
		if (pj != null && manual == null
				&& com.professor.zerion.android.vault.wallet.btc.payjoin
				.PayjoinAvailability.canOffer(pj)
				&& com.professor.zerion.android.vault.wallet.btc.payjoin.PayjoinUri
				.detect(pj).address.equals(to)) {
			pendingPayjoinUri = null;
			lastPjUri = pj;
			lastPjTo = to;
			lastPjSat = sat;
			lastPjRate = rate;
			lastPjSweep = sweep;
			viewModel.startPayjoin(pj, rate, sweep);
			return;
		}
		pendingPayjoinUri = null;
		viewModel.prepareSend(to, sat, rate, sweep, manual, false);
	}

	private void onPayjoinState(com.professor.zerion.android.vault.wallet.btc
			.PayjoinFlowController.State state) {
		String label;
		switch (state) {
			case PREPARING:
				label = getString(R.string.payjoin_state_preparing);
				break;
			case CONNECTING_TOR:
				label = getString(R.string.payjoin_state_connecting);
				break;
			case NEGOTIATING:
				label = getString(R.string.payjoin_state_negotiating);
				break;
			case VALIDATING:
				label = getString(R.string.payjoin_state_validating);
				break;
			default:
				label = null;
				break;
		}
		if (label != null) {
			showPayjoinProgress(label);
		} else {
			dismissPayjoinProgress();
		}
	}

	private void showPayjoinProgress(String message) {
		android.content.Context ctx = getContext();
		if (ctx == null) {
			return;
		}
		dismissPayjoinProgress();
		payjoinProgressDialog = track(new MaterialAlertDialogBuilder(ctx)
				.setCancelable(false)
				.setMessage(message)
				.setNegativeButton(android.R.string.cancel,
						(d, w) -> viewModel.cancelPayjoin())
				.show());
	}

	private void dismissPayjoinProgress() {
		if (payjoinProgressDialog != null) {
			payjoinProgressDialog.dismiss();
			payjoinProgressDialog = null;
		}
	}

	private void showPreparing() {
		android.content.Context ctx = getContext();
		if (ctx == null) {
			return;
		}
		dismissPreparing();
		preparingDialog = track(new MaterialAlertDialogBuilder(ctx)
				.setCancelable(false)
				.setMessage(R.string.wallet_preparing_tx)
				.show());
	}

	private void dismissPreparing() {
		if (preparingDialog != null) {
			preparingDialog.dismiss();
			preparingDialog = null;
		}
	}

	private void showUnlocking() {
		android.content.Context ctx = getContext();
		if (ctx == null) {
			return;
		}
		dismissUnlocking();
		unlockingDialog = track(new MaterialAlertDialogBuilder(ctx)
				.setCancelable(false)
				.setMessage(R.string.wallet_unlocking)
				.show());
	}

	private void dismissUnlocking() {
		if (unlockingDialog != null) {
			unlockingDialog.dismiss();
			unlockingDialog = null;
		}
	}

	private void showPayjoinFailure(VaultViewModel.PayjoinFailure failure) {
		dismissPayjoinProgress();
		android.content.Context ctx = getContext();
		if (ctx == null || failure == null) {
			return;
		}
		MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.payjoin_unavailable_title)
				.setMessage(failure.message);
		if (failure.offerNormalFallback && lastPjTo != null) {
			b.setPositiveButton(R.string.payjoin_send_normal, (d, w) ->
					viewModel.payjoinFallbackToNormal(lastPjTo, lastPjSat,
							lastPjRate, lastPjSweep));
			if (lastPjUri != null) {
				b.setNeutralButton(R.string.payjoin_try_again, (d, w) ->
						viewModel.startPayjoin(lastPjUri, lastPjRate,
								lastPjSweep));
			}
		}
		b.setNegativeButton(android.R.string.cancel,
				(d, w) -> viewModel.cancelPayjoin());
		track(b.show());
	}

	private void showPayjoinReview(com.professor.zerion.android.vault.wallet.btc
			.PayjoinReviewData data) {
		dismissPayjoinProgress();
		android.content.Context ctx = getContext();
		if (ctx == null || data == null) {
			return;
		}
		String body = getString(R.string.payjoin_review_body, data.recipient,
				data.amountSat, data.feeSat, data.totalSat,
				data.analysis.level.name());
		final android.widget.EditText pin = new android.widget.EditText(ctx);
		pin.setInputType(android.text.InputType.TYPE_CLASS_TEXT
				| android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.payjoin_review_title)
				.setMessage(body)
				.setView(pin)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					CharSequence cs = pin.getText();
					char[] cred = new char[cs.length()];
					for (int i = 0; i < cs.length(); i++) {
						cred[i] = cs.charAt(i);
					}
					viewModel.authorizePayjoin(cred, data.fingerprint);
				})
				.setNegativeButton(android.R.string.cancel,
						(d, w) -> viewModel.cancelPayjoin())
				.show());
	}

	private void showAuthSendDialog(
			@Nullable VaultViewModel.SendReview review) {
		if (review == null) {
			return;
		}
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);

		TextView privacyLevel = new TextView(ctx);
		privacyLevel.setText(privacyLevelLabel(review.analysis.level));
		privacyLevel.setTextColor(privacyLevelColor(review.analysis.level));
		privacyLevel.setTextSize(15);
		privacyLevel.setTypeface(privacyLevel.getTypeface(),
				android.graphics.Typeface.BOLD);
		box.addView(privacyLevel);

		TextView privacyDetails = new TextView(ctx);
		privacyDetails.setText(privacyDetailsText(review.analysis));
		privacyDetails.setTextColor(colorRes(R.color.zerion_text_secondary));
		privacyDetails.setTextSize(13);
		privacyDetails.setPadding(0, dp(4), 0, dp(10));
		box.addView(privacyDetails);

		TextView summary = new TextView(ctx);
		String amountLine = review.sweep ? getString(R.string.wallet_send_all)
				: formatBtc(review.amountSat) + " BTC";
		summary.setText(getString(R.string.wallet_send_to) + ":\n"
				+ review.toAddress + "\n\n"
				+ getString(R.string.wallet_send_amount_label) + ":  "
				+ amountLine + "\n"
				+ getString(R.string.wallet_send_fee_label) + ":  "
				+ review.feeSat + " sats\n\n"
				+ getString(R.string.wallet_auth_send_message));
		summary.setTextColor(colorRes(R.color.zerion_text_primary));
		summary.setTextSize(13);
		box.addView(summary);
		TextInputEditText pin = field(ctx, box, R.string.wallet_auth_send_hint,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_auth_send_title)
				.setView(box)
				.setPositiveButton(R.string.wallet_auth_send_button, (d, w) -> {
					CharSequence cs = pin.getText();
					if (cs == null || cs.length() == 0) {
						viewModel.cancelSend();
						return;
					}
					char[] cred = new char[cs.length()];
					for (int i = 0; i < cs.length(); i++) {
						cred[i] = cs.charAt(i);
					}
					viewModel.authorizeAndSend(cred, review.fingerprint);
				})
				.setNegativeButton(android.R.string.cancel,
						(d, w) -> viewModel.cancelSend())
				.setOnCancelListener(d -> viewModel.cancelSend())
				.show());
	}

	private String unitName(int unit) {
		return unit == 1 ? "EUR" : unit == 2 ? "USD" : "BTC";
	}

	private double priceForUnit(int unit) {
		if (rates == null) {
			return 0;
		}
		return unit == 1 ? rates.eur : rates.usd;
	}

	private long computeSats(int unit, String s) {
		try {
			BigDecimal v = new BigDecimal(s.trim());
			if (unit == 0) {
				return v.movePointRight(8).setScale(0, RoundingMode.DOWN)
						.longValueExact();
			}
			double price = priceForUnit(unit);
			if (price <= 0) {
				return -1;
			}
			return (long) Math.floor(v.doubleValue() / price * 1e8);
		} catch (Exception e) {
			return -1;
		}
	}

	private String sendEquivText(int unit, String s) {
		if (s == null || s.trim().isEmpty()) {
			return "";
		}
		double val;
		try {
			val = Double.parseDouble(s.trim());
		} catch (Exception e) {
			return "";
		}
		if (unit == 0) {
			double price = fiatPrice();
			if (price <= 0) {
				return "";
			}
			return "≈ " + fiatSymbol() + String.format(java.util.Locale.US,
					"%.2f", val * price);
		}
		double price = priceForUnit(unit);
		if (price <= 0) {
			return "";
		}
		return "≈ " + String.format(java.util.Locale.US, "%.8f", val / price)
				+ " BTC";
	}

	private String feeDetailText() {
		int[] etas = {R.string.wallet_fee_economy_eta,
				R.string.wallet_fee_normal_eta, R.string.wallet_fee_priority_eta};
		String eta = getString(etas[feeChoice]);
		if (feeOptions != null && feeChoice < feeOptions.length) {
			return String.format(java.util.Locale.US, "%.1f",
					feeOptions[feeChoice]) + " sat/vB  ·  " + eta;
		}
		return eta;
	}

	@Nullable
	private String readClipboard() {
		ClipboardManager cm = (ClipboardManager) requireContext()
				.getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm != null && cm.getPrimaryClip() != null
				&& cm.getPrimaryClip().getItemCount() > 0) {
			CharSequence t = cm.getPrimaryClip().getItemAt(0)
					.coerceToText(requireContext());
			return t == null ? null : t.toString();
		}
		return null;
	}

	private void confirmDelete(WalletRecord w) {
		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(requireContext())
						.setTitle(R.string.wallet_delete)
						.setMessage(R.string.wallet_delete_warning_strong)
						.setPositiveButton(R.string.wallet_delete_continue,
								(d, wch) -> promptDeleteAuth(w.id))
						.setNegativeButton(android.R.string.cancel, null)
						.create();
		track(dlg);
		dlg.show();
	}

	private void showRoutingDialog() {
		String id = openWalletId;
		if (id == null) {
			return;
		}
		String current = viewModel.readWalletRouting(id);
		final String[] modes = {
				VaultViewModel.ROUTING_TOR,
				VaultViewModel.ROUTING_DIRECT,
				VaultViewModel.ROUTING_LOCAL
		};
		CharSequence[] labels = {
				getString(R.string.wallet_routing_tor),
				getString(R.string.wallet_routing_direct),
				getString(R.string.wallet_routing_local)
		};
		int checked = 0;
		for (int i = 0; i < modes.length; i++) {
			if (modes[i].equals(current)) {
				checked = i;
			}
		}
		final int[] sel = {checked};
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_settings_routing)
				.setSingleChoiceItems(labels, checked, (d, which) -> sel[0] = which)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					String mode = modes[sel[0]];
					if (VaultViewModel.ROUTING_DIRECT.equals(mode)
							&& !VaultViewModel.ROUTING_DIRECT.equals(current)) {
						confirmDirectRouting(id, mode);
					} else {
						viewModel.setWalletRouting(id, mode);
						toast(getString(R.string.wallet_routing_changed));
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void confirmDirectRouting(String id, String mode) {
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_routing_direct)
				.setMessage(R.string.wallet_routing_direct_warning)
				.setPositiveButton(R.string.wallet_routing_enable_direct,
						(d, w) -> {
							viewModel.setWalletRouting(id, mode);
							toast(getString(R.string.wallet_routing_changed));
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showDeleteWalletFlow() {
		String id = openWalletId;
		if (id == null) {
			return;
		}
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_delete)
				.setMessage(R.string.wallet_delete_warning_strong)
				.setPositiveButton(R.string.wallet_delete_continue,
						(d, w) -> promptDeleteAuth(id))
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void promptDeleteAuth(String id) {
		Context ctx = requireContext();
		TextInputLayout til = new TextInputLayout(ctx);
		TextInputEditText input = new TextInputEditText(til.getContext());
		input.setHint(R.string.wallet_password_prompt);
		input.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		til.addView(input);
		int p = dp(20);
		til.setPadding(p, 0, p, 0);
		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(ctx)
						.setTitle(R.string.wallet_delete)
						.setMessage(R.string.wallet_delete_auth_hint)
						.setView(til)
						.setPositiveButton(R.string.wallet_delete, null)
						.setNegativeButton(android.R.string.cancel, null)
						.create();
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
					char[] pw = chars(input);
					if (pw.length == 0) {
						toast(getString(R.string.wallet_password_required));
						return;
					}
					viewModel.deleteWalletWithAuth(id, pw);
					dlg.dismiss();
				}));
		track(dlg);
		dlg.show();
	}

	private void showBackup() {
		String id = openWalletId;
		if (id == null) {
			return;
		}
		Context ctx = requireContext();
		TextInputLayout til = new TextInputLayout(ctx);
		TextInputEditText input = new TextInputEditText(til.getContext());
		input.setHint(R.string.wallet_password_prompt);
		input.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		til.addView(input);
		int p = dp(20);
		til.setPadding(p, 0, p, 0);
		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_backup_title)
				.setView(til)
				.setPositiveButton(android.R.string.ok, (d, w) ->
						viewModel.revealSeed(id, chars(input)))
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showSeedDialog(String seed) {
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);

		TextView words = new TextView(ctx);
		words.setText(seed);
		words.setTextIsSelectable(true);
		words.setTextSize(16);
		words.setLineSpacing(dp(4), 1f);
		words.setTextColor(colorRes(R.color.zerion_text_primary));
		box.addView(words);

		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_backup_title)
				.setMessage(R.string.wallet_backup_warning)
				.setView(box)
				.setPositiveButton(android.R.string.ok, null)
				.setNeutralButton(R.string.wallet_copy_phrase, (d, w) -> {
					copySensitiveToClipboard(seed);
					toast(getString(R.string.wallet_phrase_copied));
				})
				.show());
	}

	private void showWalletSettings() {
		java.util.List<CharSequence> labels = new java.util.ArrayList<>();
		java.util.List<Integer> acts = new java.util.ArrayList<>();
		labels.add(getString(R.string.wallet_settings_currency));
		acts.add(0);
		labels.add(getString(R.string.wallet_settings_nodes));
		acts.add(1);
		if (openWalletId != null) {
			labels.add(getString(R.string.wallet_settings_rename));
			acts.add(2);
			labels.add(getString(R.string.wallet_settings_change_pw));
			acts.add(3);
			labels.add(getString(R.string.wallet_settings_sp));
			acts.add(4);
			labels.add(getString(R.string.wallet_settings_coincontrol));
			acts.add(5);
			labels.add(getString(R.string.wallet_settings_epm) + "  ·  "
					+ getString(extremeMode ? R.string.wallet_epm_on
							: R.string.wallet_epm_off));
			acts.add(6);
			labels.add(getString(R.string.wallet_settings_routing));
			acts.add(9);
			labels.add(getString(R.string.wallet_settings_backup));
			acts.add(7);
			labels.add(getString(R.string.wallet_settings_delete));
			acts.add(8);
		}
		viewModel.loadNodes();
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_settings_title)
				.setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
					switch (acts.get(which)) {
						case 0:
							showCurrencyPicker();
							break;
						case 1:
							showNodesDialog();
							break;
						case 2:
							showRenameDialog();
							break;
						case 3:
							showChangePasswordDialog();
							break;
						case 4:
							showSilentPaymentsDialog();
							break;
						case 5:
							showCoinsWhenLoaded = true;
							viewModel.loadCoins();
							break;
						case 6:
							showExtremeModeDialog();
							break;
						case 7:
							showBackup();
							break;
						case 8:
							showDeleteWalletFlow();
							break;
						case 9:
							showRoutingDialog();
							break;
						default:
							break;
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private String originLabel(com.professor.zerion.android.vault.wallet.btc
			.privacy.UtxoOrigin o) {
		switch (o) {
			case CHANGE:
				return getString(R.string.wallet_coin_change);
			case SILENT_PAYMENT:
				return getString(R.string.wallet_coin_sp);
			default:
				return getString(R.string.wallet_received);
		}
	}

	private void showCoinControlDialog() {
		Context ctx = requireContext();
		List<com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMeta>
				list = coins;
		if (list == null || list.isEmpty()) {
			toast(getString(R.string.wallet_coincontrol_empty));
			return;
		}
		final java.util.Set<String> selected = new java.util.HashSet<>();
		final java.util.Map<String, String> selCluster =
				new java.util.HashMap<>();
		LinearLayout container = new LinearLayout(ctx);
		container.setOrientation(LinearLayout.VERTICAL);
		container.setPadding(dp(14), dp(4), dp(14), dp(4));
		for (com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMeta m
				: list) {
			LinearLayout row = new LinearLayout(ctx);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(dp(4), dp(10), dp(4), dp(10));

			android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
			cb.setEnabled(!m.frozen);
			final String op = m.outpoint;
			final String cluster = m.clusterId;
			cb.setOnCheckedChangeListener((b, checked) -> {
				if (checked) {
					selected.add(op);
					selCluster.put(op, cluster);
				} else {
					selected.remove(op);
					selCluster.remove(op);
				}
			});

			TextView info = new TextView(ctx);
			info.setLayoutParams(new LinearLayout.LayoutParams(0,
					ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
			StringBuilder meta = new StringBuilder();
			meta.append(shorten(m.address)).append("  ·  ")
					.append(originLabel(m.origin)).append("  ·  ")
					.append(getString(R.string.wallet_coin_group)).append(' ')
					.append(shorten(m.clusterId));
			if (m.label != null && !m.label.isEmpty()) {
				meta.append("  ·  ").append(m.label);
			}
			if (m.frozen) {
				meta.append("  ·  ").append(getString(R.string.wallet_coin_frozen));
			}
			info.setText(formatBtc(m.valueSat) + " BTC\n" + meta);
			info.setTextColor(colorRes(R.color.zerion_text_primary));
			info.setTextSize(13);

			TextView toggle = new TextView(ctx);
			toggle.setPadding(dp(12), dp(6), dp(12), dp(6));
			toggle.setTextSize(13);
			toggle.setClickable(true);
			applyFreezeStyle(toggle, m.frozen);
			final boolean[] fz = {m.frozen};
			toggle.setOnClickListener(v -> {
				boolean next = !fz[0];
				fz[0] = next;
				viewModel.setUtxoFrozen(op, next);
				applyFreezeStyle(toggle, next);
				cb.setEnabled(!next);
				if (next) {
					cb.setChecked(false);
				}
				toast(getString(next ? R.string.wallet_coin_frozen_toast
						: R.string.wallet_coin_unfrozen_toast));
			});

			row.addView(cb);
			row.addView(info);
			row.addView(toggle);
			container.addView(row);
		}
		ScrollView sv = new ScrollView(ctx);
		sv.addView(container);
		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_settings_coincontrol)
				.setMessage(R.string.wallet_coincontrol_hint)
				.setView(sv)
				.setPositiveButton(R.string.wallet_coin_send_selected,
						(d, w) -> {
							if (selected.isEmpty()) {
								toast(getString(
										R.string.wallet_coin_select_none));
								return;
							}
							if (new java.util.HashSet<>(selCluster.values())
									.size() > 1) {
								toast(getString(
										R.string.wallet_coin_cross_cluster));
							}
							manualCoins = new java.util.HashSet<>(selected);
							showSendDialog();
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private String privacyLevelLabel(PrivacyAnalyzer.Level l) {
		switch (l) {
			case HIGH:
				return getString(R.string.wallet_privacy_level_high);
			case MEDIUM:
				return getString(R.string.wallet_privacy_level_medium);
			case LOW:
				return getString(R.string.wallet_privacy_level_low);
			default:
				return getString(R.string.wallet_privacy_unavailable);
		}
	}

	private int privacyLevelColor(PrivacyAnalyzer.Level l) {
		switch (l) {
			case HIGH:
				return colorRes(R.color.zerion_success);
			case MEDIUM:
				return colorRes(R.color.zerion_warning);
			case LOW:
				return colorRes(R.color.zerion_red_500);
			default:
				return colorRes(R.color.zerion_text_secondary);
		}
	}

	private String privacyDetailsText(PrivacyAnalyzer.Analysis a) {
		if (a.level == PrivacyAnalyzer.Level.UNAVAILABLE) {
			return getString(R.string.wallet_privacy_unavailable_detail);
		}
		StringBuilder sb = new StringBuilder();
		if (a.level == PrivacyAnalyzer.Level.HIGH) {
			sb.append(getString(R.string.wallet_privacy_headline_high))
					.append('\n');
		}
		for (PrivacyAnalyzer.Finding f : a.findings) {
			String t = findingText(f);
			if (t != null) {
				sb.append("•  ").append(t).append('\n');
			}
		}
		return sb.toString().trim();
	}

	@Nullable
	private String findingText(PrivacyAnalyzer.Finding f) {
		switch (f.code) {
			case PrivacyAnalyzer.MERGE_CLUSTERS:
				return getString(R.string.wallet_pf_merge, f.count);
			case PrivacyAnalyzer.SP_MIX:
				return getString(R.string.wallet_pf_spmix);
			case PrivacyAnalyzer.ADDRESS_REUSE:
				return getString(R.string.wallet_pf_reuse);
			case PrivacyAnalyzer.EXTRA_INPUTS:
				return getString(R.string.wallet_pf_extra);
			case PrivacyAnalyzer.COMMON_INPUT:
				return getString(R.string.wallet_pf_common, f.count);
			case PrivacyAnalyzer.SINGLE_CLUSTER:
				return getString(R.string.wallet_pf_single);
			case PrivacyAnalyzer.NO_REUSE:
				return getString(R.string.wallet_pf_noreuse);
			case PrivacyAnalyzer.NO_UNRELATED:
				return getString(R.string.wallet_pf_nounrelated);
			case PrivacyAnalyzer.CHANGE_ISOLATED:
				return getString(R.string.wallet_pf_change);
			default:
				return null;
		}
	}

	private void showExtremeModeDialog() {
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_epm_title)
				.setMessage(R.string.wallet_epm_explain)
				.setPositiveButton(extremeMode ? R.string.wallet_epm_disable
						: R.string.wallet_epm_enable, (d, w) ->
						viewModel.setExtremeMode(!extremeMode))
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showMergeWarningDialog(
			@Nullable VaultViewModel.MergePrompt prompt) {
		if (prompt == null) {
			return;
		}
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_merge_title)
				.setMessage(getString(R.string.wallet_merge_msg,
						prompt.clusterCount))
				.setPositiveButton(R.string.wallet_merge_proceed, (d, w) ->
						viewModel.confirmSendMerge(prompt))
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void applyFreezeStyle(TextView t, boolean frozen) {
		t.setText(frozen ? getString(R.string.wallet_coin_frozen)
				: getString(R.string.wallet_coin_freeze));
		t.setTextColor(colorRes(frozen ? R.color.zerion_red_500
				: R.color.zerion_primary_accent));
	}

	private int colorForHealth(String node) {
		Boolean ok = nodeHealth.get(node);
		if (ok == null) {
			return colorRes(R.color.zerion_text_secondary);
		}
		return colorRes(ok ? R.color.zerion_success : R.color.zerion_red_500);
	}

	private void showNodesDialog() {
		if (nodeList == null || nodeList.isEmpty()) {
			showNodesWhenLoaded = true;
			viewModel.loadNodes();
			return;
		}
		Context ctx = requireContext();
		LinearLayout container = new LinearLayout(ctx);
		container.setOrientation(LinearLayout.VERTICAL);
		container.setPadding(dp(14), dp(4), dp(14), dp(4));
		nodeDots.clear();
		final java.util.Map<String, TextView> marks = new java.util.HashMap<>();
		final java.util.Map<String, TextView> labels =
				new java.util.HashMap<>();

		for (String node : nodeList) {
			LinearLayout row = new LinearLayout(ctx);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(dp(6), dp(14), dp(6), dp(14));
			TypedValue bg = new TypedValue();
			ctx.getTheme().resolveAttribute(
					android.R.attr.selectableItemBackground, bg, true);
			row.setBackgroundResource(bg.resourceId);
			row.setClickable(true);

			TextView dot = new TextView(ctx);
			dot.setText("●");
			dot.setTextSize(13);
			dot.setTextColor(colorForHealth(node));
			nodeDots.put(node, dot);

			TextView label = new TextView(ctx);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
					0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			lp.setMarginStart(dp(12));
			label.setLayoutParams(lp);
			String labelBase = node + "  ·  " + nodeModeLabel(node);
			label.setText(node.equals(VaultViewModel.preferredDefaultNode())
					? labelBase + "  ("
							+ getString(R.string.wallet_node_default) + ")"
					: labelBase);
			label.setTextColor(colorRes(R.color.zerion_text_primary));
			label.setTextSize(14);
			boolean sel = node.equals(selectedNode);
			label.setTypeface(null,
					sel ? android.graphics.Typeface.BOLD
							: android.graphics.Typeface.NORMAL);
			labels.put(node, label);

			TextView mark = new TextView(ctx);
			mark.setText(sel ? "✓" : "");
			mark.setTextSize(16);
			mark.setTextColor(colorRes(R.color.zerion_primary_accent));
			marks.put(node, mark);

			row.addView(dot);
			row.addView(label);
			row.addView(mark);

			final String n = node;
			row.setOnClickListener(v -> {
				selectedNode = n;
				viewModel.selectNode(n);
				for (java.util.Map.Entry<String, TextView> e
						: marks.entrySet()) {
					e.getValue().setText(e.getKey().equals(n) ? "✓" : "");
				}
				for (java.util.Map.Entry<String, TextView> e
						: labels.entrySet()) {
					e.getValue().setTypeface(null, e.getKey().equals(n)
							? android.graphics.Typeface.BOLD
							: android.graphics.Typeface.NORMAL);
				}
			});
			row.setOnLongClickListener(v -> {
				if (!n.equals(VaultViewModel.preferredDefaultNode())) {
					viewModel.deleteNode(n);
					toast(getString(R.string.wallet_node_remove));
				}
				return true;
			});
			container.addView(row);
			viewModel.checkNode(node);
		}

		ScrollView sv = new ScrollView(ctx);
		sv.addView(container);
		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_settings_nodes)
				.setMessage(R.string.wallet_node_hint)
				.setView(sv)
				.setPositiveButton(R.string.wallet_node_add,
						(d, w) -> showAddNodeDialog())
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener(d -> nodeDots.clear())
				.show());
	}

	private void showNodeOptions() {
		String node = selectedNode;
		if (node == null) {
			return;
		}
		boolean isDefault = node.equals(VaultViewModel.preferredDefaultNode());
		java.util.List<CharSequence> labels = new java.util.ArrayList<>();
		labels.add(getString(R.string.wallet_node_test));
		final boolean canPin = isTlsNode(node);
		if (canPin) {
			labels.add(getString(R.string.wallet_node_pin));
		}
		final boolean removable = !isDefault;
		if (removable) {
			labels.add(getString(R.string.wallet_node_remove));
		}
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(node)
				.setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
					CharSequence chosen = labels.get(which);
					if (chosen.equals(getString(R.string.wallet_node_test))) {
						viewModel.checkNode(node);
						toast(getString(R.string.wallet_node_testing));
					} else if (chosen.equals(
							getString(R.string.wallet_node_pin))) {
						viewModel.captureNodePin(node);
						toast(getString(R.string.wallet_node_testing));
					} else {
						viewModel.deleteNode(node);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showAddNodeDialog() {
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);
		TextInputEditText host = field(ctx, box, R.string.wallet_node_host,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		TextInputEditText port = field(ctx, box, R.string.wallet_node_port,
				InputType.TYPE_CLASS_NUMBER);
		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_node_add)
				.setMessage(R.string.wallet_node_add_hint)
				.setView(box)
				.setPositiveButton(R.string.wallet_node_add, (d, w) -> {
					String h = text(host);
					String pt = text(port);
					if (h.isEmpty() || pt.isEmpty()) {
						toast(getString(R.string.wallet_node_invalid));
						return;
					}
					String node = h + ":" + pt;
					viewModel.addNode(node);
					viewModel.selectNode(node);
					if (isTlsNode(node)) {
						viewModel.captureNodePin(node);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private boolean isTlsNode(String node) {
		try {
			int i = node.lastIndexOf(':');
			String h = i <= 0 ? node : node.substring(0, i);
			int pt = Integer.parseInt(node.substring(i + 1).trim());
			return com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
					.fromUserInput(h, pt, null).tls();
		} catch (Exception e) {
			return false;
		}
	}

	private String nodeModeLabel(String node) {
		try {
			int i = node.lastIndexOf(':');
			String h = i <= 0 ? node : node.substring(0, i);
			int pt = Integer.parseInt(node.substring(i + 1).trim());
			com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint ep =
					com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
							.fromUserInput(h, pt, null);
			if (ep.isOnion()) {
				return getString(R.string.wallet_node_mode_onion);
			}
			if (ep.local) {
				return getString(R.string.wallet_node_mode_local);
			}
			return ep.tls() ? getString(R.string.wallet_node_mode_tls)
					: getString(R.string.wallet_node_mode_plaintext);
		} catch (Exception e) {
			return "";
		}
	}

	private static String groupFingerprint(String fp) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < fp.length(); i += 4) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(fp.substring(i, Math.min(i + 4, fp.length())));
		}
		return sb.toString().toUpperCase();
	}

	private void showPinConfirmDialog(String node, String fp) {
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_pin_confirm_title)
				.setMessage(getString(R.string.wallet_pin_confirm_msg, node,
						groupFingerprint(fp)))
				.setPositiveButton(R.string.wallet_pin_confirm_button,
						(d, w) -> {
							viewModel.confirmNodePin(node, fp);
							toast(getString(R.string.wallet_pin_saved));
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showRenameDialog() {
		String id = openWalletId;
		if (id == null) {
			return;
		}
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);
		TextInputEditText name = field(ctx, box, R.string.wallet_rename_hint,
				InputType.TYPE_CLASS_TEXT);
		TextInputEditText pw = field(ctx, box, R.string.wallet_password_prompt,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_rename_title)
				.setView(box)
				.setPositiveButton(R.string.wallet_settings_rename, (d, w) -> {
					String n = text(name);
					char[] c = chars(pw);
					if (n.isEmpty() || c.length == 0) {
						return;
					}
					viewModel.renameBtcWallet(id, n, c);
					backToList();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showChangePasswordDialog() {
		String id = openWalletId;
		if (id == null) {
			return;
		}
		String name = headerTitle.getText().toString();
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);
		TextInputEditText oldPw = field(ctx, box, R.string.wallet_change_pw_old,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		TextInputEditText newPw = field(ctx, box, R.string.wallet_change_pw_new,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		TextInputEditText confirm = field(ctx, box,
				R.string.wallet_password_confirm_hint,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		androidx.appcompat.app.AlertDialog dlg =
				new MaterialAlertDialogBuilder(ctx)
						.setTitle(R.string.wallet_change_pw_title)
						.setView(box)
						.setPositiveButton(R.string.wallet_settings_change_pw,
								null)
						.setNegativeButton(android.R.string.cancel, null)
						.create();
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
					char[] o = chars(oldPw);
					char[] nw = chars(newPw);
					if (o.length == 0 || nw.length < 6) {
						toast(getString(R.string.wallet_auth_too_short));
						return;
					}
					if (!java.util.Arrays.equals(nw, chars(confirm))) {
						toast(getString(R.string.wallet_password_mismatch));
						return;
					}
					viewModel.changeBtcWalletPassword(id, name, o, nw);
					dlg.dismiss();
					backToList();
				}));
		track(dlg);
		dlg.show();
	}

	private void showCurrencyPicker() {
		String[] currencies = com.professor.zerion.android.vault.wallet.btc
				.BtcPrice.CURRENCIES;
		int checked = -1;
		for (int i = 0; i < currencies.length; i++) {
			if (currencies[i].equals(fiatCurrency)) {
				checked = i;
				break;
			}
		}
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_settings_currency)
				.setSingleChoiceItems(currencies, checked, (d, which) -> {
					String cur = currencies[which];
					fiatCurrency = cur;
					if (!"BTC".equals(displayUnit)) {
						displayUnit = cur;
					}
					viewModel.saveWalletCurrency(cur);
					renderBalance();
					d.dismiss();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showSilentPaymentsDialog() {
		viewModel.loadSp();
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);

		android.widget.Switch spSwitch = new android.widget.Switch(ctx);
		spSwitch.setText(R.string.wallet_sp_enable);
		spSwitch.setChecked(spEnabled);
		spSwitch.setPadding(0, dp(4), 0, dp(4));
		spSwitch.setOnCheckedChangeListener(
				(b, checked) -> viewModel.setSpEnabled(checked));
		box.addView(spSwitch);

		TextView intro = new TextView(ctx);
		intro.setText(spEnabled ? R.string.wallet_sp_intro
				: R.string.wallet_sp_disabled_hint);
		intro.setTextColor(colorRes(R.color.zerion_text_secondary));
		intro.setTextSize(13);
		box.addView(intro);

		TextView addrView = new TextView(ctx);
		addrView.setText(spAddress == null ? "…" : spAddress);
		addrView.setTextIsSelectable(true);
		addrView.setPadding(0, dp(14), 0, dp(6));
		addrView.setTextColor(colorRes(R.color.zerion_text_primary));
		addrView.setTypeface(android.graphics.Typeface.MONOSPACE);
		addrView.setTextSize(12);
		addrView.setOnClickListener(v -> {
			if (spAddress != null) {
				ClipboardManager cm = (ClipboardManager) ctx
						.getSystemService(Context.CLIPBOARD_SERVICE);
				if (cm != null) {
					cm.setPrimaryClip(ClipData.newPlainText("sp", spAddress));
					toast(getString(R.string.wallet_address_copied));
				}
			}
		});
		box.addView(addrView);

		long bal = spInfo != null ? spInfo[0] : 0;
		int scanned = spInfo != null ? (int) spInfo[2] : 0;
		TextView status = new TextView(ctx);
		String s = getString(R.string.wallet_sp_balance, formatBtc(bal));
		if (scanned > 0) {
			s = s + "\n" + getString(R.string.wallet_sp_scanned, scanned);
		}
		status.setText(s);
		status.setTextColor(colorRes(R.color.zerion_text_secondary));
		status.setPadding(0, dp(10), 0, 0);
		box.addView(status);

		if (bal > 0) {
			TextView move = new TextView(ctx);
			move.setText(R.string.wallet_sp_move);
			move.setTextColor(colorRes(R.color.zerion_primary_accent));
			move.setTypeface(move.getTypeface(),
					android.graphics.Typeface.BOLD);
			move.setTextSize(14);
			move.setPadding(0, dp(14), 0, 0);
			move.setOnClickListener(v -> showSpMoveDialog());
			box.addView(move);
		}

		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_settings_sp)
				.setView(box)
				.setPositiveButton(R.string.wallet_sp_scan, (d, w) -> {
					if (!spEnabled) {
						toast(getString(R.string.wallet_sp_enable_first));
						return;
					}
					viewModel.scanSp();
					toast(getString(R.string.wallet_sp_scanning));
				})
				.setNeutralButton(R.string.wallet_sp_set_oracle,
						(d, w) -> showSpOracleDialog())
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showSpOracleDialog() {
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);
		TextInputEditText oracle = field(ctx, box, R.string.wallet_sp_oracle_hint,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		oracle.setText(VaultViewModel.DEFAULT_SP_ORACLE);
		TextInputEditText birthday = field(ctx, box,
				R.string.wallet_sp_birthday_hint, InputType.TYPE_CLASS_NUMBER);
		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_sp_set_oracle)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					String url = text(oracle);
					int b = 0;
					try {
						b = Integer.parseInt(text(birthday));
					} catch (Exception ignored) {
					}
					viewModel.saveSpConfig(url, b);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showSpMoveDialog() {
		Context ctx = requireContext();
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);
		TextInputEditText addr = field(ctx, box, R.string.wallet_sp_move_hint,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		TextInputEditText pin = field(ctx, box, R.string.wallet_auth_send_hint,
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		track(new MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_sp_move)
				.setView(box)
				.setPositiveButton(R.string.wallet_auth_send_button, (d, w) -> {
					String to = text(addr);
					if (!com.professor.zerion.android.vault.wallet.btc.BtcKeys
							.isValidAddress(to)) {
						toast(getString(R.string.wallet_send_bad_address));
						return;
					}
					String s = text(pin);
					if (s.isEmpty()) {
						return;
					}
					double rate = feeOptions != null
							&& feeChoice < feeOptions.length
							? feeOptions[feeChoice] : feeRate;
					viewModel.sweepSp(to, rate, s.toCharArray());
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
	}

	private void showDetail(boolean detail) {
		listScroll.setVisibility(detail ? View.GONE : View.VISIBLE);
		detailScroll.setVisibility(detail ? View.VISIBLE : View.GONE);
		fabAdd.setVisibility(detail ? View.GONE : View.VISIBLE);
		if (settingsGear != null) {
			settingsGear.setVisibility(detail ? View.VISIBLE : View.GONE);
		}
		if (!detail) {
			headerTitle.setText(R.string.wallet_card_title);
		}
	}

	private void backToList() {
		stopPolling();
		viewModel.closeBtcWallet();
		openWalletId = null;
		receiveAddress = null;
		feeOptions = null;
		feeRequested = false;
		showDetail(false);
		viewModel.loadWallets();
	}

	private void startPolling() {
		stopPolling();
		pollRunnable = new Runnable() {
			@Override
			public void run() {
				if (openWalletId != null) {
					viewModel.pollBtcWallet();
					pollHandler.postDelayed(this, POLL_INTERVAL_MS);
				}
			}
		};
		pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
	}

	private void stopPolling() {
		if (pollRunnable != null) {
			pollHandler.removeCallbacks(pollRunnable);
			pollRunnable = null;
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		viewModel.cancelSend();
		viewModel.onPayjoinInterrupted();
		dismissPayjoinProgress();
	}

	@Override
	public void onStart() {
		super.onStart();
		if (!viewModel.walletSessionValid()) {
			viewModel.resetWalletSession();
			if (isAdded()) {
				showNextFragment(VaultDashboardFragment.newInstance());
			}
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		viewModel.cancelSend();
		viewModel.onPayjoinInterrupted();
		dismissPayjoinProgress();
		dismissPreparing();
		dismissUnlocking();
		dismissTrackedDialogs();
		pendingOpen = null;
		stopPolling();
		viewModel.closeBtcWallet();
	}

	private TextInputEditText field(Context ctx, LinearLayout box, int hint,
			int inputType) {
		TextInputLayout til = new TextInputLayout(ctx);
		TextInputEditText e = new TextInputEditText(til.getContext());
		e.setHint(hint);
		e.setInputType(inputType);
		til.addView(e);
		box.addView(til);
		return e;
	}

	private static String text(TextInputEditText e) {
		return e.getText() == null ? "" : e.getText().toString().trim();
	}

	private static char[] chars(TextInputEditText e) {
		android.text.Editable ed = e.getText();
		int n = ed == null ? 0 : ed.length();
		char[] out = new char[n];
		if (n > 0) {
			ed.getChars(0, n, out, 0);
		}
		if (ed != null) {
			ed.clear();
		}
		return out;
	}

	private void renderBalance() {
		Long sat = viewModel.getBtcBalanceSat().getValue();
		long s = sat == null ? -1L : sat;
		balanceValue.setText(sat == null ? "" : formatBtc(sat));
		balanceFiat.setText(com.professor.zerion.android.vault.wallet.btc
				.FiatDisplay.line(s, fiatCurrency, symbolFor(fiatCurrency),
						rates));
		updateSyncStatus();
	}

	private void updateSyncStatus() {
		if (syncStatus == null) {
			return;
		}
		boolean synced = !busyNow
				&& viewModel.getBtcBalanceSat().getValue() != null;
		syncStatus.setVisibility(synced ? View.VISIBLE : View.GONE);
	}

	private void cycleDisplayUnit() {
		if (rates == null) {
			return;
		}
		java.util.List<String> fiats = new java.util.ArrayList<>();
		for (String c : com.professor.zerion.android.vault.wallet.btc
				.BtcPrice.CURRENCIES) {
			if (rates.has(c)) {
				fiats.add(c);
			}
		}
		if (fiats.isEmpty()) {
			return;
		}
		int idx = fiats.indexOf(fiatCurrency);
		fiatCurrency = fiats.get((idx < 0 ? 0 : (idx + 1) % fiats.size()));
		displayUnit = "BTC";
		viewModel.saveWalletCurrency(fiatCurrency);
		renderBalance();
	}

	private double fiatPrice() {
		return rates == null ? 0 : rates.get(fiatCurrency);
	}

	private String fiatSymbol() {
		return symbolFor(fiatCurrency);
	}

	private String symbolFor(String cur) {
		switch (cur) {
			case "USD":
				return "$";
			case "GBP":
				return "£";
			case "JPY":
				return "¥";
			case "CAD":
				return "CA$";
			case "AUD":
				return "A$";
			case "CHF":
				return "CHF ";
			default:
				return "€";
		}
	}

	private String formatBtc(long sat) {
		if (sat == 0) {
			return "0 BTC";
		}
		BigDecimal btc = BigDecimal.valueOf(sat).movePointLeft(8)
				.stripTrailingZeros();
		return btc.toPlainString() + " BTC";
	}

	private static String shorten(String s) {
		if (s.length() <= 16) {
			return s;
		}
		return s.substring(0, 10) + "…" + s.substring(s.length() - 6);
	}

	private void toast(String msg) {
		Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
	}

	private int dp(int value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
	}

	private int colorRes(int res) {
		return androidx.core.content.ContextCompat.getColor(requireContext(), res);
	}

	private int colorAttr(int attr) {
		TypedValue tv = new TypedValue();
		requireContext().getTheme().resolveAttribute(attr, tv, true);
		if (tv.resourceId != 0) {
			return androidx.core.content.ContextCompat.getColor(
					requireContext(), tv.resourceId);
		}
		return tv.data;
	}
}
