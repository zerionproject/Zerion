package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.conversation.glide.Radii;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView.Adapter;

import static android.content.Context.WINDOW_SERVICE;
import static java.util.Objects.requireNonNull;
import static com.professor.zerion.android.util.UiUtils.isRtl;

@NotNullByDefault
class ImageAdapter extends Adapter<ImageViewHolder> {

	private final List<AttachmentItem> items = new ArrayList<>();
	private final ConversationListener listener;
	private final int imageSize;
	private final int radiusBig, radiusSmall;
	private final boolean isRtl;
	@Nullable
	private final AttachmentReader attachmentReader;
	@Nullable
	@DatabaseExecutor
	private final Executor dbExecutor;
	@Nullable
	private ConversationMessageItem conversationItem;

	ImageAdapter(Context ctx, ConversationListener listener) {
		this(ctx, listener, null, null);
	}

	ImageAdapter(Context ctx, ConversationListener listener,
			@Nullable AttachmentReader attachmentReader,
			@Nullable @DatabaseExecutor Executor dbExecutor) {
		this.listener = listener;
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
		imageSize = getImageSize(ctx);
		Resources res = ctx.getResources();
		radiusBig =
				res.getDimensionPixelSize(R.dimen.message_bubble_radius_big);
		radiusSmall =
				res.getDimensionPixelSize(R.dimen.message_bubble_radius_small);
		isRtl = isRtl(ctx);
	}

	@Override
	public ImageViewHolder onCreateViewHolder(ViewGroup viewGroup, int type) {
		View v = LayoutInflater.from(viewGroup.getContext()).inflate(
				R.layout.list_item_image, viewGroup, false);
		requireNonNull(conversationItem);
		return new ImageViewHolder(v, imageSize, conversationItem.getId(),
				attachmentReader, dbExecutor);
	}

	@Override
	public void onBindViewHolder(ImageViewHolder imageViewHolder,
			int position) {
		requireNonNull(conversationItem);
		AttachmentItem item = items.get(position);
		imageViewHolder.itemView.setOnClickListener(v ->
				listener.onAttachmentClicked(v, conversationItem, item)
		);
		int size = items.size();
		boolean isIncoming = conversationItem.isIncoming();
		boolean hasText = conversationItem.getText() != null;
		Radii r = getRadii(position, size, isIncoming, hasText);
		imageViewHolder.bind(item, r, size == 1, singleInRow(position, size));
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	void setConversationItem(ConversationMessageItem item) {
		this.conversationItem = item;
		this.items.clear();
		this.items.addAll(item.getAttachments());
		notifyDataSetChanged();
	}

	private int getImageSize(Context ctx) {
		Resources res = ctx.getResources();
		WindowManager windowManager =
				(WindowManager) ctx.getSystemService(WINDOW_SERVICE);
		DisplayMetrics displayMetrics = new DisplayMetrics();
		if (windowManager == null) {
			return res.getDimensionPixelSize(
					R.dimen.message_bubble_image_default);
		}
		windowManager.getDefaultDisplay().getMetrics(displayMetrics);
		int imageSize = displayMetrics.widthPixels / 3;
		int maxSize = res.getDimensionPixelSize(
				R.dimen.message_bubble_image_max_width);
		return Math.min(imageSize, maxSize);
	}

	private Radii getRadii(int pos, int num, boolean isIncoming,
			boolean hasText) {
		boolean left = isLeft(pos);
		boolean single = num == 1;
		int topLeft;
		int topRight;
		if (single) {
			topLeft = isIncoming ? radiusSmall : radiusBig;
			topRight = !isIncoming ? radiusSmall : radiusBig;
		} else if (isTopRow(pos)) {
			topLeft = left ? (isIncoming ? radiusSmall : radiusBig) : 0;
			topRight = !left ? (!isIncoming ? radiusSmall : radiusBig) : 0;
		} else {
			topLeft = 0;
			topRight = 0;
		}
		boolean singleInRow = singleInRow(pos, num);
		int bottomLeft;
		int bottomRight;
		if (!hasText && isBottomRow(pos, num)) {
			bottomLeft = singleInRow || left ? radiusBig : 0;
			bottomRight = singleInRow || !left ? radiusBig : 0;
		} else {
			bottomLeft = 0;
			bottomRight = 0;
		}
		if (isRtl) return new Radii(topRight, topLeft, bottomRight, bottomLeft);
		return new Radii(topLeft, topRight, bottomLeft, bottomRight);
	}

	void clear() {
		items.clear();
		notifyDataSetChanged();
	}

	static boolean isTopRow(int pos) {
		return pos < 2;
	}

	static boolean isLeft(int pos) {
		return pos % 2 == 0;
	}

	static boolean isBottomRow(int pos, int num) {
		return num % 2 == 0 ?
				pos >= num - 2 :
				pos > num - 2;
	}

	static boolean singleInRow(int pos, int num) {
		return num % 2 != 0 && pos == num -1;
	}

}
