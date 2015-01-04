package util;

import java.io.IOException;

public interface Channel {
	/**
	 * Connects to the host on the specified port.
	 * 
	 * @param host Host to connect to.
	 * @param port Port to use.
	 * @return true if the connection is established, false if an error occurred.
	 */
	boolean connect(String host, int port);
	
	/**
	 * Returns true if a connection is thought to be established. Can return true, although the connection was lost
	 * since the last read.
	 * 
	 * @return true if the connection is thought to be established.
	 */
	boolean isConnected();
	
	/**
	 * Closes the connection to the host.
	 */
	void close();
	
	/**
	 * Sends the message.
	 * 
	 * @param msg Message to send.
	 */
	void sendMessage(String msg) throws NotConnectedException; 
	
	/**
	 * Sends the message.
	 * 
	 * @param msg Message to send.
	 */
	void sendMessage(byte[] msg) throws NotConnectedException;
	
	/**
	 * Reads a message (one line). This method will block, until a message was read.
	 * 
	 * @return The read message.
	 * @throws IOException 
	 */
	String readMessage() throws IOException, NotConnectedException;
	
	/**
	 * Reads a message (one line). This method will block, until a message was read.
	 * 
	 * @return The read message.
	 * @throws IOException 
	 */
	byte[] readByteMessage() throws IOException, NotConnectedException;
	
	public static class NotConnectedException extends Exception
	{
		private static final long serialVersionUID = 3136534771735622682L;

		public NotConnectedException() {
			super();
		}
		
		public NotConnectedException(String message) {
			super(message);
		}

	}
}
