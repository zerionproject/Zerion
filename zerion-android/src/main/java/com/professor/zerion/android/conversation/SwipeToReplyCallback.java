package com.professor.zerion.android.conversation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.View;

import com.professor.zerion.R;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import static androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE;
import static androidx.recyclerview.widget.ItemTouchHelper.RIGHT;

public class SwipeToReplyCallback extends ItemTouchHelper.Callback {

    private final Context context;
    private final SwipeToReplyListener swipeListener;
    private Drawable replyIcon;
    private final float replyIconMargin = 32f;
    private boolean vibrated = false;
    private View currentSwipingView = null;
    private float replyButtonProgress = 0f;
    private long lastReplyButtonAnimationTime = 0;
    private boolean isVibrate = false;
    private float lastDx = 0f;
    private float swipeThresholdPx = 0f;

    public interface SwipeToReplyListener {
        void onSwipeToReply(ConversationItem item);
    }

    public SwipeToReplyCallback(Context context, SwipeToReplyListener listener) {
        this.context = context;
        this.swipeListener = listener;
        this.replyIcon = ContextCompat.getDrawable(context, R.drawable.ic_reply);
        replyIcon.setColorFilter(new PorterDuffColorFilter(
                ContextCompat.getColor(context, R.color.briar_primary),
                PorterDuff.Mode.SRC_IN
        ));
    }

    @Override
    public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                @NonNull RecyclerView.ViewHolder viewHolder) {
        return makeMovementFlags(0, RIGHT);
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView,
                         @NonNull RecyclerView.ViewHolder viewHolder,
                         @NonNull RecyclerView.ViewHolder target) {
        return false;
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
    }

    @Override
    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
        return 0.5f;
    }

    @Override
    public float getSwipeEscapeVelocity(float defaultValue) {
        return defaultValue * 0.5f;
    }

    @Override
    public void onChildDraw(@NonNull Canvas c,
                           @NonNull RecyclerView recyclerView,
                           @NonNull RecyclerView.ViewHolder viewHolder,
                           float dX, float dY,
                           int actionState,
                           boolean isCurrentlyActive) {

        if (actionState == ACTION_STATE_SWIPE) {
            View itemView = viewHolder.itemView;

            if (swipeThresholdPx == 0f) {
                swipeThresholdPx = itemView.getWidth() * 0.25f;
            }

            float maxSwipe = itemView.getWidth() * 0.3f;
            float translationX = Math.min(dX, maxSwipe);

            if (translationX > maxSwipe * 0.5f) {
                float overflow = translationX - maxSwipe * 0.5f;
                translationX = maxSwipe * 0.5f + overflow * 0.3f;
            }

            itemView.setTranslationX(translationX);

            float progress = translationX / maxSwipe;
            float iconAlpha = Math.min(1f, progress * 2);

            if (translationX > 0 && replyIcon != null) {
                int iconSize = replyIcon.getIntrinsicHeight();
                int top = itemView.getTop() + (itemView.getHeight() - iconSize) / 2;
                int iconLeft = (int) replyIconMargin;
                int iconRight = iconLeft + iconSize;
                int iconBottom = top + iconSize;

                replyIcon.setBounds(iconLeft, top, iconRight, iconBottom);
                replyIcon.setAlpha((int) (255 * iconAlpha));
                replyIcon.draw(c);
            }

            if (dX > swipeThresholdPx && !vibrated && isCurrentlyActive) {
                itemView.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                );
                vibrated = true;
            } else if (dX < swipeThresholdPx * 0.75f) {
                vibrated = false;
            }

            if (isCurrentlyActive) {
                lastDx = dX;
            }

            if (!isCurrentlyActive && lastDx > swipeThresholdPx) {
                if (viewHolder instanceof ConversationItemViewHolder) {
                    ConversationAdapter adapter = (ConversationAdapter)
                            recyclerView.getAdapter();
                    int position = viewHolder.getAdapterPosition();
                    if (adapter != null && position != RecyclerView.NO_POSITION) {
                        ConversationItem item = adapter.getItemAt(position);
                        if (item != null) {
                            swipeListener.onSwipeToReply(item);
                        }
                    }
                }
                lastDx = 0f;
            }

            currentSwipingView = isCurrentlyActive ? itemView : null;
        } else {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY,
                            actionState, isCurrentlyActive);
        }
    }

    @Override
    public void clearView(@NonNull RecyclerView recyclerView,
                         @NonNull RecyclerView.ViewHolder viewHolder) {
        super.clearView(recyclerView, viewHolder);
        viewHolder.itemView.setTranslationX(0f);
        vibrated = false;
        currentSwipingView = null;
    }
}