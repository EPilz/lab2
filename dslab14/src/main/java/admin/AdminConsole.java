package admin;

import controller.IAdminConsole;
import model.ComputationRequestInfo;
import util.Config;
import cli.Command;
import cli.MyShell;
import cli.Shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.Socket;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.security.Key;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Please note that this class is not needed for Lab 1, but will later be
 * used in Lab 2. Hence, you do not have to implement it for the first
 * submission.
 */
public class AdminConsole implements IAdminConsole, INotificationCallback, Runnable {

	private String componentName;
	private Config config;
	private InputStream userRequestStream;
	private PrintStream userResponseStream;
	
	private Shell shell;
	
	private IAdminConsole server;
	
	private INotificationCallback remote;
	
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
	public AdminConsole(String componentName, Config config,
			InputStream userRequestStream, PrintStream userResponseStream) {
		this.componentName = componentName;
		this.config = config;
		this.userRequestStream = userRequestStream;
		this.userResponseStream = userResponseStream;
		
		shell = new MyShell(componentName, userRequestStream, userResponseStream);
		shell.register(this);
			
	}

	@Override
	public void run() {
		Registry registry;
		try {
			shell.writeLine("Adminconsole: " + " is online! Enter command.");
		
			new Thread(shell).start();	
			
			// obtain registry that was created by the server
			registry = LocateRegistry.getRegistry(
					config.getString("controller.host"),
					config.getInt("controller.rmi.port"));
			// look for the bound server remote-object implementing the IAdminConsole
			// interface
			// retrieve the remote reference of the admin service
			server = (IAdminConsole) registry.lookup(config
					.getString("binding.name"));
		} catch (RemoteException e) {
			e.printStackTrace();
		} catch (NotBoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}

	@Override	
	public boolean subscribe(String username, int credits,
			INotificationCallback callback) throws RemoteException {
		return server.subscribe(username, credits, callback);
	}
	
	@Command
	public String subscribe(String username, int credits) throws RemoteException {
		if(remote == null) remote = (INotificationCallback) UnicastRemoteObject.exportObject(this, 0);
		
		if(subscribe(username, credits, remote)) return "Successfully subscribed for user " + username;
		
		UnicastRemoteObject.unexportObject(this, true);
		return "User not available";
	}

	@Override
	@Command
	public List<ComputationRequestInfo> getLogs() throws RemoteException {
		return server.getLogs();
	}

	@Override
	@Command
	public LinkedHashMap<Character, Long> statistics() throws RemoteException {
		LinkedHashMap<Character, Long> map = server.statistics();
		List<Map.Entry<Character, Long>> entries = new ArrayList<Map.Entry<Character,Long>>(map.entrySet());
		Collections.sort(entries, new Comparator<Map.Entry<Character, Long>>() {
			public int compare(Map.Entry<Character, Long> a, Map.Entry<Character, Long> b){
				return b.getValue().compareTo(a.getValue());
			}
		});
		LinkedHashMap<Character, Long> sortedMap = new LinkedHashMap<Character, Long>();
		for (Map.Entry<Character, Long> entry : entries) {
			sortedMap.put(entry.getKey(), entry.getValue());
		}
		return sortedMap;
	}
	
	//IGNORE
	@Override
	public Key getControllerPublicKey() throws RemoteException {
		return null;
	}

	//IGNORE
	@Override
	public void setUserPublicKey(String username, byte[] key)
			throws RemoteException {
	}

	/**
	 * @param args
	 *            the first argument is the name of the {@link AdminConsole}
	 *            component
	 */
	public static void main(String[] args) {
		AdminConsole adminConsole = new AdminConsole(args[0], new Config(
				"admin"), System.in, System.out);
		adminConsole.run();
	}

	@Override
	public void notify(String username, int credits) throws RemoteException {
		try {
			shell.writeLine("Notification: " + username + " has less than " + credits + " credits.");
		} catch (IOException e) {
			e.printStackTrace();
		}		
	}
	
	@Command
	public void exit(){
			System.out.println("1");
			try {
			UnicastRemoteObject.unexportObject(this, true);
		} catch (NoSuchObjectException e) { }
			System.out.println("2");
			shell.close();		
			System.out.println("3");
			Thread.currentThread().interrupt();
			System.out.println("4");
		
		System.out.println("Closed");
	}
}
