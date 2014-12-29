package controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import util.Config;
import cli.Command;
import cli.Shell;
import controller.communication.ClientCommunicationThread;
import controller.communication.NodeCommunicationThread;
import controller.info.ClientInfo;
import controller.info.NodeInfo;

public class CloudController implements ICloudControllerCli, Runnable {

	private String componentName;
	private Config config;
	
	private Shell shell;
	private ClientCommunicationThread clientCommunicationThread;
	private NodeCommunicationThread nodeCommunicationThread;
	private ServerSocket serverSocket;
	private DatagramSocket datagramSocket;
	
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
		
		shell = new Shell(componentName, userRequestStream, userResponseStream);
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
		} catch (IOException e) {
			try {
				exit();
			} catch (IOException e1) { }
		}

		try {
			shell.writeLine("Controller " + componentName + " is online!");
		} catch (IOException e) { }
		
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
	
	public void writeToShell(String text) {
		try {
			shell.writeLine(text);
		} catch (IOException e) {  }
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

}
