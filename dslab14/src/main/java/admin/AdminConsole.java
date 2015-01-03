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
import java.net.Socket;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.Key;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
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
	
	private Socket socket;
	private Shell shell;
//	private ExecutorService pool;
	
	private IAdminConsole server;
	
	//callback object
	private INotificationCallback callbackObj;

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
		
//		this.pool = Executors.newCachedThreadPool();

		// TODO
		shell = new MyShell(componentName, userRequestStream, userResponseStream);
		shell.register(this);
	}

	@Override
	public void run() {
		// TODO
		
		// obtain registry that was created by the server
		Registry registry;
		try {
			shell.writeLine("Adminconsole: " + " is online! Enter command.");
		
			new Thread(shell).start();	
			
			socket = new Socket(config.getString("controller.host"),
					config.getInt("controller.rmi.port"));

//			cloudReader = new BufferedReader(
//					new InputStreamReader(socket.getInputStream()));
//			
//			cloudWriter = new PrintWriter(
//					socket.getOutputStream(), true);
			
			
			registry = LocateRegistry.getRegistry(
					config.getString("controller.host"),
					config.getInt("controller.rmi.port"));
			// look for the bound server remote-object implementing the IAdminConsole
			// interface
			// retrieve the remote reference of the admin service
			server = (IAdminConsole) registry.lookup(config
					.getString("binding.name"));
		} catch (RemoteException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NotBoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}

	@Override
	@Command
	public boolean subscribe(String username, int credits,
			INotificationCallback callback) throws RemoteException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	@Command
	public List<ComputationRequestInfo> getLogs() throws RemoteException {
		// TODO Auto-generated method stub
		try {
			shell.writeLine("command getLogs");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return server.getLogs();
	}

	@Override
	@Command
	public LinkedHashMap<Character, Long> statistics() throws RemoteException {
		// TODO Auto-generated method stub
//		LinkedHashMap<Character, Long> sorted = server.statistics();
//		List list = new LinkedList(sorted.entrySet());
//		Collections.sort(list, new Comparator() {
//			@Override
//			public int compare(Object o1, Object o2) {
//				// TODO Auto-generated method stub
//				return ((Comparable) o1.getValue().compareTo(o2.getValue()));
//			}
//
//		});
//		return sorted;
		
		return server.statistics();
	}

	//IGNORE
	@Override
	public Key getControllerPublicKey() throws RemoteException {
		// TODO Auto-generated method stub
		return null;
	}

	//IGNORE
	@Override
	public void setUserPublicKey(String username, byte[] key)
			throws RemoteException {
		// TODO Auto-generated method stub
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
		// TODO Auto-generated method stub
		
	}
}
