package node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.logging.LogFactory;

import controller.info.NodeInfo;
import util.Config;
import cli.Command;
import cli.Shell;

public class Node implements INodeCli, Runnable {

	private String componentName;
	private Config config;

	private ServerSocket serverSocket;
	private Shell shell;
	private Timer timerIsAlive;
	private String logDir;

	private ExecutorService pool;
	private boolean stop = true;
	
	private int rmin;
	
	private static ThreadLocal<SimpleDateFormat> dateFormater = new ThreadLocal<SimpleDateFormat>() {
	 
		@Override
	    protected SimpleDateFormat initialValue() {
	        return new SimpleDateFormat("yyyyMMdd_HHmmss.SSS");
	    }
	};
	
	
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
	public Node(String componentName, Config config,
			InputStream userRequestStream, PrintStream userResponseStream) {
		this.componentName = componentName;
		this.config = config;
	
		this.pool = Executors.newCachedThreadPool();
		
		this.rmin = config.getInt("node.rmin");
		
		this.logDir = System.getProperty("user.dir") + File.separator + config.getString("log.dir") + File.separator;
		createDir();	
		
		shell = new Shell(componentName, userRequestStream, userResponseStream);
		shell.register(this);		
	}
	
	private void createDir() {
		File file = new File(logDir);
		if(! file.exists()) {
			file.mkdirs();
		}
	}

	@Override
	public void run() {
		new Thread(shell).start();	
		
		try {
			shell.writeLine("Node: " + componentName + " is up! Enter command.");
		} catch (IOException e2) {  }
		
		//Method for Two-Phase Commit
        twoPhaseCommit();
            			
		TimerTask action = new TimerTask() {				
            public void run() {
            	DatagramSocket socketAlive = null;
            	
            	try {
	            	socketAlive = new DatagramSocket();
	        		String message = "!alive " + config.getInt("tcp.port") + " " + config.getString("node.operators");
	        		byte[] buffer = message.getBytes();
        	
					DatagramPacket packet = new DatagramPacket(buffer, buffer.length,
							InetAddress.getByName(config.getString("controller.host")),
							config.getInt("controller.udp.port"));
					
					socketAlive.send(packet);
				} catch (UnknownHostException e) {
					System.out.println("Cannot connect to host: " + e.getMessage());
				} catch (IOException e) {
					System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
				} finally {
					if (socketAlive != null && !socketAlive.isClosed()) {
						socketAlive.close();
					}
				}
            }
        };
        
        
        timerIsAlive = new Timer();        
        timerIsAlive.schedule(action, 10, config.getInt("node.alive"));

        try {
			serverSocket = new ServerSocket(config.getInt("tcp.port"));
		} catch (IOException e) {
			throw new RuntimeException("Cannot listen on TCP port.", e);
		}
        
        while (stop) {       	
			try {
				pool.execute(new NodeRequestThread(serverSocket.accept()));
			} catch (IOException e) {
				try {
					exit();
				} catch (IOException e1) {  }
			} 
		}	       
	}
	
	private void twoPhaseCommit() {
		DatagramSocket socketHello;
		
		try {	
			socketHello = new DatagramSocket();
			
			String message = "!hello";
			byte[] buffer = message.getBytes();
			
			DatagramPacket packet = new DatagramPacket(buffer, buffer.length,
					InetAddress.getByName(config.getString("controller.host")),
					config.getInt("controller.udp.port"));
			
			socketHello.send(packet);
			
			//get init from CloudController
			buffer = new byte[1024];
			packet = new DatagramPacket(buffer, buffer.length);
			socketHello.receive(packet);

			String request = new String(packet.getData());				
			System.out.println(request);
		
		} catch (UnknownHostException e) {
			System.out.println("Cannot connect to host: " + e.getMessage());
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
		} 
	}

	@Override
	@Command
	public String exit() throws IOException {
		stop = false;
	
		if (pool != null) {
			pool.shutdown();
		}
		
		if (serverSocket != null) {
			try {
				serverSocket.close();
			} catch (IOException e) {  }
		}
		
		timerIsAlive.cancel();
		shell.close();		
		
		return "Shut down completed! Bye ..";
	}

	@Override
	public String history(int numberOfRequests) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @param args
	 *            the first argument is the name of the {@link Node} component,
	 *            which also represents the name of the configuration
	 */
	public static void main(String[] args) {
		Node node = new Node(args[0], new Config(args[0]), System.in,
				System.out);
		node.run();
	}

	// --- Commands needed for Lab 2. Please note that you do not have to
	// implement them for the first submission. ---

	@Override
	public String resources() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}
	
	class NodeRequestThread implements Runnable {

		private final Socket socket;
		
		public NodeRequestThread(Socket socket) { 
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
					if(request.startsWith("!compute")) {
						writer.println(calculate(request.replaceAll("!compute", "").trim()));
					} else {
						writer.println("not valid command");
					}
				}
			} catch (IOException e) {
				System.err.println("Error occurred while communicating with client: " + e.getMessage());
			} finally {
				if(reader != null) {
					try {
						reader.close();
					} catch (IOException e) {  }
				}
				if(writer != null) {
					writer.close();
				}
				if (socket != null && !socket.isClosed()) {
					try {
						socket.close();
					} catch (IOException e) {  }
				}
			}			
		}
		
		private String calculate(String term) {
			LogFactory.getLog("test");
			String[] termArray = term.split("\\s");
			
			int value1 = Integer.parseInt(termArray[0]);
			int value2 = Integer.parseInt(termArray[2]);
			String operation = termArray[1];
			String result = "";
			if(operation.equals("+")) {
				result = "" + (value1 + value2);
			} else if(operation.equals("-")) {
				result = "" + (value1 - value2);
			} else if(operation.equals("*")) {
				result = "" + (value1 * value2);
			} else if(operation.equals("/")) {
				if(value2 == 0) {
					result = "Error: division by 0";
				} else {
					result = "" + Math.round((value1 / (double) value2));
				}
			} else {
				result = "Error: not valid operation";
			}		
			writeLog(term, result);
			return result;
		}	
		
		private void writeLog(String term, String result) {
			String fileName = logDir + dateFormater.get().format(new Date()) +  "_" + componentName + ".log";
			BufferedWriter fileWriter;
			try {
				fileWriter = new BufferedWriter(new PrintWriter(fileName));
				fileWriter.write(term);
				fileWriter.newLine();
				fileWriter.write(result);
				fileWriter.flush();
				fileWriter.close();
			} catch (IOException e) {  }	
		}
	}
}
