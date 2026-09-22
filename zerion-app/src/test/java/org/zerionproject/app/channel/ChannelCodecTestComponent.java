package org.zerionproject.app.channel;

import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.data.DataModule;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = DataModule.class)
interface ChannelCodecTestComponent {

	BdfReaderFactory getBdfReaderFactory();

	BdfWriterFactory getBdfWriterFactory();
}
