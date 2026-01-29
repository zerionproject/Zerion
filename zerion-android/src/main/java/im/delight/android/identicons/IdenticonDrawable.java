package im.delight.android.identicons;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import static android.graphics.PixelFormat.OPAQUE;

@UiThread
public class IdenticonDrawable extends Drawable {

	private static final int HEIGHT = 200, WIDTH = 200;

	private final Identicon identicon;

	public IdenticonDrawable(@NonNull byte[] input) {
		super();
		identicon = new Identicon(input);
	}

	@Override
	public int getIntrinsicHeight() {
		return HEIGHT;
	}

	@Override
	public int getIntrinsicWidth() {
		return WIDTH;
	}

	@Override
	public void setBounds(@NonNull Rect bounds) {
		super.setBounds(bounds);
		identicon.updateSize(bounds.right - bounds.left,
				bounds.bottom - bounds.top);
	}

	@Override
	public void setBounds(int left, int top, int right, int bottom) {
		super.setBounds(left, top, right, bottom);
		identicon.updateSize(right - left, bottom - top);
	}

	@Override
	public void draw(@NonNull Canvas canvas) {
		identicon.draw(canvas);
	}

	@Override
	public void setAlpha(int alpha) {

	}

	@Override
	public void setColorFilter(ColorFilter cf) {

	}

	@Override
	public int getOpacity() {
		return OPAQUE;
	}
}
