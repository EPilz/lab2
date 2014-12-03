package controller.info;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class NodeInfo {
	
	public enum Status {
		ONLINE("online"), 
		OFFLINE("offline");
		
		private String text;
		
		private Status(String text) {
			this.text = text;
		}
		
		public String getText()  {
			return text;
		}
	}

	private String ip;
	private int tcpPort;
	private Status status;
	private long usage;	
	private int timeout;
	private Set<Character> operators;
	
	public NodeInfo(String ip, int tcpPort, Status status, long usage, String operators) {
		this.ip = ip;
		this.tcpPort = tcpPort;
		this.status = status;
		this.usage = usage;
		this.operators = Collections.synchronizedSet(new HashSet<Character>());
		addOperators(operators);
	}
	
	public String getIp() {
		return ip;
	}
	
	public int getTcpPort() {
		return tcpPort;
	}
	
	public Status getStatus() {
		return status;
	}
	
	public long getUsage() {
		return usage;
	}
	
	public void setIp(String ip) {
		this.ip = ip;
	}
	
	public void setTcpPort(int tcpPort) {
		this.tcpPort = tcpPort;
	}
	
	public void setStatus(Status status) {
		this.status = status;
	}
	
	public void setUsage(long usage) {
		this.usage = usage;
	}
	
	public void addUsage(long usage) {
		this.usage += usage;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public void addOperators(String operators) {
		for (int i = 0; i < operators.length(); i++) {
			this.operators.add(operators.charAt(i));
		}		
	}

	public Set<Character> getOperators() {
		return operators;
	}
}
