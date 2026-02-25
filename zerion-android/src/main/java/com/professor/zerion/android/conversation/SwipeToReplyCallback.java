package com.professor.zerion.android.conversation;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.professor.zerion.R;

@NotNullByDefault
class SwipeToReplyCallback extends ItemTouchHelper.Callback {

	private static final float TRIGGER_THRESHOLD = 0.3f;

	private final OnSwipeReplyListener listener;
	private boolean triggered;

	interface OnSwipeReplyListener {
		void onSwipeReply(int position);
	}

	SwipeToReplyCallback(OnSwipeReplyListener listener) {
		this.listener = listener;
	}

	@Override
	public int getMovementFlags(@NonNull RecyclerView recyclerView,
			@NonNull RecyclerView.ViewHolder viewHolder) {
		return makeMovementFlags(0, ItemTouchHelper.RIGHT);
	}

	@Override
	public boolean onMove(@NonNull RecyclerView recyclerView,
			@NonNull RecyclerView.ViewHolder viewHolder,
			@NonNull RecyclerView.ViewHolder target) {
		return false;
	}

	@Override
	public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder,
			int direction) {
	}

	@Override
	public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
		return 10.0f;
	}

	@Override
	public float getSwipeEscapeVelocity(float defaultValue) {
		return Float.MAX_VALUE;
	}

	@Override
	public float getSwipeVelocityThreshold(float defaultValue) {
		return Float.MAX_VALUE;
	}

	@Override
	public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
			@NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
			int actionState, boolean isCurrentlyActive) {
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0) {
			View itemView = viewHolder.itemView;
			float maxSwipe = itemView.getWidth() * TRIGGER_THRESHOLD;
			float clampedDX = Math.min(dX, maxSwipe);

			itemView.setTranslationX(clampedDX);

			float progress = clampedDX / maxSwipe;

			if (isCurrentlyActive && progress >= 1.0f && !triggered) {
				triggered = true;
				int pos = viewHolder.getBindingAdapterPosition();
				if (pos >= 0) {
					listener.onSwipeReply(pos);
				}
			}

			Drawable icon = ContextCompat.getDrawable(rv.getContext(),
					R.drawable.ic_reply);
			if (icon != null) {
				float density = rv.getContext().getResources()
						.getDisplayMetrics().density;
				int iconSize = (int) (24 * density);
				int iconMargin = (int) (16 * density);

				int iconTop = itemView.getTop() +
						(itemView.getHeight() - iconSize) / 2;
				int iconLeft = iconMargin;

				icon.setBounds(iconLeft, iconTop,
						iconLeft + iconSize, iconTop + iconSize);

				int alpha = (int) (progress * 255);
				icon.setAlpha(Math.min(alpha, 255));
				icon.setTint(0xFFFFFFFF);
				icon.draw(c);
			}
		} else {
			super.onChildDraw(c, rv, viewHolder, dX, dY, actionState,
					isCurrentlyActive);
		}
	}

	@Override
	public void clearView(@NonNull RecyclerView rv,
			@NonNull RecyclerView.ViewHolder viewHolder) {
		super.clearView(rv, viewHolder);
		viewHolder.itemView.setTranslationX(0);
		triggered = false;
	}
}
