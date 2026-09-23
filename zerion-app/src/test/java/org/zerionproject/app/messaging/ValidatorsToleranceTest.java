package org.zerionproject.app.messaging;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.jmock.api.Invocation;
import org.jmock.lib.action.CustomAction;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Random;

import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.junit.Assert.fail;

/**
 * Every message validator the app registers is fed random structured BDF
 * bodies (nested lists, dictionaries, strings, raw bytes, numbers, booleans
 * and nulls, with the first element often a real message type) and may
 * respond only by accepting the body or throwing
 * {@link InvalidMessageException}. Any other exception means a peer can crash
 * the validation executor with a crafted message, the class of defect the
 * assessment's PROTO-01 belonged to.
 */
public class ValidatorsToleranceTest extends BrambleMockTestCase {

	private static final int BODIES_PER_VALIDATOR = 1500;

	private final BdfReaderFactory bdfReaderFactory =
			context.mock(BdfReaderFactory.class);
	private final BdfReader reader = context.mock(BdfReader.class);
	private final MetadataEncoder metadataEncoder =
			context.mock(MetadataEncoder.class);
	private final Clock clock = context.mock(Clock.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final Object introductionEncoder = metadataStub(
			"org.zerionproject.app.introduction.MessageEncoder");
	private final Object agreementEncoder = metadataStub(
			"org.zerionproject.core.transport.agreement.MessageEncoder");

	private final Group group = getGroup(getClientId(), 0);
	private final Message message = getMessage(group.getId());
	private final Random random = new Random(37);
	private final BdfList[] current = new BdfList[1];

	private static final int[] PRIVATE_TYPES = {0, 1, 2, 3, 4, 7, 8, 9, 10,
			32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44};

	@Before
	public void setUp() throws Exception {
		context.checking(new Expectations() {{
			allowing(clock).currentTimeMillis();
			will(returnValue(message.getTimestamp() + 1000));
			allowing(metadataEncoder).encode(with(any(BdfDictionary.class)));
			will(returnValue(new Metadata()));
			allowing(bdfReaderFactory).createReader(
					with(any(InputStream.class)), with(any(int.class)),
					with(any(int.class)), with(any(boolean.class)));
			will(returnValue(reader));
			allowing(bdfReaderFactory).createReader(
					with(any(InputStream.class)));
			will(returnValue(reader));
			allowing(reader).readList();
			will(currentBody());
			allowing(reader).eof();
			will(returnValue(true));
			allowing(clientHelper).toList(with(any(Message.class)),
					with(any(boolean.class)));
			will(currentBody());
			allowing(clientHelper).toList(with(any(Message.class)));
			will(currentBody());
			allowing(clientHelper).parseAndValidateAgreementPublicKey(
					with(any(byte[].class)));
			will(returnValue(getAgreementPublicKey()));
			allowing(clientHelper).parseAndValidateTransportPropertiesMap(
					with(any(BdfDictionary.class)));
			will(returnValue(Collections.<TransportId, TransportProperties>emptyMap()));
			allowing(clientHelper).parseAndValidateTransportProperties(
					with(any(BdfDictionary.class)));
			will(returnValue(new TransportProperties()));
			allowing(clientHelper).parseAndValidateAuthor(
					with(any(BdfList.class)));
			will(returnValue(getAuthor()));
			allowing(crypto).hash(with(any(String.class)),
					with(any(byte[][].class)));
			will(returnValue(new byte[32]));
			allowing(crypto).verifySignature(with(any(byte[].class)),
					with(any(String.class)), with(any(byte[].class)),
					with(any(PublicKey.class)));
			will(returnValue(false));
		}});
	}

	@Test
	public void privateMessageValidatorToleratesRandomBodies() throws Exception {
		Object v = new PrivateMessageValidator(bdfReaderFactory,
				metadataEncoder, clock, crypto);
		run(v, PRIVATE_TYPES);
	}

	@Test
	public void avatarValidatorToleratesRandomBodies() throws Exception {
		run(construct("org.zerionproject.app.avatar.AvatarValidator",
				bdfReaderFactory, metadataEncoder, clock), new int[] {0, 1, 2});
	}

	@Test
	public void introductionValidatorToleratesRandomBodies() throws Exception {
		run(construct("org.zerionproject.app.introduction.IntroductionValidator",
				introductionEncoder, clientHelper, metadataEncoder, clock),
				new int[] {0, 1, 2, 3, 4, 5, 6, 7});
	}

	@Test
	public void transportPropertyValidatorToleratesRandomBodies()
			throws Exception {
		run(construct("org.zerionproject.core.properties.TransportPropertyValidator",
				clientHelper, metadataEncoder, clock), new int[] {0, 1});
	}

	@Test
	public void transportKeyAgreementValidatorToleratesRandomBodies()
			throws Exception {
		run(construct("org.zerionproject.core.transport.agreement.TransportKeyAgreementValidator",
				clientHelper, metadataEncoder, clock, agreementEncoder),
				new int[] {0, 1, 2});
	}

	@Test
	public void clientVersioningValidatorToleratesRandomBodies()
			throws Exception {
		run(construct("org.zerionproject.core.versioning.ClientVersioningValidator",
				clientHelper, metadataEncoder, clock), new int[] {0, 1});
	}

	private void run(Object validator, int[] leadingTypes) throws Exception {
		Method validate = validator.getClass().getMethod("validateMessage",
				Message.class, Group.class);
		validate.setAccessible(true);
		for (int i = 0; i < BODIES_PER_VALIDATOR; i++) {
			BdfList body = randomList(0);
			if (random.nextInt(10) < 7) {
				int type = leadingTypes[random.nextInt(leadingTypes.length)];
				if (body.isEmpty()) body.add(type);
				else body.set(0, random.nextBoolean() ? type : (long) type);
			}
			current[0] = body;
			try {
				validate.invoke(validator, message, group);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				if (cause instanceof InvalidMessageException) continue;
				throw new AssertionError(validator.getClass().getSimpleName()
						+ " threw " + cause + " on " + body, cause);
			}
		}
	}

	private BdfList randomList(int depth) {
		BdfList list = new BdfList();
		int size = random.nextInt(depth == 0 ? 10 : 5);
		for (int i = 0; i < size; i++) list.add(randomElement(depth));
		return list;
	}

	private BdfDictionary randomDictionary(int depth) {
		BdfDictionary d = new BdfDictionary();
		int size = random.nextInt(5);
		for (int i = 0; i < size; i++) {
			d.put(randomString(random.nextInt(12)), randomElement(depth));
		}
		return d;
	}

	private Object randomElement(int depth) {
		switch (random.nextInt(depth < 3 ? 12 : 10)) {
			case 0:
				return null;
			case 1:
				return random.nextBoolean();
			case 2:
				return random.nextInt(50) - 5;
			case 3:
				return random.nextInt();
			case 4:
				return random.nextLong();
			case 5:
				return (long) random.nextInt(4) * 0x8000_0000L;
			case 6:
				return randomString(random.nextInt(8));
			case 7:
				return randomString(random.nextInt(600));
			case 8:
				return randomBytes(random.nextInt(70));
			case 9: {
				int[] hot = {0, 16, 31, 32, 33, 64, 100, 1088, 1184, 2048,
						4096, 4097};
				return randomBytes(hot[random.nextInt(hot.length)]);
			}
			case 10:
				return randomList(depth + 1);
			default:
				return randomDictionary(depth + 1);
		}
	}

	private String randomString(int length) {
		StringBuilder b = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			int k = random.nextInt(20);
			if (k == 0) b.append((char) random.nextInt(0x10000));
			else if (k == 1) b.append("😀");
			else b.append((char) ('a' + random.nextInt(26)));
		}
		return b.toString();
	}

	private byte[] randomBytes(int length) {
		byte[] b = new byte[length];
		random.nextBytes(b);
		return b;
	}

	private CustomAction currentBody() {
		return new CustomAction("the current random body") {
			@Override
			public Object invoke(Invocation invocation) {
				return current[0];
			}
		};
	}

	/**
	 * A stand-in for a package-private encoder interface: every method that
	 * returns a metadata dictionary returns an empty one, every other method
	 * returns null. The validators only call the metadata encoders after
	 * their own checks have passed, so the stub's answer never masks a
	 * failure.
	 */
	private static Object metadataStub(String interfaceName) {
		try {
			Class<?> type = Class.forName(interfaceName);
			return Proxy.newProxyInstance(type.getClassLoader(),
					new Class<?>[] {type}, (proxy, method, args) ->
							method.getReturnType() == BdfDictionary.class
									? new BdfDictionary() : null);
		} catch (ClassNotFoundException e) {
			throw new AssertionError(e);
		}
	}

	private static Object construct(String className, Object... args)
			throws Exception {
		Class<?> cls = Class.forName(className);
		for (Constructor<?> c : cls.getDeclaredConstructors()) {
			if (c.getParameterCount() != args.length) continue;
			c.setAccessible(true);
			return c.newInstance(args);
		}
		fail("no constructor with " + args.length + " parameters on "
				+ className);
		return null;
	}
}
