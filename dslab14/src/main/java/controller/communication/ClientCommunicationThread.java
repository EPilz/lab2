package controller.communication;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

import org.bouncycastle.util.encoders.Base64;

import util.Base64Channel;
import util.Channel;
import util.Channel.NotConnectedException;
import util.Config;
import util.Keys;
import util.SecureChannel;
import util.SecurityUtil;
import controller.CloudController;
import controller.info.ClientInfo;
import controller.info.NodeInfo;

public class ClientCommunicationThread extends Thread {

	private ServerSocket serverSocket;
	
	private ExecutorService pool;
	private Config controllerConfig;
	private Config userConfig;
	private CloudController cloudController;
	
	private Map<String, ClientInfo> clientInfos;
	private CopyOnWriteArrayList<Channel> activeChannels;
	private LinkedHashMap<Character, Long> usageOfOperators = new LinkedHashMap<>();
	
	public ClientCommunicationThread(CloudController cloudController, ServerSocket serverSocket, Config controllerConfig, Config userConfig) {
		this.cloudController = cloudController;
		this.serverSocket = serverSocket;
		this.pool = Executors.newCachedThreadPool();
		this.controllerConfig = controllerConfig;
		this.userConfig = userConfig;
		this.activeChannels =  new CopyOnWriteArrayList<>();
		
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
				ClientConnectionThread clientConnectionThread = new ClientConnectionThread(controllerConfig, socket);
				activeChannels.add(clientConnectionThread.getChannel());
				pool.execute(clientConnectionThread);
			} catch (IOException e) {
				break;
			} 
		}		
	}
	
	public void shutdown() {
		for (Channel channel : activeChannels) {
			if(channel != null)
				channel.close();			
		}
		for (ClientInfo clientInfo : clientInfos.values()) {
			clientInfo.setStatus(ClientInfo.Status.OFFLINE);
		}
		if (pool != null) {
			pool.shutdownNow();
		}
	}
	
	class ClientConnectionThread implements Runnable {
		private final String B64 = "a-zA-Z0-9/+";
		
		private Channel channel;
		private String loggedInUser;
		
		public ClientConnectionThread(Config controllerConfig, Socket socket) { 
			this.channel = new Base64Channel(socket);
			
			String keysDir = controllerConfig.getString("keys.dir");
			File privateKey = new File(controllerConfig.getString("key"));
			
			readAndAnswerChallenge(privateKey, keysDir);
		}	

		@Override
		public void run() {
			try {
				String request;
				while ((request = getChannel().readMessage()) != null) {		
					if(request.startsWith("login")) {
						getChannel().sendMessage(login(request));
					} else if(request.startsWith("logout")) {
						getChannel().sendMessage(logout());
					} else if(request.startsWith("credits")) {
						getChannel().sendMessage(credits());
					} else if(request.startsWith("buy")) {
						getChannel().sendMessage(buy(request));
					} else if(request.startsWith("list")) {
						getChannel().sendMessage(list());
					} else if(request.startsWith("compute")) {
						getChannel().sendMessage(compute(request));
					} else {
						getChannel().sendMessage("command not found");
					}	
				}
			} catch (IOException e) { 
				e.printStackTrace();
			} catch (NotConnectedException e) {
				e.printStackTrace();
			} 
			finally {
				if (getChannel() != null) {
					activeChannels.remove(getChannel());
					if(getChannel().isConnected()) {
						getChannel().close();
					}
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
			return value1;
		}
		
		public String list() {
			if (loggedInUser == null) {
				return "You have to login first!";
			}
			String operators = cloudController.listOfOperators();
			return operators.isEmpty() ? "no operations support currently" : operators;
		}

		public Channel getChannel() {
			return channel;
		}
		
		private void readAndAnswerChallenge(File privateKeyFile, String keysDir)
		{
			try {
				//Read message from client
				byte[] encryptedMessage = channel.readByteMessage();
				
				PrivateKey privateKey = Keys.readPrivatePEM(privateKeyFile);
				Cipher privateCipher = Cipher.getInstance("RSA/NONE/OAEPWithSHA256AndMGF1Padding");
				privateCipher.init(Cipher.DECRYPT_MODE, privateKey);
				byte[] decryptedMessage = privateCipher.doFinal(encryptedMessage);
				String message = new String(decryptedMessage);
				assert message.matches("!authenticate \\w+ ["+B64+"]{43}=") : "1st message";
				System.out.println(message);
				String[] messageParts = message.split(" ");
				
				//Prepare answer
				String username = messageParts[1];
				File publicKeyOfUser = new File(keysDir + "/" + username + ".pub.pem");
				
				String clientChallenge = messageParts[2];
				byte[] controllerChallenge = SecurityUtil.createBase64Challenge();
				SecretKey key = SecurityUtil.createAESKey();
				byte[] ivParam = SecurityUtil.createIVParam();
				String answer = String.format("!ok %s %s %s %s", clientChallenge, new String(controllerChallenge),
						new String(Base64.encode(key.getEncoded())), new String(Base64.encode(ivParam)));
				
				//Encrypt message using public key of host and send it
				PublicKey publicKey = Keys.readPublicPEM(publicKeyOfUser);
				Cipher publicCipher = Cipher.getInstance("RSA/NONE/OAEPWithSHA256AndMGF1Padding");
				publicCipher.init(Cipher.ENCRYPT_MODE, publicKey);
				byte[] encryptedAnswer = publicCipher.doFinal(answer.getBytes());
				channel.sendMessage(encryptedAnswer);
				System.out.println("Send ok! message");
				//Create secure channel
				channel = new SecureChannel((Base64Channel) channel, key, ivParam);
				System.out.println("Created secure channel");
				//Read controller challenge and check it
				byte[] controllerChallengeAnswer = channel.readByteMessage();
				System.out.println("Read message: " + new String(controllerChallenge));
				if(!Arrays.equals(controllerChallengeAnswer, controllerChallenge))
					channel.close();
				else //Login user
				{
					loggedInUser = username;
					clientInfos.get(loggedInUser).setStatus(ClientInfo.Status.ONLINE);
				}
			} catch (Exception e) {
				e.printStackTrace(); //TODO: delete
			}
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


