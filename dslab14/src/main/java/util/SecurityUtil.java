package util;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.bouncycastle.util.encoders.Base64;

public class SecurityUtil {
	/**
	 * Creates a 32 byte random number.
	 * @return The created number.
	 */
	public static byte[] createRandom(int size)
	{
		// generates a 32 byte secure random number
		SecureRandom secureRandom = new SecureRandom();
		final byte[] number = new byte[size];
		secureRandom.nextBytes(number);
		return number;
	}
	
	/**
	 * Creates a 32 byte random number which is Base64 encoded.
	 */
	public static byte[] createBase64Challenge()
	{
		return Base64.encode(createRandom(32));
	}
	
	public static SecretKey createAESKey()
	{
		KeyGenerator generator = null;
		try {
			generator = KeyGenerator.getInstance("AES");
		} catch (NoSuchAlgorithmException e) { }
		generator.init(256);
		return generator.generateKey();
	}
	
	public static byte[] createIVParam()
	{
		return createRandom(16);
	}
}
