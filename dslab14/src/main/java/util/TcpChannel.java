package util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class TcpChannel implements Channel {
	private Socket socket;
	private PrintWriter writer;
	private BufferedReader reader;
	private boolean isConnected;
	
	public TcpChannel() { }

	public TcpChannel(Socket socket)
	{
		this.socket = socket;
		boolean ok = initializeStreams();
		if(ok)
			isConnected = true;
		else
			isConnected = false;
	}
	
	@Override
	public boolean connect(String host, int port) {
		try {
			socket = new Socket(host, port);
			boolean ok = initializeStreams();
			if(!ok)
				return false;
		} catch (IOException e) {
			return false;
		}
		isConnected = true;
		return true;
	}
	
	private boolean initializeStreams()
	{
		try {
			reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			writer = new PrintWriter(socket.getOutputStream(), true);
		} catch (IOException e) {
			isConnected = false;
			return false;
		}
		return true;
	}
	
	@Override
	public boolean isConnected() {
		return isConnected;
	}

	@Override
	public void close() {
		if(isConnected)
		{
			try {
				reader.close();
				writer.close();
				socket.close();
			} catch (IOException e) { }
		}
		isConnected = false;
	}

	@Override
	public void sendMessage(byte[] msg) throws NotConnectedException
	{
		sendMessage(new String(msg));
	}
	
	@Override
	public void sendMessage(String msg) throws NotConnectedException {
		if(isConnected)
			writer.println(msg);
		else
			throw new NotConnectedException();
	}

	@Override
	public String readMessage() throws IOException, NotConnectedException {
		if(isConnected)
			return reader.readLine();
		else
			throw new NotConnectedException();
	}

	@Override
	public byte[] readByteMessage() throws IOException, NotConnectedException {
		String msg = readMessage();
		if(msg != null)
			return msg.getBytes();
		else
			return null;
	}
}
