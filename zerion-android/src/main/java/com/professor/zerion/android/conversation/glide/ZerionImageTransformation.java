package com.professor.zerion.android.conversation.glide;

import android.graphics.Bitmap;

import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;

public class ZerionImageTransformation extends MultiTransformation<Bitmap> {

	public ZerionImageTransformation(Radii r) {
		super(new CenterCrop(), new CustomCornersTransformation(r));
	}

}
