package controller;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.rmi.AlreadyBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.Key;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import model.ComputationRequestInfo;
import admin.INotificationCallback;
import util.Config;
import cli.Command;
import cli.MyShell;
import cli.Shell;
import controller.communication.ClientCommunicationThread;
import controller.communication.NodeCommunicationThread;
import controller.info.ClientInfo;
import controller.info.NodeInfo;

public class CloudController implements ICloudControllerCli, IAdminConsole, Runnable {

	private String componentName;
	private Config config;
	private Registry registry;
	
	private MyShell shell;
	private ClientCommunicationThread clientCommunicationThread;
	private NodeCommunicationThread nodeCommunicationThread;
	private ServerSocket serverSocket;
	private DatagramSocket datagramSocket;
	
	private HashMap<String, INotificationCallback> subscribedUsers = new HashMap<String, INotificationCallback>();
	private HashMap<String, Integer> subscribedUsersLimits = new HashMap<String, Integer>();

	
	private boolean stop = true;
	
	/**
	 * @param componentName
	 *            the name of the component - represented in the prompt
	 * @param config
	 *            the configuration to use
	 * @param userRequestStream
	 *            the input stream to read user input from
	 * @param userResponseStream
	 *            the output stream to write the console output to
	 */
	public CloudController(String componentName, Config config,
			InputStream userRequestStream, PrintStream userResponseStream) {
		this.componentName = componentName;
		this.config = config;
		
		shell = new MyShell(componentName, userRequestStream, userResponseStream);
		shell.register(this);
	}

	@Override
	public void run() {
		Config userConfig = new Config("user");
		try {
			serverSocket = new ServerSocket(config.getInt("tcp.port"));
			clientCommunicationThread = new ClientCommunicationThread(this, serverSocket, userConfig);
			clientCommunicationThread.start();
			
			datagramSocket = new DatagramSocket(config.getInt("udp.port"));
			nodeCommunicationThread = new NodeCommunicationThread(this, datagramSocket, 
					config.getInt("node.timeout"), config.getInt("node.checkPeriod"), config.getInt("controller.rmax"));
			nodeCommunicationThread.start();
						
			new Thread(shell).start();
			
			// create and export the registry instance on localhost at the specified port
			registry = LocateRegistry.createRegistry(config.getInt("controller.rmi.port"));
			// create a remote object of this server object
			IAdminConsole remote = (IAdminConsole) UnicastRemoteObject
					.exportObject(this, 0);
			// bind the obtained remote object on specified binding name in the registry
			registry.bind(config.getString("binding.name"), remote);
		} catch (IOException e) {
			try {
				exit();
			} catch (IOException e1) { }
		} catch (AlreadyBoundException e) {
			throw new RuntimeException(
					"Error while binding remote object to registry.", e);
		}
	
		shell.writeLine("Controller " + componentName + " is online!");
	}
		
	public NodeInfo getNodeWithLeastUsage(Character operation) {
		List<NodeInfo> nodeInfos = nodeCommunicationThread.getNodeInfosWithAvailableOperation(operation);
		NodeInfo leastUsageNode = null;
		
		if(nodeInfos.size() > 0) {
			leastUsageNode = nodeInfos.get(0);
			
			for (int i = 1; i < nodeInfos.size(); i++) {
				if(nodeInfos.get(i).getUsage() < leastUsageNode.getUsage()) {
					leastUsageNode = nodeInfos.get(i);
				}
			}
		}
		
		return leastUsageNode;
	}
	
	public String listOfOperators() {
		Set<Character> operators = new HashSet<>();
		String str = "";

		for (NodeInfo nodeInfo : nodeCommunicationThread.nodeInfos()) {
			if(nodeInfo.getStatus().equals(NodeInfo.Status.ONLINE)) {
				operators.addAll(nodeInfo.getOperators());
			}
		}

		for (Character character : operators) {
			str += character;
			
		}		
		return str;
	}
	
	@Override
	@Command
	public String nodes() throws IOException {
		String nodes = "";
		
		for(NodeInfo nodeInfo : nodeCommunicationThread.nodeInfos()) {
			nodes += "IP: " + nodeInfo.getIp() + " Port: " + nodeInfo.getTcpPort() + " " + nodeInfo.getStatus().getText() + "  Usage: " + nodeInfo.getUsage() + "\n";
		}
		
		return nodes.isEmpty() ? "no nodes registered" : nodes;
	}

	@Override
	@Command
	public String users() throws IOException {
		String users = "";
		
		for(ClientInfo clientInfo : clientCommunicationThread.clientInfos()) {
			users += clientInfo.getName() + " " + clientInfo.getStatus().getText() + " Credits: " + clientInfo.getCredits() + "\n";
		}
		
		return users;
	}

	@Override
	@Command
	public String exit() throws IOException {
		stop = false;		
		
		clientCommunicationThread.shutdown();
		
		shell.close();		
		
		if (datagramSocket != null) {
			datagramSocket.close();
		}
		
		if (serverSocket != null) {
			try {
				serverSocket.close();
			} catch (IOException e) { }
		}
		
		return "Shut down completed! Bye ..";
	}
	
	public boolean isStop() {
		return stop;
	}

	public int getTimeOut() {
		return config.getInt("node.timeout");
	}

	/**
	 * @param args
	 *            the first argument is the name of the {@link CloudController}
	 *            component
	 */
	public static void main(String[] args) {
		CloudController cloudController = new CloudController(args[0], new Config("controller"), System.in, System.out);		
		cloudController.run();
	}

	@Override
	public boolean subscribe(String username, int credits,
			INotificationCallback callback) throws RemoteException {
		try {
			if(!users().contains(username)) return false;
			subscribedUsers.put(username, callback);
			subscribedUsersLimits.put(username, credits);
		} catch (IOException e) {
			e.printStackTrace();
		}		
		return true;
	}
	
	public void checkCredits(long credits, String username){
		if(subscribedUsersLimits.containsKey(username)){
			int limit = subscribedUsersLimits.get(username);
			if(credits < limit){
				try {
					subscribedUsers.get(username).notify(username, limit);
					subscribedUsers.remove(username);
					subscribedUsersLimits.remove(username);
				} catch (RemoteException e) {
					e.printStackTrace();
				}
			}
		}
	}

	@Override
	public List<ComputationRequestInfo> getLogs() throws RemoteException {
		List<ComputationRequestInfo> out = new ArrayList<>();
		Socket socket = null;
		for (NodeInfo nodeInfo : nodeCommunicationThread.nodeInfos()) {
			if(nodeInfo.getStatus().equals(NodeInfo.Status.ONLINE)) {
				try {					
					socket = new Socket(InetAddress.getByName(nodeInfo.getIp()), nodeInfo.getTcpPort());					
					PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
					writer.println("!getLogs");
					ObjectInputStream objectInputStream = new ObjectInputStream(socket.getInputStream());			
					while(true){
						try {
							ComputationRequestInfo req = (ComputationRequestInfo) objectInputStream.readObject();
							out.add(req);
						} catch(EOFException ex) {
							break;
						}
					} 
				} catch (UnknownHostException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				} finally {
					if(socket != null && ! socket.isClosed()) {
						try {
							socket.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
		return out;
	}

	@Override
	public LinkedHashMap<Character, Long> statistics() throws RemoteException {
		return clientCommunicationThread.getUsageOfOperators();
	}

	@Override
	public Key getControllerPublicKey() throws RemoteException {
		return null;
	}

	@Override
	public void setUserPublicKey(String username, byte[] key)
			throws RemoteException {
	}

}
