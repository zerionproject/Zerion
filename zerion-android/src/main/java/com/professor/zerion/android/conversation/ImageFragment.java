package com.professor.zerion.android.conversation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.chrisbanes.photoview.PhotoView;

import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.core.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.util.SafeImageDecoder;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import org.briarproject.nullsafety.MethodsNotNullByDefault;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.inject.Inject;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import static android.widget.ImageView.ScaleType.FIT_START;
import static com.bumptech.glide.load.engine.DiskCacheStrategy.NONE;
import static com.professor.zerion.android.attachment.AttachmentItem.State.AVAILABLE;
import static com.professor.zerion.android.attachment.AttachmentItem.State.ERROR;
import static com.professor.zerion.android.conversation.ImageActivity.ATTACHMENT_POSITION;
import static com.professor.zerion.android.conversation.ImageActivity.ITEM_ID;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@MethodsNotNullByDefault
@ParametersAreNonnullByDefault
public class ImageFragment extends Fragment
		implements RequestListener<Drawable> {

	private final static String IS_FIRST = "isFirst";
	private final static String SHOWN = "shown";
	private final static String PAGE = "page";
	private final static String PAGES = "pages";
	@DrawableRes
	private static final int ERROR_RES = R.drawable.ic_image_broken;

	@VisibleForTesting
	static Executor decoder = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "viewer-decode");
		t.setDaemon(true);
		return t;
	});

	private static final Handler MAIN = new Handler(Looper.getMainLooper());

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Nullable
	private AttachmentItem attachment;
	@Nullable
	private String token;
	private boolean isFirst;
	private int page;
	private int pages;
	@Nullable
	private MessageId conversationItemId;
	private ImageViewModel viewModel;
	private PhotoView photoView;
	@Nullable
	private Object decoding;
	@Nullable
	private Bitmap decoded;

	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(),
				viewModelFactory).get(ImageViewModel.class);
	}

	static ImageFragment newInstance(AttachmentItem a,
			MessageId conversationMessageId, boolean isFirst, int page,
			int pages) {
		ImageFragment f = new ImageFragment();
		Bundle args = new Bundle();
		args.putParcelable(ATTACHMENT_POSITION, a);
		args.putBoolean(IS_FIRST, isFirst);
		args.putByteArray(ITEM_ID, conversationMessageId.getBytes());
		args.putInt(PAGE, page);
		args.putInt(PAGES, pages);
		f.setArguments(args);
		return f;
	}

	static ImageFragment newInstance(String token, boolean isFirst) {
		ImageFragment f = new ImageFragment();
		Bundle args = new Bundle();
		args.putString(SHOWN, token);
		args.putBoolean(IS_FIRST, isFirst);
		args.putInt(PAGE, 0);
		args.putInt(PAGES, 1);
		f.setArguments(args);
		return f;
	}

	@Override
	public void onAttach(Context ctx) {
		super.onAttach(ctx);
		injectFragment(
				((BaseActivity) requireActivity()).getActivityComponent());
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Bundle args = requireArguments();
		isFirst = args.getBoolean(IS_FIRST);
		page = args.getInt(PAGE);
		pages = args.getInt(PAGES, 1);
		token = args.getString(SHOWN);
		if (token == null) {
			attachment = requireNonNull(args.getParcelable(ATTACHMENT_POSITION));
			conversationItemId =
					new MessageId(requireNonNull(args.getByteArray(ITEM_ID)));
		}
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_image, container,
				false);

		photoView = v.findViewById(R.id.photoView);
		photoView.setScaleLevels(1, 2, 4);
		photoView.setOnClickListener(view -> viewModel.clickImage());
		photoView.setContentDescription(ImageActivity.positionLabel(
				inflater.getContext(), page, pages));

		AttachmentItem a = attachment;
		if (a == null) {
			showFromMemory();
		} else if (a.getState() == AVAILABLE) {
			loadImage(a);
		} else if (a.getState() == ERROR) {
			photoView.setImageResource(ERROR_RES);
			startPostponedTransition();
		} else {
			photoView.setImageResource(R.drawable.ic_image_missing);
			startPostponedTransition();
			LifecycleOwner owner = getViewLifecycleOwner();
			viewModel.getOnAttachmentReceived(a.getMessageId())
					.observeEvent(owner, this::onAttachmentReceived);
		}

		return v;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		forgetPicture();
	}

	void forgetPicture() {
		decoding = null;
		if (photoView == null) return;
		if (attachment != null) Glide.with(photoView).clear(photoView);
		photoView.setImageDrawable(null);
		Bitmap shown = decoded;
		decoded = null;
		if (shown != null) shown.recycle();
	}

	private void showFromMemory() {
		MediaViewer.Shown shown = MediaViewer.get(token);
		if (shown == null) {
			photoView.setImageResource(ERROR_RES);
			startPostponedTransition();
			return;
		}
		byte[] data = shown.data;
		int size = decodeSize();
		Object ticket = new Object();
		decoding = ticket;
		decoder.execute(() -> {
			Bitmap bmp = SafeImageDecoder.decode(data, size);
			MAIN.post(() -> onDecoded(ticket, bmp));
		});
	}

	private void onDecoded(Object ticket, @Nullable Bitmap bmp) {
		if (decoding != ticket || getView() == null) {
			if (bmp != null) bmp.recycle();
			return;
		}
		decoding = null;
		if (bmp == null) {
			photoView.setImageResource(ERROR_RES);
		} else {
			decoded = bmp;
			photoView.setImageBitmap(bmp);
			Drawable d = photoView.getDrawable();
			if (d != null && viewModel.isOverlappingToolbar(photoView, d)) {
				photoView.setScaleType(FIT_START);
			}
		}
		startPostponedTransition();
	}

	private int decodeSize() {
		DisplayMetrics dm = getResources().getDisplayMetrics();
		int longest = Math.max(dm.widthPixels, dm.heightPixels);
		return Math.max(1024, Math.min(2560, longest));
	}

	static RequestBuilder<Drawable> viewerRequest(RequestManager glide,
			AttachmentHeader header) {
		return glide.load(header).diskCacheStrategy(NONE)
				.skipMemoryCache(true);
	}

	private void loadImage(AttachmentItem a) {
		viewerRequest(Glide.with(this), a.getHeader())
				.error(ERROR_RES)
				.addListener(this)
				.into(photoView);
	}

	private void onAttachmentReceived(Boolean received) {
		AttachmentItem a = attachment;
		if (received && a != null) loadImage(a);
	}

	@Override
	public boolean onLoadFailed(@Nullable GlideException e,
			Object model, Target<Drawable> target,
			boolean isFirstResource) {
		startPostponedTransition();
		return false;
	}

	@Override
	public boolean onResourceReady(Drawable resource, Object model,
			Target<Drawable> target, DataSource dataSource,
			boolean isFirstResource) {
		AttachmentItem a = attachment;
		MessageId id = conversationItemId;
		if (!(resource instanceof Animatable) && a != null && id != null) {
			photoView.setTransitionName(a.getTransitionName(id));
		}
		if (viewModel.isOverlappingToolbar(photoView, resource)) {
			photoView.setScaleType(FIT_START);
		}
		startPostponedTransition();
		return false;
	}

	private void startPostponedTransition() {
		if (getActivity() != null && isFirst) {
			getActivity().supportStartPostponedEnterTransition();
		}
	}

}
