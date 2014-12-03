package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;

import cli.Command;
import cli.Shell;
import util.Config;

public class Client implements IClientCli, Runnable {

	private String componentName;
	private Config config;
	
	private Shell shell;
	private Socket socket;
	private BufferedReader cloudReader;
	PrintWriter cloudWriter;

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
	public Client(String componentName, Config config,
			InputStream userRequestStream, PrintStream userResponseStream) {
		this.componentName = componentName;
		this.config = config;

		shell = new Shell(componentName, userRequestStream, userResponseStream);
		shell.register(this);
	}

	@Override
	public void run() {
		try {
			shell.writeLine("Client " + componentName + " is started!");
		} catch (IOException e) { }
		
		try {			
			
			new Thread(shell).start();	
			
			socket = new Socket(config.getString("controller.host"),
					config.getInt("controller.tcp.port"));

			cloudReader = new BufferedReader(
					new InputStreamReader(socket.getInputStream()));
			
			cloudWriter = new PrintWriter(
					socket.getOutputStream(), true);		
		} catch (UnknownHostException e) {
			writeToShell("Error: cannot connect to cloud controller");
			try {
				exit();
			} catch (IOException e1) { }
		} catch (IOException e) {
			writeToShell("Error: cannot connect to cloud controller");
			try {
				exit();
			} catch (IOException e1) { }
		} 
	}
	
	
	public void writeToShell(String text) {
		try {
			shell.writeLine(text);
		} catch (IOException e) {  }
	}
	
	private String sentToCloudController(String message) {
		if(cloudWriter != null) {
			cloudWriter.println(message);
			try {
				String response = cloudReader.readLine();
				if(response == null) {
					return "Error: CloudController unreachable";
				}
				return response;
			} catch (Exception e) {
				return "Error: while read from CloudController";
			}
		} else {
			return "Error: not connect to cloud controller";
		}
	}


	@Override
	@Command
	public String login(String username, String password) throws IOException {		
		return sentToCloudController("login " + username + " " + password);							
	}

	@Override
	@Command
	public String logout() throws IOException {
		return sentToCloudController("logout");				
	}

	@Override
	@Command
	public String credits() throws IOException {
		return sentToCloudController("credits");
	}

	@Override
	@Command
	public String buy(long credits) throws IOException {
		return sentToCloudController("buy " + credits);
	}

	@Override
	@Command
	public String list() throws IOException {
		return sentToCloudController("list");
	}

	@Override
	@Command
	public String compute(String term) throws IOException {
		return sentToCloudController("compute " + term);
	}

	@Override
	@Command
	public String exit() throws IOException {		
		if (socket != null && !socket.isClosed()) {	
			try {
				logout();
			} catch(Exception ex) { }
			socket.close();			
		}

		if(cloudWriter != null) {			
			cloudWriter.close();
		}
		
		if(cloudReader != null) {
			cloudReader.close();
		}	
		shell.close();		
		
		return "Shut down completed! Bye ..";
	}

	/**
	 * @param args
	 *            the first argument is the name of the {@link Client} component
	 */
	public static void main(String[] args) {
		Client client = new Client(args[0], new Config("client"), System.in, System.out);
		client.run();
	}

	// --- Commands needed for Lab 2. Please note that you do not have to
	// implement them for the first submission. ---

	@Override
	public String authenticate(String username) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

}
