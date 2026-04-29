package com.professor.zerion.android.vault.ui;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.utils.MimeUtils;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultDocumentViewerFragment extends BaseFragment {

	private static final String ARG_ITEM_ID = "item_id";
	private static final String ARG_ITEM_NAME = "item_name";

	private static final long MAX_DOCUMENT_SIZE = 15 * 1024 * 1024;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;

	private String itemId;
	private String itemName;

	private MaterialToolbar toolbar;
	private FrameLayout loadingOverlay;

	private NestedScrollView pdfScrollView;
	private ImageView pdfPageView;
	private LinearLayout pdfControls;
	private Button pdfPrevButton;
	private Button pdfNextButton;
	private TextView pdfPageIndicator;

	private NestedScrollView textScrollView;
	private TextView textContentView;

	private WebView markdownWebView;

	private LinearLayout unsupportedLayout;
	private TextView unsupportedTitle;
	private TextView unsupportedMessage;

	private PdfRenderer pdfRenderer;
	private ParcelFileDescriptor pdfFileDescriptor;
	private byte[] pdfBytes;
	private int currentPdfPage = 0;
	private Bitmap currentPageBitmap;

	private byte[] documentBytes;

	public static VaultDocumentViewerFragment newInstance(String itemId, String itemName) {
		VaultDocumentViewerFragment fragment = new VaultDocumentViewerFragment();
		Bundle args = new Bundle();
		args.putString(ARG_ITEM_ID, itemId);
		args.putString(ARG_ITEM_NAME, itemName);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (getArguments() != null) {
			itemId = getArguments().getString(ARG_ITEM_ID);
			itemName = getArguments().getString(ARG_ITEM_NAME);
		}
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_vault_document_viewer, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		initializeViews(view);
		setupToolbar();
		loadDocument();
	}

	private void initializeViews(View view) {
		toolbar = view.findViewById(R.id.toolbar);
		loadingOverlay = view.findViewById(R.id.loading_overlay);

		pdfScrollView = view.findViewById(R.id.pdf_scroll_view);
		pdfPageView = view.findViewById(R.id.pdf_page_view);
		pdfControls = view.findViewById(R.id.pdf_controls);
		pdfPrevButton = view.findViewById(R.id.pdf_prev_button);
		pdfNextButton = view.findViewById(R.id.pdf_next_button);
		pdfPageIndicator = view.findViewById(R.id.pdf_page_indicator);

		textScrollView = view.findViewById(R.id.text_scroll_view);
		textContentView = view.findViewById(R.id.text_content);

		markdownWebView = view.findViewById(R.id.markdown_webview);

		unsupportedLayout = view.findViewById(R.id.unsupported_layout);
		unsupportedTitle = view.findViewById(R.id.unsupported_title);
		unsupportedMessage = view.findViewById(R.id.unsupported_message);

		pdfPrevButton.setOnClickListener(v -> navigatePdfPage(-1));
		pdfNextButton.setOnClickListener(v -> navigatePdfPage(1));
	}

	private void setupToolbar() {
		toolbar.setTitle(itemName != null ? itemName : "Document Viewer");
		toolbar.setNavigationOnClickListener(v -> {
			if (getActivity() != null) {
				getActivity().getOnBackPressedDispatcher().onBackPressed();
			}
		});

		toolbar.inflateMenu(R.menu.document_viewer_menu);
		toolbar.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == R.id.action_edit) {
				editDocument();
				return true;
			}
			return false;
		});
	}

	private void editDocument() {
		if (documentBytes == null) {
			Toast.makeText(requireContext(), "Cannot edit this document", Toast.LENGTH_SHORT).show();
			return;
		}

		MimeUtils.MimeType detectedType = MimeUtils.detectMimeType(documentBytes, itemName);
		if (detectedType != MimeUtils.MimeType.TEXT && detectedType != MimeUtils.MimeType.MARKDOWN) {
			Toast.makeText(requireContext(), "Only text documents can be edited", Toast.LENGTH_SHORT).show();
			return;
		}

		String content = new String(documentBytes, StandardCharsets.UTF_8);

		if (listener != null) {
			TextEditorFragment editorFragment = TextEditorFragment.newInstanceForEdit(itemId, itemName, content);
			listener.showNextFragment(editorFragment);
		}
	}

	private void loadDocument() {
		showLoading(true);

		checkPasswordProtection();
	}

	private void checkPasswordProtection() {
		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) {
				for (com.professor.zerion.android.vault.model.VaultItem item : items) {
					if (item.id.equals(itemId)) {
						if (item.hasExtraPassword) {
							showPasswordDialog();
						} else {
							loadDocumentWithoutPassword();
						}
						return;
					}
				}
				showLoading(false);
				showError("Document Not Found", "The document could not be found in the vault.");
			}
		});
	}

	private void showPasswordDialog() {
		showLoading(false);

		DocumentPasswordDialog dialog = DocumentPasswordDialog.newUnlockDialog(
				"Password Required",
				"This document is protected with an additional password. Please enter it to view."
		);

		dialog.setCallback(new DocumentPasswordDialog.PasswordCallback() {
			@Override
			public void onPasswordEntered(@Nullable char[] password) {
				if (password != null) {
					loadDocumentWithPassword(password);
				} else {
					showError("Password Required", "This document requires a password to view.");
				}
			}

			@Override
			public void onPasswordCancelled() {
				if (getActivity() != null) {
					getActivity().getOnBackPressedDispatcher().onBackPressed();
				}
			}
		});

		dialog.show(getParentFragmentManager(), "password_unlock");
	}

	private void loadDocumentWithPassword(char[] password) {
		showLoading(true);

		viewModel.loadDocumentWithPassword(itemId, password, new VaultViewModel.DocumentCallback() {
			@Override
			public void onLoaded(byte[] content, String mimeType) {
				documentBytes = content;

				MimeUtils.MimeType detectedType = MimeUtils.detectMimeType(content, itemName);

				showLoading(false);

				if (content.length > MAX_DOCUMENT_SIZE) {
					showError("Document Too Large",
							"This document is too large to safely open in memory inside the secure vault.\n\n" +
							"Size: " + formatSize(content.length) + "\n" +
							"Limit: " + formatSize(MAX_DOCUMENT_SIZE));
					return;
				}

				if (detectedType.canViewSecurely) {
					renderDocument(content, detectedType);
				} else {
					showUnsupportedFormat(detectedType);
				}
			}

			@Override
			public void onError(String error) {
				showLoading(false);
				if (error.contains("Incorrect password")) {
					Toast.makeText(requireContext(), "Incorrect password", Toast.LENGTH_SHORT).show();
					showPasswordDialog();
				} else {
					showError("Failed to Load Document", error);
				}
			}
		});
	}

	private void loadDocumentWithoutPassword() {
		viewModel.loadDocumentSecure(itemId, new VaultViewModel.DocumentCallback() {
			@Override
			public void onLoaded(byte[] content, String mimeType) {
				documentBytes = content;

				MimeUtils.MimeType detectedType = MimeUtils.detectMimeType(content, itemName);

				showLoading(false);

				if (content.length > MAX_DOCUMENT_SIZE) {
					showError("Document Too Large",
							"This document is too large to safely open in memory inside the secure vault.\n\n" +
							"Size: " + formatSize(content.length) + "\n" +
							"Limit: " + formatSize(MAX_DOCUMENT_SIZE));
					return;
				}

				if (detectedType.canViewSecurely) {
					renderDocument(content, detectedType);
				} else {
					showUnsupportedFormat(detectedType);
				}
			}

			@Override
			public void onError(String error) {
				showLoading(false);
				showError("Failed to Load Document", error);
			}
		});
	}

	private void renderDocument(byte[] content, MimeUtils.MimeType mimeType) {
		switch (mimeType) {
			case PDF:
				renderPdf(content);
				break;

			case TEXT:
				renderText(content);
				break;

			case MARKDOWN:
				renderMarkdown(content);
				break;

			case IMAGE_PNG:
			case IMAGE_JPEG:
			case IMAGE_GIF:
			case IMAGE_WEBP:
				Toast.makeText(requireContext(),
						"Image files should be viewed in the Gallery tab",
						Toast.LENGTH_SHORT).show();
				if (getActivity() != null) {
					getActivity().getOnBackPressedDispatcher().onBackPressed();
				}
				break;

			default:
				showUnsupportedFormat(mimeType);
				break;
		}
	}

	private void renderPdf(byte[] pdfContent) {
		try {
			pdfBytes = pdfContent;

			ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
			ParcelFileDescriptor readFd = pipe[0];
			ParcelFileDescriptor writeFd = pipe[1];

			new Thread(() -> {
				try {
					java.io.OutputStream outputStream = new ParcelFileDescriptor.AutoCloseOutputStream(writeFd);
					outputStream.write(pdfContent);
					outputStream.close();
				} catch (Exception e) {
					try {
						writeFd.close();
					} catch (Exception ignored) {
					}
				}
			}).start();

			pdfFileDescriptor = readFd;
			pdfRenderer = new PdfRenderer(pdfFileDescriptor);

			pdfScrollView.setVisibility(View.VISIBLE);
			textScrollView.setVisibility(View.GONE);
			markdownWebView.setVisibility(View.GONE);
			unsupportedLayout.setVisibility(View.GONE);

			currentPdfPage = 0;
			renderPdfPage(currentPdfPage);

		} catch (Exception e) {
			showError("PDF Rendering Failed",
					"Cannot render this PDF securely");
		}
	}

	private void renderPdfPage(int pageIndex) {
		if (pdfRenderer == null || pageIndex < 0 || pageIndex >= pdfRenderer.getPageCount()) {
			return;
		}

		try {
			if (currentPageBitmap != null && !currentPageBitmap.isRecycled()) {
				currentPageBitmap.recycle();
				currentPageBitmap = null;
			}

			PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);

			int width = page.getWidth() * 2;
			int height = page.getHeight() * 2;

			currentPageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

			page.render(currentPageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

			pdfPageView.setImageBitmap(currentPageBitmap);

			pdfPageIndicator.setText(getString(R.string.vault_doc_page_indicator,
					pageIndex + 1, pdfRenderer.getPageCount()));
			pdfPrevButton.setEnabled(pageIndex > 0);
			pdfNextButton.setEnabled(pageIndex < pdfRenderer.getPageCount() - 1);

			page.close();

			currentPdfPage = pageIndex;

		} catch (Exception e) {
			Toast.makeText(requireContext(), "Failed to render page", Toast.LENGTH_SHORT).show();
		}
	}

	private void navigatePdfPage(int delta) {
		int newPage = currentPdfPage + delta;
		if (pdfRenderer != null && newPage >= 0 && newPage < pdfRenderer.getPageCount()) {
			renderPdfPage(newPage);
		}
	}

	private void renderText(byte[] textContent) {
		try {
			String text = new String(textContent, StandardCharsets.UTF_8);

			textScrollView.setVisibility(View.VISIBLE);
			pdfScrollView.setVisibility(View.GONE);
			markdownWebView.setVisibility(View.GONE);
			unsupportedLayout.setVisibility(View.GONE);

			textContentView.setText(text);

		} catch (Exception e) {
			showError("Text Rendering Failed", "Cannot decode text: " + e.getMessage());
		}
	}

	private void renderMarkdown(byte[] markdownContent) {
		try {
			String markdown = new String(markdownContent, StandardCharsets.UTF_8);

			String html = convertMarkdownToHtml(markdown);

			markdownWebView.setVisibility(View.VISIBLE);
			pdfScrollView.setVisibility(View.GONE);
			textScrollView.setVisibility(View.GONE);
			unsupportedLayout.setVisibility(View.GONE);

			markdownWebView.getSettings().setJavaScriptEnabled(false);
			markdownWebView.getSettings().setAllowFileAccess(false);
			markdownWebView.getSettings().setAllowContentAccess(false);

			markdownWebView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

		} catch (Exception e) {
			showError("Markdown Rendering Failed", "Cannot render markdown: " + e.getMessage());
		}
	}

	private String convertMarkdownToHtml(String markdown) {
		String html = markdown
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");

		html = html.replaceAll("(?m)^# (.+)$", "<h1>$1</h1>");
		html = html.replaceAll("(?m)^## (.+)$", "<h2>$1</h2>");
		html = html.replaceAll("(?m)^### (.+)$", "<h3>$1</h3>");
		html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
		html = html.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
		html = html.replaceAll("```([\\s\\S]*?)```", "<pre><code>$1</code></pre>");
		html = html.replaceAll("`(.+?)`", "<code>$1</code>");
		html = html.replace("\n\n", "</p><p>");
		html = html.replace("\n", "<br>");

		return "<!DOCTYPE html>" +
				"<html>" +
				"<head>" +
				"<meta charset=\"utf-8\">" +
				"<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
				"<style>" +
				"body { font-family: sans-serif; padding: 16px; line-height: 1.6; }" +
				"h1, h2, h3 { color: #333; }" +
				"code { background: #f4f4f4; padding: 2px 6px; border-radius: 3px; }" +
				"pre { background: #f4f4f4; padding: 12px; border-radius: 6px; overflow-x: auto; }" +
				"</style>" +
				"</head>" +
				"<body>" +
				"<p>" + html + "</p>" +
				"</body>" +
				"</html>";
	}

	private void showUnsupportedFormat(MimeUtils.MimeType mimeType) {
		pdfScrollView.setVisibility(View.GONE);
		textScrollView.setVisibility(View.GONE);
		markdownWebView.setVisibility(View.GONE);

		unsupportedLayout.setVisibility(View.VISIBLE);

		String message;
		if (mimeType == MimeUtils.MimeType.OFFICE_DOCX ||
				mimeType == MimeUtils.MimeType.OFFICE_DOC ||
				mimeType == MimeUtils.MimeType.OFFICE_XLSX ||
				mimeType == MimeUtils.MimeType.OFFICE_XLS ||
				mimeType == MimeUtils.MimeType.OFFICE_PPTX ||
				mimeType == MimeUtils.MimeType.OFFICE_PPT) {
			message = "Office documents cannot be viewed inside the secure vault.\n\n" +
					"Opening these files requires external apps and temp files, " +
					"which would compromise vault security.\n\n" +
					"You may only store them, not open them.";
		} else if (mimeType == MimeUtils.MimeType.ARCHIVE_ZIP ||
				mimeType == MimeUtils.MimeType.ARCHIVE_RAR ||
				mimeType == MimeUtils.MimeType.ARCHIVE_7Z) {
			message = "Archive files cannot be extracted inside the secure vault.\n\n" +
					"Extraction would create plaintext files on disk, " +
					"compromising vault security.\n\n" +
					"You may only store them, not extract them.";
		} else {
			message = "This document type cannot be viewed inside the secure vault.\n\n" +
					"File type: " + mimeType.displayName + "\n\n" +
					"You may only store it, not open it.";
		}

		unsupportedMessage.setText(message);
	}

	private void showError(String title, String message) {
		unsupportedLayout.setVisibility(View.VISIBLE);
		pdfScrollView.setVisibility(View.GONE);
		textScrollView.setVisibility(View.GONE);
		markdownWebView.setVisibility(View.GONE);

		unsupportedTitle.setText(title);
		unsupportedMessage.setText(message);
	}

	private void showLoading(boolean show) {
		loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
	}

	private String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
		return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();

		cleanupSecurely();
	}

	private void cleanupSecurely() {
		if (documentBytes != null) {
			SecureMemory.shred(documentBytes);
			documentBytes = null;
		}

		if (pdfBytes != null) {
			SecureMemory.shred(pdfBytes);
			pdfBytes = null;
		}

		if (currentPageBitmap != null && !currentPageBitmap.isRecycled()) {
			currentPageBitmap.recycle();
			currentPageBitmap = null;
		}

		if (pdfRenderer != null) {
			try {
				pdfRenderer.close();
			} catch (Exception ignored) {
			}
			pdfRenderer = null;
		}

		if (pdfFileDescriptor != null) {
			try {
				pdfFileDescriptor.close();
			} catch (Exception ignored) {
			}
			pdfFileDescriptor = null;
		}

		if (textContentView != null) {
			textContentView.setText("");
		}

		if (markdownWebView != null) {
			markdownWebView.loadUrl("about:blank");
			markdownWebView.clearHistory();
			markdownWebView.clearCache(true);
		}

		System.gc();
	}

	@Override
	public String getUniqueTag() {
		return "VaultDocumentViewerFragment_" + itemId;
	}
}
