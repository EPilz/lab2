package controller.communication;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
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
import javax.crypto.Mac;
import javax.crypto.SecretKey;

import org.bouncycastle.util.encoders.Base64;

import cli.MyShell;
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
	private Mac hMac;
	private MyShell shell;
	
	private Map<String, ClientInfo> clientInfos;
	private CopyOnWriteArrayList<Channel> activeChannels;
	private LinkedHashMap<Character, Long> usageOfOperators = new LinkedHashMap<>();

	private boolean isShutdown;
	
	public ClientCommunicationThread(CloudController cloudController, ServerSocket serverSocket, Config controllerConfig, Config userConfig, Mac hMac, MyShell shell) {
		this.cloudController = cloudController;
		this.serverSocket = serverSocket;
		this.pool = Executors.newCachedThreadPool();
		this.controllerConfig = controllerConfig;
		this.userConfig = userConfig;
		this.activeChannels =  new CopyOnWriteArrayList<>();
		
		this.hMac = hMac;
		this.shell = shell;
		
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
		while (!cloudController.isStop()) {
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
		isShutdown = true;
		for (Channel channel : activeChannels) {
			if(channel != null && channel.isConnected())
			{
				channel.close();			
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
		private final String B64 = "a-zA-Z0-9/+";
		
		private Channel channel;
		private String loggedInUser;
		
		public ClientConnectionThread(Config controllerConfig, Socket socket) { 
			this.channel = new Base64Channel(socket);
		}	

		@Override
		public void run() {
			String keysDir = controllerConfig.getString("keys.dir");
			File privateKey = new File(controllerConfig.getString("key"));
			
			boolean authenticated = processAuthentication(privateKey, keysDir);
			if(!authenticated)
			{
				shell.writeLine("Error: an authentication failed");
				if(channel.isConnected())
					channel.close();
				return;
			}
			
			try {
				String request;
				while (channel.isConnected() && (request = channel.readMessage()) != null) {		
					if(request.startsWith("logout")) {
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
				if(!isShutdown)
					shell.writeLine("Error: while reading from client");
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
						
						String computeTask = "!compute " + value1 + " " + operation + " " + value2;
						hMac.update(computeTask.getBytes());						
						byte[] hash = hMac.doFinal();
						
						byte[] encodeHash = Base64.encode(hash);
	
						nodeWriter.println(new String(encodeHash) + " " +computeTask);	
						value1 = nodeReader.readLine();							
						
						if(value1.startsWith("Error")) {
							break;
						} else if(value1.contains("!tampered")) {
							int index = value1.indexOf("!compute");				
							String term = value1.substring(index, value1.length()).trim();
							value1 = "Term " + term + " tampered during the transmission!";
							break;
						} else if(value1.contains("!result")) {						
							int index = value1.indexOf("!result");
							String strEncodeHash = value1.substring(0, index).trim();		
							String term = value1.substring(index, value1.length()).trim();
							
							if(verifyHash(strEncodeHash, term)) {
								value1 = term.replaceAll("!result", "").trim();
								n.addUsage(value1.length() * 50);
								countOfOperation++;
							} else {
								value1 = "Result Term " + term.trim() + " tampered during the transmission!";
								break;
							}
						} else {
							value1 = "Unknown command received: " + value1;
							break;
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
		
		private boolean verifyHash(String encodeHash, String term) {
			byte[] receivedHash = Base64.decode(encodeHash);
			
			hMac.update(term.getBytes());						
			byte[] computedHash = hMac.doFinal();

			return MessageDigest.isEqual(computedHash, receivedHash);
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
		
		private boolean processAuthentication(File privateKeyFile, String keysDir)
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
				String[] messageParts = message.split(" ");
				
				//Prepare answer
				String username = messageParts[1];
				File publicKeyOfUser = new File(keysDir + "/" + username + ".pub.pem");
				if(!publicKeyOfUser.exists())
				{
					shell.writeLine("Error: " + username + " does not exist");
					channel.close();
					return false;
				}
				
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
				//Create secure channel
				channel = new SecureChannel((Base64Channel) channel, key, ivParam);
				//Read controller challenge and check it
				byte[] controllerChallengeAnswer = channel.readByteMessage();
				if(!Arrays.equals(controllerChallengeAnswer, controllerChallenge))
				{
					channel.close();
					return false;
				}
				else //Login user
				{
					loggedInUser = username;
					clientInfos.get(loggedInUser).setStatus(ClientInfo.Status.ONLINE);
				}
			} catch (Exception e) {
				channel.close();
				return false;
			}
			
			return true;
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


