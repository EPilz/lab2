package controller.communication;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import util.Config;
import controller.CloudController;
import controller.info.ClientInfo;
import controller.info.NodeInfo;

public class ClientCommunicationThread extends Thread {

	private ServerSocket serverSocket;
	
	private ExecutorService pool;
	private Config userConfig;
	private CloudController cloudController;
	
	private Map<String, ClientInfo> clientInfos;
	private CopyOnWriteArrayList<Socket> activeSockets;
	private LinkedHashMap<Character, Long> usageOfOperators = new LinkedHashMap<>();
	
	public ClientCommunicationThread(CloudController cloudController, ServerSocket serverSocket, Config userConfig) {
		this.cloudController = cloudController;
		this.serverSocket = serverSocket;
		this.pool = Executors.newCachedThreadPool();		
		this.userConfig = userConfig;
		this.activeSockets =  new CopyOnWriteArrayList<>();
		
		initClientInfos();
	}
	
	private void initClientInfos() {
		this.clientInfos = new ConcurrentHashMap<>();
		
		for(String key : userConfig.listKeys()) {
			String name = key.split("\\.")[0];
			clientInfos.put(name, new ClientInfo(name, ClientInfo.Status.OFFLINE, userConfig.getInt(name + ".credits")));
		}
	}
	
	public List<ClientInfo> clientInfos() {		
		List<ClientInfo> infos = new ArrayList<>(clientInfos.values());
		
		Collections.sort(infos, new Comparator<ClientInfo>() {
		    public int compare(ClientInfo one, ClientInfo other) {
		        return one.getName().compareTo(other.getName());
		    }
		}); 
		
		return infos;
	}

	public void run() {
		while (cloudController.isStop()) {
			try {
				Socket socket = serverSocket.accept();
				activeSockets.add(socket);
				pool.execute(new ClientConnectionThread(socket));
			} catch (IOException e) {
				break;
			} 
		}		
	}
	
	public void shutdown() {
		for (Socket socket : activeSockets) {
			try {
				socket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}			
		}
		for (ClientInfo clientInfo : clientInfos.values()) {
			clientInfo.setStatus(ClientInfo.Status.OFFLINE);
		}
		if (pool != null) {
			pool.shutdownNow();
		}
	}
	
	class ClientConnectionThread implements Runnable {
		
		private final Socket socket;
		private String loggedInUser;
		
		public ClientConnectionThread(Socket socket) { 
			this.socket = socket; 
		}	

		@Override
		public void run() {
			BufferedReader reader = null;
			PrintWriter writer = null;
			
			try {
				reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));				
				writer = new PrintWriter(socket.getOutputStream(), true);
	
				String request;
				while ((request = reader.readLine()) != null) {					
					if(request.startsWith("login")) {
						writer.println(login(request));
					} else if(request.startsWith("logout")) {
						writer.println(logout());
					} else if(request.startsWith("credits")) {
						writer.println(credits());
					} else if(request.startsWith("buy")) {
						writer.println(buy(request));
					} else if(request.startsWith("list")) {
						writer.println(list());
					} else if(request.startsWith("compute")) {
						writer.println(compute(request));
					} else {
						writer.println("command not found");
					}	
				}
			} catch (IOException e) { 
				
			} 
			finally {
				if (socket != null) {
					try {
						activeSockets.remove(socket);
						if(!socket.isClosed()) {
							socket.close();
						}
					} catch (IOException e) { }
				}
				if(reader != null) {
					try {
						reader.close();
					} catch (IOException ex) {
						System.out.println(ex.getMessage());
					}
				}
				if(writer != null) {
					writer.close();
				}
				if(loggedInUser != null) {
					clientInfos.get(loggedInUser).setStatus(ClientInfo.Status.OFFLINE);					
				}
			}
			
		}
		
		private boolean checkUser(String username, String pw) {
			if(userConfig.containsKey(username + ".password")) {
				String password = userConfig.getString(username + ".password");
				if (password == null) {
					return false;
				}
	
				if (password.equals(pw)) {
					return true;
				}
			}
			return false;
		}
		
		private String login(String request) {
			if (loggedInUser != null) {
				return "You are already logged in!";
			}
		
			String[] array = request.split(" ");
			if (array.length != 3) {
				return "Wrong format!";
			} else {
			
				if(checkUser(array[1], array[2])) {					
					if(clientInfos.get(array[1]).getStatus() == ClientInfo.Status.ONLINE) {
						return "User " + array[1] + " alread logged in on other Socket!";						
					}
					loggedInUser = array[1];
					clientInfos.get(loggedInUser).setStatus(ClientInfo.Status.ONLINE);					
					return "Successfully logged in!";
				}
				return "Wrong username/password combination!";
			}		
		}

		public String logout() {		
			if (loggedInUser == null) {
				return "You have to login first!";
			}
						
			clientInfos.get(loggedInUser).setStatus(ClientInfo.Status.OFFLINE);		
			loggedInUser = null;
			
			return "Successfully logged out!";		
		}
		
		public String credits() {
			if (loggedInUser == null) {
				return "You have to login first!";
			}
			
			return "You have " +  clientInfos.get(loggedInUser).getCredits() + " credits left."; 	
		}		
		
		public String buy(String request) {
			if (loggedInUser == null) {
				return "You have to login first!";
			}
			String[] array = request.split(" ");
			clientInfos.get(loggedInUser).addCredits(Long.parseLong(array[1]));
			
			return "You now have " + clientInfos.get(loggedInUser).getCredits() + " credits."; 	
		}	
		
		public String compute(String request) {
			if (loggedInUser == null) {
				return "You have to login first!";
			}
			request = request.replaceAll("compute", "").trim();
			String[] splitTerm = request.split(" ");
						
			String operators = request.replaceAll("\\d", "").replaceAll("\\s", "");
			updateUsageOfOperators(operators);
			String supportedOperators = cloudController.listOfOperators();
			
			for (int i = 0; i < operators.length(); i++) {
				if(! supportedOperators.contains("" + operators.charAt(i))) {
					return "Error: Operation " + operators.charAt(i) + " is not supported.";
				}
			}
			
			if(clientInfos.get(loggedInUser).getCredits() < operators.length() * 50) {
				return "Error: not enough credits"; 
			}

			String value1 = splitTerm[0];
			int countOfOperation = 0;
			for (int i = 1; i < splitTerm.length; i += 2) {				
				Character operation = splitTerm[i].charAt(0);
				String value2 = splitTerm[i+1];
				
				NodeInfo n = cloudController.getNodeWithLeastUsage(operation);
				PrintWriter nodeWriter;
				BufferedReader nodeReader;
				
				if(n != null) {
					Socket socket = null;
					
					try {
						socket = new Socket(n.getIp(), n.getTcpPort());
						socket.setSoTimeout(cloudController.getTimeOut());
						
						nodeReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
						nodeWriter = new PrintWriter(socket.getOutputStream(), true);	
						
						nodeWriter.println("!compute " + value1 + " " + operation + " " + value2);	
						value1 = nodeReader.readLine();	
						
						countOfOperation++;
						
						if(value1.startsWith("Error")) {
							break;
						} else {
							n.addUsage(value1.length() * 50);
						}						
					} catch (Exception e) {
						return "Error: Node Timeout";
					} finally {
						if (socket != null && !socket.isClosed()) {
							try {
								socket.close();
							} catch (IOException e) { }
						}
					}
				} else {
					return "Error: cannot be calculated";
				}
			}
			
			clientInfos.get(loggedInUser).minusCredits(countOfOperation * 50);
			cloudController.checkCredits(clientInfos.get(loggedInUser).getCredits(), clientInfos.get(loggedInUser).getName());
			return value1;
		}
		
		public String list() {
			if (loggedInUser == null) {
				return "You have to login first!";
			}
			String operators = cloudController.listOfOperators();
			return operators.isEmpty() ? "no operations support currently" : operators;
		}
		
		private void updateUsageOfOperators(String operators){
			for(int i = 0; i<operators.length(); i++){
				char akt = operators.charAt(i);
				if(usageOfOperators.containsKey(akt)){
					long newStat = usageOfOperators.get(akt)+1;
					usageOfOperators.put(akt, newStat);
				} else {
					usageOfOperators.put(akt, 1L);
				}
			}
		}
		
	}

	public LinkedHashMap<Character, Long> getUsageOfOperators() {
		return usageOfOperators;
	}
}


