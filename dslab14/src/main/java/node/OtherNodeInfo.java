package node;

import java.net.InetAddress;

public class OtherNodeInfo {
	
	private String status;
	private int port;
	private InetAddress inetAddress;

	public OtherNodeInfo(String status, int port, InetAddress inetAddress) {
		this.status = status;
		this.port = port;
		this.inetAddress = inetAddress;
	}
	
	public String getStatus() {
		return status;
	}
	
	public int getPort() {
		return port;
	}
	
	public InetAddress getInetAddress() {
		return inetAddress;
	}
	
	public void setStatus(String status) {
		this.status = status;
	}
	
	public void setPort(int port) {
		this.port = port;
	}
	
	public void setInetAddress(InetAddress inetAddress) {
		this.inetAddress = inetAddress;
	}	
}
