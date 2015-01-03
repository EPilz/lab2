package util;

import java.io.IOException;
import java.net.Socket;

import org.bouncycastle.util.encoders.Base64;

public class Base64Channel implements Channel {
	private Channel tcpChannel;
	
	public Base64Channel()
	{
		tcpChannel = new TcpChannel();
	}
	
	public Base64Channel(Socket socket)
	{
		tcpChannel = new TcpChannel(socket);
	}
	
	@Override
	public boolean connect(String host, int port) {
		return tcpChannel.connect(host, port);
	}

	@Override
	public boolean isConnected() {
		return tcpChannel.isConnected();
	}

	@Override
	public void close() {
		tcpChannel.close();
	}

	@Override
	public void sendMessage(String msg) throws NotConnectedException {
		sendMessage(msg.getBytes());
	}

	@Override
	public void sendMessage(byte[] msg) throws NotConnectedException {
		byte[] base64Message = Base64.encode(msg);
		tcpChannel.sendMessage(base64Message);
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
		String readMessage = tcpChannel.readMessage();
		if(readMessage == null)
			return null;
		else
			return Base64.decode(readMessage);
	}

}
