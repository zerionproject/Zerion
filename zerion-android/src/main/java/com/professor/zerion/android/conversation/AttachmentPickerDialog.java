package com.professor.zerion.android.conversation;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.professor.zerion.R;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AttachmentPickerDialog extends BottomSheetDialogFragment {

	public interface AttachmentPickerListener {
		void onCameraSelected();
		void onPhoneGallerySelected();
		void onVaultGallerySelected();
		void onPhoneDocumentsSelected();
		void onVaultDocumentsSelected();
	}

	private AttachmentPickerListener listener;

	public static AttachmentPickerDialog newInstance() {
		return new AttachmentPickerDialog();
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof AttachmentPickerListener) {
			listener = (AttachmentPickerListener) context;
		} else {
			throw new RuntimeException(context.toString()
					+ " must implement AttachmentPickerListener");
		}
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);

		View view = LayoutInflater.from(getContext())
				.inflate(R.layout.dialog_attachment_picker, null);
		dialog.setContentView(view);

		LinearLayout camera = view.findViewById(R.id.attachment_camera);
		camera.setOnClickListener(v -> {
			if (listener != null) {
				listener.onCameraSelected();
			}
			dismiss();
		});

		LinearLayout phoneGallery = view.findViewById(R.id.attachment_phone_gallery);
		phoneGallery.setOnClickListener(v -> {
			if (listener != null) {
				listener.onPhoneGallerySelected();
			}
			dismiss();
		});

		LinearLayout vaultGallery = view.findViewById(R.id.attachment_vault_gallery);
		vaultGallery.setOnClickListener(v -> {
			if (listener != null) {
				listener.onVaultGallerySelected();
			}
			dismiss();
		});

		LinearLayout phoneDocuments = view.findViewById(R.id.attachment_phone_documents);
		phoneDocuments.setOnClickListener(v -> {
			if (listener != null) {
				listener.onPhoneDocumentsSelected();
			}
			dismiss();
		});

		LinearLayout vaultDocuments = view.findViewById(R.id.attachment_vault_documents);
		vaultDocuments.setOnClickListener(v -> {
			if (listener != null) {
				listener.onVaultDocumentsSelected();
			}
			dismiss();
		});

		return dialog;
	}

	@Override
	public void onDetach() {
		super.onDetach();
		listener = null;
	}
}
