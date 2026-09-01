package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.xmr.XmrError;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.List;

import javax.inject.Inject;

/**
 * Monero wallet list, structured like the Bitcoin wallet list: a header, a card
 * row per wallet (coin identity from the persisted {@link WalletRecord#coin},
 * never the name), and a + that adds a Monero wallet. This screen shows Monero
 * wallets ONLY. Tapping a wallet always prompts for the wallet password and
 * verifies it before opening the (view-only) detail screen, and a long press
 * deletes the wallet after a confirmation and the same password, mirroring the
 * Bitcoin list. Per-wallet settings live inside the detail screen, not here.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class XmrWalletFragment extends BaseFragment {

	public static final String TAG = "XmrWalletFragment";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private XmrViewModel viewModel;
	private LinearLayout listContainer;
	private TextView emptyView;

	public static XmrWalletFragment newInstance() {
		return new XmrWalletFragment();
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
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(XmrViewModel.class);
		View v = inflater.inflate(R.layout.fragment_xmr_list, container, false);
		listContainer = v.findViewById(R.id.xmr_wallets_container);
		emptyView = v.findViewById(R.id.xmr_wallet_empty);
		v.findViewById(R.id.xmr_list_back).setOnClickListener(
				x -> showNextFragment(VaultDashboardFragment.newInstance()));
		v.findViewById(R.id.xmr_list_settings).setVisibility(View.GONE);
		v.findViewById(R.id.xmr_fab_add).setOnClickListener(x -> showAddSheet());

		observe();
		viewModel.loadWallets();
		return v;
	}

	private void observe() {
		viewModel.getWallets().observe(getViewLifecycleOwner(), this::render);
		viewModel.getError().observe(getViewLifecycleOwner(), ev -> {
			XmrError e = ev == null ? null : ev.getIfNotHandled();
			if (e != null) {
				dismissOpening();
				toast(messageFor(e));
			}
		});
		viewModel.getSessionOpened().observe(getViewLifecycleOwner(), ev -> {
			String id = ev == null ? null : ev.getIfNotHandled();
			if (id != null) {
				dismissOpening();
				openDetail(id);
			}
		});
		viewModel.getSeedReveal().observe(getViewLifecycleOwner(), ev -> {
			String id = ev == null ? null : ev.getIfNotHandled();
			if (id != null) {
				showNextFragment(XmrRecoveryPhraseFragment.newInstance(id,
						nameOf(id), true));
			}
		});
		viewModel.getWalletDeleted().observe(getViewLifecycleOwner(), ev -> {
			String id = ev == null ? null : ev.getIfNotHandled();
			if (id != null) {
				toast(getString(R.string.wallet_deleted));
				viewModel.loadWallets();
			}
		});
	}

	private String nameOf(String id) {
		List<WalletRecord> ws = viewModel.getWallets().getValue();
		if (ws != null) {
			for (WalletRecord w : ws) {
				if (w.id.equals(id)) return w.name;
			}
		}
		return "";
	}

	@Nullable
	private android.app.Dialog openingDialog;
	private final java.util.List<android.app.Dialog> trackedDialogs =
			new java.util.ArrayList<>();

	private void showOpening() {
		dismissOpening();
		android.widget.LinearLayout box =
				new android.widget.LinearLayout(requireContext());
		box.setOrientation(android.widget.LinearLayout.HORIZONTAL);
		box.setGravity(android.view.Gravity.CENTER_VERTICAL);
		int p = dp(24);
		box.setPadding(p, p, p, p);
		android.widget.ProgressBar bar =
				new android.widget.ProgressBar(requireContext());
		box.addView(bar);
		android.widget.TextView t =
				new android.widget.TextView(requireContext());
		t.setText(R.string.wallet_xmr_opening);
		t.setPadding(dp(16), 0, 0, 0);
		box.addView(t);
		openingDialog = new com.google.android.material.dialog
				.MaterialAlertDialogBuilder(requireContext())
				.setView(box)
				.setCancelable(false)
				.create();
		openingDialog.show();
	}

	private void dismissOpening() {
		if (openingDialog != null) {
			openingDialog.dismiss();
			openingDialog = null;
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		dismissOpening();
		dismissTrackedDialogs();
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

	private void openDetail(String id) {
		String name = id;
		List<WalletRecord> ws = viewModel.getWallets().getValue();
		if (ws != null) {
			for (WalletRecord w : ws) {
				if (w.id.equals(id)) {
					name = w.name;
					break;
				}
			}
		}
		showNextFragment(XmrWalletDetailFragment.newInstance(id, name));
	}

	private void render(@Nullable List<WalletRecord> wallets) {
		listContainer.removeAllViews();
		if (wallets == null || wallets.isEmpty()) {
			emptyView.setVisibility(View.VISIBLE);
			return;
		}
		emptyView.setVisibility(View.GONE);
		LayoutInflater inf = LayoutInflater.from(requireContext());
		for (WalletRecord w : wallets) {
			View row = inf.inflate(R.layout.item_vault_wallet, listContainer,
					false);
			((TextView) row.findViewById(R.id.wallet_row_name)).setText(w.name);
			((TextView) row.findViewById(R.id.wallet_row_coin))
					.setText(w.coin.getLabel());
			row.setOnClickListener(x -> showUnlockDialog(w));
			row.setOnLongClickListener(x -> {
				confirmDelete(w);
				return true;
			});
			listContainer.addView(row);
		}
	}

	private void showAddSheet() {
		String[] items = {
				getString(R.string.wallet_xmr_add_create),
				getString(R.string.wallet_xmr_add_import)
		};
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_xmr_add_title)
				.setItems(items, (d, which) -> {
					if (which == 0) showCreateDialog();
					else showImportDialog();
				})
				.create()).show();
	}

	private void showCreateDialog() {
		LinearLayout box = column();
		EditText name = field(box, getString(R.string.wallet_name_hint), false);
		EditText pw = field(box, getString(R.string.wallet_password_prompt), true);
		EditText confirm = field(box,
				getString(R.string.wallet_confirm_password), true);
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_xmr_add_create)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, wch) -> {
					char[] p1 = chars(pw);
					char[] p2 = chars(confirm);
					String nm = name.getText().toString().trim();
					if (nm.isEmpty() || p1.length == 0
							|| !java.util.Arrays.equals(p1, p2)) {
						toast(getString(R.string.wallet_xmr_create_invalid));
						wipe(p1);
						wipe(p2);
						return;
					}
					wipe(p2);
					viewModel.createWallet(nm, p1);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create()).show();
	}

	private void showImportDialog() {
		LinearLayout box = column();
		EditText name = field(box, getString(R.string.wallet_name_hint), false);
		EditText seed = field(box, getString(R.string.wallet_xmr_seed_hint),
				false);
		seed.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_FLAG_MULTI_LINE
				| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		seed.setMinLines(3);
		TextView heightNote = new TextView(requireContext());
		heightNote.setText(R.string.wallet_xmr_restore_height_note);
		heightNote.setTextSize(12);
		heightNote.setPadding(0, dp(8), 0, 0);
		box.addView(heightNote);
		EditText height = field(box,
				getString(R.string.wallet_xmr_restore_height_hint), false);
		height.setInputType(InputType.TYPE_CLASS_NUMBER);
		EditText pw = field(box, getString(R.string.wallet_password_prompt), true);
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_xmr_add_import)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, wch) -> {
					char[] sd = chars(seed);
					char[] p1 = chars(pw);
					String nm = name.getText().toString().trim();
					long h = 0;
					try {
						String hs = height.getText().toString().trim();
						if (!hs.isEmpty()) h = Long.parseLong(hs);
					} catch (NumberFormatException ignored) {
					}
					if (nm.isEmpty() || sd.length == 0 || p1.length == 0) {
						toast(getString(R.string.wallet_xmr_import_invalid));
						wipe(sd);
						wipe(p1);
						return;
					}
					viewModel.importWallet(nm, sd, h, p1);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create()).show();
	}

	private void showUnlockDialog(WalletRecord w) {
		LinearLayout box = column();
		EditText pw = field(box, getString(R.string.wallet_password_prompt), true);
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(w.name)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, wch) -> {
					char[] p1 = chars(pw);
					if (p1.length == 0) {
						wipe(p1);
						return;
					}
					showOpening();
					viewModel.openWallet(w.id, p1);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create()).show();
	}

	private void confirmDelete(WalletRecord w) {
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_delete)
				.setMessage(R.string.wallet_delete_warning_strong)
				.setPositiveButton(R.string.wallet_delete,
						(d, wch) -> promptDeleteAuth(w))
				.setNegativeButton(android.R.string.cancel, null)
				.create()).show();
	}

	private void promptDeleteAuth(WalletRecord w) {
		LinearLayout box = column();
		EditText pw = field(box, getString(R.string.wallet_password_prompt), true);
		track(new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wallet_delete)
				.setMessage(R.string.wallet_delete_auth_hint)
				.setView(box)
				.setPositiveButton(R.string.wallet_delete, (d, wch) -> {
					char[] p1 = chars(pw);
					if (p1.length == 0) {
						wipe(p1);
						return;
					}
					viewModel.deleteWallet(w.id, p1);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create()).show();
	}

	private void showNodeSettings() {
		XmrNodeSettings.choose(this, viewModel);
	}

	private LinearLayout column() {
		LinearLayout box = new LinearLayout(requireContext());
		box.setOrientation(LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);
		return box;
	}

	private EditText field(LinearLayout parent, String hint, boolean password) {
		TextInputLayout til = new TextInputLayout(parent.getContext());
		TextInputEditText et = new TextInputEditText(til.getContext());
		et.setHint(hint);
		et.setSaveEnabled(false);
		if (password) {
			et.setInputType(InputType.TYPE_CLASS_TEXT
					| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		}
		til.addView(et);
		parent.addView(til);
		return et;
	}

	private static char[] chars(EditText e) {
		Editable ed = e.getText();
		if (ed == null) return new char[0];
		char[] out = new char[ed.length()];
		ed.getChars(0, ed.length(), out, 0);
		e.setText("");
		return out;
	}

	private static void wipe(char[] c) {
		if (c != null) java.util.Arrays.fill(c, '\0');
	}

	private int dp(int v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}

	private void toast(String s) {
		if (isAdded()) {
			Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
		}
	}

	private String messageFor(XmrError e) {
		switch (e) {
			case WRONG_PASSWORD:
				return getString(R.string.wallet_wrong_password);
			case EMPTY_PASSWORD:
				return getString(R.string.wallet_xmr_create_invalid);
			case MALFORMED_SEED:
				return getString(R.string.wallet_xmr_import_invalid);
			case NATIVE_UNAVAILABLE:
				return getString(R.string.wallet_xmr_native_unavailable);
			case BUSY:
				return getString(R.string.wallet_xmr_busy);
			case WALLET_NEEDS_PASSWORD:
			case NATIVE_OPEN_FAILED:
			case CORRUPTED_ITEM:
			case SESSION_INVALIDATED:
				return getString(R.string.wallet_xmr_open_failed);
			default:
				return getString(R.string.wallet_xmr_node_invalid);
		}
	}
}
