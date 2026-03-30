package com.professor.zerion.android.conversation;

import android.content.ActivityNotFoundException;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.transition.Fade;
import android.transition.Transition;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.Window;
import android.widget.TextView;

import com.google.android.material.appbar.AppBarLayout;

import org.briarproject.bramble.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import com.professor.zerion.android.util.ZerionSnackbarBuilder;
import com.professor.zerion.android.view.PullDownLayout;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog.Builder;
import androidx.core.view.WindowCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import static android.view.View.GONE;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.View.VISIBLE;
import static com.google.android.material.snackbar.Snackbar.LENGTH_LONG;
import static java.util.Objects.requireNonNull;
import static com.professor.zerion.android.util.UiUtils.formatDateAbsolute;
import static com.professor.zerion.android.util.UiUtils.getDialogIcon;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ImageActivity extends ZerionActivity
		implements PullDownLayout.Callback, OnGlobalLayoutListener {

	final static String ATTACHMENTS = "attachments";
	final static String ATTACHMENT_POSITION = "position";
	final static String NAME = "name";
	final static String DATE = "date";
	final static String ITEM_ID = "itemId";

	private final static int UI_FLAGS_DEFAULT =
			SYSTEM_UI_FLAG_LAYOUT_STABLE | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ImageViewModel viewModel;
	private PullDownLayout layout;
	private AppBarLayout appBarLayout;
	private ViewPager2 viewPager;
	private List<AttachmentItem> attachments;
	private MessageId conversationMessageId;

	private final ActivityResultLauncher<String> launcher =
			registerForActivityResult(new CreateDocumentAdvanced(),
					this::onImageUriSelected);

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ImageViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		if (state == null) supportPostponeEnterTransition();
		Window window = getWindow();
		Transition transition = new Fade();
		setSceneTransitionAnimation(transition, null, transition);

		Intent i = getIntent();
		attachments =
				requireNonNull(i.getParcelableArrayListExtra(ATTACHMENTS));
		int position = i.getIntExtra(ATTACHMENT_POSITION, -1);
		if (position == -1) throw new IllegalStateException();
		String name = i.getStringExtra(NAME);
		long time = i.getLongExtra(DATE, 0);
		byte[] messageIdBytes = requireNonNull(i.getByteArrayExtra(ITEM_ID));

		viewModel.expectAttachments(attachments);
		viewModel.getSaveState().observeEvent(this,
				this::onImageSaveStateChanged);

		setContentView(R.layout.activity_image);
		layout = findViewById(R.id.layout);
		layout.setCallback(this);
		layout.getViewTreeObserver().addOnGlobalLayoutListener(this);

		WindowCompat.setDecorFitsSystemWindows(window, false);

		appBarLayout = findViewById(R.id.appBarLayout);
		Toolbar toolbar = requireNonNull(setUpCustomToolbar(true));
		TextView contactName = toolbar.findViewById(R.id.contactName);
		TextView dateView = toolbar.findViewById(R.id.dateView);

		String date = formatDateAbsolute(this, time);
		contactName.setText(name);
		dateView.setText(date);
		conversationMessageId = new MessageId(messageIdBytes);

		viewPager = findViewById(R.id.viewPager);
		ImagePagerAdapter pagerAdapter = new ImagePagerAdapter();
		viewPager.setAdapter(pagerAdapter);
		viewPager.setCurrentItem(position);

		viewModel.getOnImageClicked().observeEvent(this, this::onImageClicked);
		window.getDecorView().setSystemUiVisibility(UI_FLAGS_DEFAULT);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.image_actions, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (item.getItemId() == R.id.action_save_image) {
			showSaveImageDialog();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onGlobalLayout() {
		viewModel.setToolbarPosition(
				appBarLayout.getTop(), appBarLayout.getBottom()
		);
		layout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
	}

	@Override
	public void onPullStart() {
		appBarLayout.animate()
				.alpha(0f)
				.start();
	}

	@Override
	public void onPull(float progress) {
	}

	@Override
	public void onPullCancel() {
		appBarLayout.animate()
				.alpha(1f)
				.start();
	}

	@Override
	public void onPullComplete() {
		showStatusBarBeforeFinishing();
		supportFinishAfterTransition();
	}

	@Override
	public void onBackPressed() {
		showStatusBarBeforeFinishing();
		super.onBackPressed();
	}

	private void onImageClicked(@Nullable Boolean clicked) {
		if (clicked != null && clicked) {
			toggleSystemUi();
		}
	}

	private void toggleSystemUi() {
		View decorView = getWindow().getDecorView();
		if (appBarLayout.getVisibility() == VISIBLE) {
			hideSystemUi(decorView);
		} else {
			showSystemUi(decorView);
		}
	}

	private void hideSystemUi(View decorView) {
		decorView.setSystemUiVisibility(
				SYSTEM_UI_FLAG_FULLSCREEN | UI_FLAGS_DEFAULT);
		appBarLayout.animate()
				.translationYBy(-1 * appBarLayout.getHeight())
				.alpha(0f)
				.withEndAction(() -> appBarLayout.setVisibility(GONE))
				.start();
	}

	private void showSystemUi(View decorView) {
		decorView.setSystemUiVisibility(UI_FLAGS_DEFAULT);
		appBarLayout.animate()
				.translationYBy(appBarLayout.getHeight())
				.alpha(1f)
				.withStartAction(() -> appBarLayout.setVisibility(VISIBLE))
				.start();
	}

	private void showStatusBarBeforeFinishing() {
		if (appBarLayout.getVisibility() == GONE) {
			View decorView = getWindow().getDecorView();
			decorView.setSystemUiVisibility(UI_FLAGS_DEFAULT);
		}
	}

	private void showSaveImageDialog() {
		OnClickListener okListener = (dialog, which) -> {
			String name = viewModel.getFileName() + "." +
					getVisibleAttachment().getExtension();
			try {
				launcher.launch(name);
			} catch (ActivityNotFoundException e) {
				viewModel.onSaveImageError();
			}
		};
		Builder builder = new Builder(this, R.style.ZerionDialogTheme);
		builder.setTitle(getString(R.string.dialog_title_save_image));
		builder.setMessage(getString(R.string.dialog_message_save_image));
		builder.setIcon(getDialogIcon(this, R.drawable.ic_security));
		builder.setPositiveButton(R.string.save_image, okListener);
		builder.setNegativeButton(R.string.cancel, null);
		builder.show();
	}

	private void onImageUriSelected(@Nullable Uri uri) {
		if (uri == null) return;
		viewModel.saveImage(getVisibleAttachment(), uri);
	}

	private void onImageSaveStateChanged(@Nullable Boolean error) {
		if (error == null) return;
		int stringRes = error ?
				R.string.save_image_error : R.string.save_image_success;
		int colorRes = error ?
				R.color.zerion_error_red : R.color.zerion_cyan;
		new ZerionSnackbarBuilder()
				.setBackgroundColor(colorRes)
				.make(layout, stringRes, LENGTH_LONG)
				.show();
	}

	private AttachmentItem getVisibleAttachment() {
		return attachments.get(viewPager.getCurrentItem());
	}

	private class ImagePagerAdapter extends FragmentStateAdapter {

		private boolean isFirst = true;

		private ImagePagerAdapter() {
			super(ImageActivity.this);
		}

		@NotNull
		@Override
		public Fragment createFragment(int position) {
			Fragment f = ImageFragment.newInstance(
					attachments.get(position), conversationMessageId, isFirst);
			isFirst = false;
			return f;
		}

		@Override
		public int getItemCount() {
			return attachments.size();
		}
	}

}
