package controller.info;

public class ClientInfo {
	
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

	private String name;
	private Status status;
	private int currentLogins;
	private long credits;
		
	public ClientInfo(String name, Status status, long credits) {
		super();
		this.name = name;
		this.status = status;
		this.credits = credits;
		currentLogins = 0;
	}
	
	public String getName() {
		return name;
	}
	
	public Status getStatus() {
		return status;
	}
	
	public long getCredits() {
		return credits;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public void setStatus(Status status) {
		this.status = status;
	}
	
	public void setCredits(long credits) {
		this.credits = credits;
	}
	
	public synchronized void addCredits(long credits) {
		this.credits += credits;
	}
	
	public synchronized void minusCredits(long credits) {
		this.credits -= credits;
	}

	public int getCurrentLogins() {
		return currentLogins;
	}

	public void setCurrentLogins(int currentLogins) {
		this.currentLogins = currentLogins;
	}
}
