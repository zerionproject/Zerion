package com.professor.zerion.android.sticker;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.concurrent.Executor;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class StickerPickerDialog extends BottomSheetDialogFragment {

	public interface StickerPickerListener {

		void onStickerEmojiPicked(String emoji);

		void onCustomStickerPicked(byte[] pngBytes);
	}

	@Nullable
	private Executor ioExecutor;
	@Nullable
	private StickerPickerListener listener;
	@Nullable
	private StickerStorage storage;
	@Nullable
	private StickerImporter importer;
	@Nullable
	private MyStickersAdapter myAdapter;
	@Nullable
	private RecyclerView recycler;
	@Nullable
	private TextView tabStandard;
	@Nullable
	private TextView tabMine;

	private boolean myStickersTabActive = false;

	private final ActivityResultLauncher<PickVisualMediaRequest> imagePicker =
			registerForActivityResult(
					new ActivityResultContracts.PickVisualMedia(),
					this::onPickResult);

	public static StickerPickerDialog newInstance() {
		return new StickerPickerDialog();
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof StickerPickerListener) {
			listener = (StickerPickerListener) context;
		} else {
			throw new RuntimeException(context
					+ " must implement StickerPickerDialog.StickerPickerListener");
		}

		ZerionApplication app =
				(ZerionApplication) context.getApplicationContext();
		ioExecutor = app.getApplicationComponent().ioExecutor();
		storage = new StickerStorage(context.getApplicationContext());
		importer = new StickerImporter(storage);
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(
				savedInstanceState);
		View view = LayoutInflater.from(getContext())
				.inflate(R.layout.dialog_sticker_picker, null);
		dialog.setContentView(view);

		recycler = view.findViewById(R.id.stickerList);
		tabStandard = view.findViewById(R.id.tabStandard);
		tabMine = view.findViewById(R.id.tabMine);
		ImageButton importBtn = view.findViewById(R.id.importStickerButton);

		recycler.setLayoutManager(new GridLayoutManager(getContext(), 6));
		StandardStickerAdapter standardAdapter = new StandardStickerAdapter(
				emoji -> {
					if (listener != null) listener.onStickerEmojiPicked(emoji);
					dismiss();
				});
		myAdapter = new MyStickersAdapter(storage, ioExecutor,
				new MyStickersAdapter.Listener() {
					@Override
					public void onStickerPicked(String id, byte[] pngBytes) {
						if (listener != null) {
							listener.onCustomStickerPicked(pngBytes);
						}
						dismiss();
					}

					@Override
					public void onStickerDeleteRequested(String id) {
						confirmDelete(id);
					}
				});

		tabStandard.setOnClickListener(v -> {
			myStickersTabActive = false;
			recycler.setLayoutManager(
					new GridLayoutManager(getContext(), 6));
			recycler.setAdapter(standardAdapter);
			refreshTabStyles();
			importBtn.setVisibility(View.GONE);
		});
		tabMine.setOnClickListener(v -> {
			myStickersTabActive = true;
			recycler.setLayoutManager(
					new GridLayoutManager(getContext(), 4));
			myAdapter.reload();
			recycler.setAdapter(myAdapter);
			refreshTabStyles();
			importBtn.setVisibility(View.VISIBLE);
		});

		importBtn.setOnClickListener(v -> imagePicker.launch(
				new PickVisualMediaRequest.Builder()
						.setMediaType(ActivityResultContracts.PickVisualMedia
								.ImageOnly.INSTANCE)
						.build()));

		recycler.setAdapter(standardAdapter);
		refreshTabStyles();

		return dialog;
	}

	private void refreshTabStyles() {
		if (tabStandard == null || tabMine == null) return;
		tabStandard.setAlpha(myStickersTabActive ? 0.5f : 1f);
		tabMine.setAlpha(myStickersTabActive ? 1f : 0.5f);
	}

	private void onPickResult(@Nullable Uri uri) {
		if (uri == null || importer == null || myAdapter == null) return;
		Activity act = getActivity();
		if (act == null) return;
		ioExecutor.execute(() -> {
			try {
				importer.importFromUri(act.getContentResolver(), uri);
				act.runOnUiThread(() -> {
					if (myAdapter != null) myAdapter.reload();
				});
			} catch (Exception e) {
				act.runOnUiThread(() ->
						Toast.makeText(act,
								R.string.sticker_import_failed,
								Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void confirmDelete(String id) {
		Context ctx = getContext();
		if (ctx == null || storage == null || myAdapter == null) return;
		new MaterialAlertDialogBuilder(ctx)
				.setMessage(R.string.sticker_delete_confirm)
				.setPositiveButton(R.string.delete, (d, w) -> {
					ioExecutor.execute(() -> {
						storage.delete(id);
						Activity a = getActivity();
						if (a != null) {
							a.runOnUiThread(() -> {
								if (myAdapter != null) myAdapter.reload();
							});
						}
					});
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@Override
	public void onDetach() {
		super.onDetach();
		listener = null;
	}
}
