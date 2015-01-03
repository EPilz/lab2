package util;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SecureChannel implements Channel {
	private Base64Channel base64Channel;
	private Cipher decryptCipher;
	private Cipher encryptCipher;
	
	public SecureChannel(Base64Channel channel, byte[] secretKey, byte[] ivParam)
	{
		this(channel, new SecretKeySpec(secretKey, "AES"), ivParam);
	}
	
	public SecureChannel(Base64Channel channel, SecretKey secretKey, byte[] ivParam)
	{
		this.base64Channel = channel;
		
		try {
			encryptCipher = Cipher.getInstance("AES/CTR/NoPadding");
			encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(ivParam));
			decryptCipher = Cipher.getInstance("AES/CTR/NoPadding");
			decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(ivParam));
		} catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvalidAlgorithmParameterException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	@Override
	public boolean connect(String host, int port) {
		return base64Channel.connect(host, port);
	}

	@Override
	public boolean isConnected() {
		return base64Channel.isConnected();
	}

	@Override
	public void close() {
		base64Channel.close();
	}

	@Override
	public void sendMessage(String msg) throws NotConnectedException {
		sendMessage(msg.getBytes());
	}

	@Override
	public void sendMessage(byte[] msg) throws NotConnectedException {
		try {
			byte[] encryptedMsg = encryptCipher.doFinal(msg);
			base64Channel.sendMessage(encryptedMsg);
		} catch (IllegalBlockSizeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (BadPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public String readMessage() throws IOException, NotConnectedException {
		byte[] readMessage = readByteMessage();
		if(readMessage == null)
			return null;
		else
			return new String(readMessage);
	}

	@Override
	public byte[] readByteMessage() throws IOException, NotConnectedException {
		byte[] readMsg = base64Channel.readByteMessage();
		if(readMsg == null)
			return null;
		try {
			return decryptCipher.doFinal(readMsg);
		} catch (IllegalBlockSizeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (BadPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
}
