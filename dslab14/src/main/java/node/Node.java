package node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import model.ComputationRequestInfo;

import org.apache.commons.logging.LogFactory;

import controller.info.NodeInfo;
import util.Config;
import cli.Command;
import cli.MyShell;
import cli.Shell;

public class Node implements INodeCli, Runnable {

	private String componentName;
	private Config config;

	private ServerSocket serverSocket;
	private MyShell shell;
	private Timer timerIsAlive;
	private String logDir;

	private ExecutorService pool;
	private boolean stop = true;
	
	private List<OtherNodeInfo> nodeResourceStatusList;
	private int rmin;
	private int current_resources;
	private int new_resources;
	
	private List<String> namesOfLogFiles = new ArrayList<>();
	
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
		this.nodeResourceStatusList = Collections.synchronizedList(new ArrayList<OtherNodeInfo>());
		
		this.logDir = System.getProperty("user.dir") + File.separator + config.getString("log.dir") + File.separator;
		createDir();	
		
		shell = new MyShell(componentName, userRequestStream, userResponseStream);
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
		
		shell.writeLine("Node: " + componentName + " is up!");
		
		//Method for Two-Phase Commit
        boolean goOnline = twoPhaseCommit();
        
        if(goOnline) {
        	shell.writeLine("Node: " + componentName + " is online! Enter command.");
        	
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
						shell.writeLine("Cannot connect to host: " + e.getMessage());
					} catch (IOException e) {
						shell.writeLine(e.getClass().getSimpleName() + ": " + e.getMessage());
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
        } else {
        	shell.writeLine("Node: " + componentName + " would require too many resources! Enter !exit");
        }
	}
	
	private boolean twoPhaseCommit() {
		DatagramSocket socketHello = null;
		boolean goOnline = true;
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
			String[] lines = request.trim().split("\n");
			
			if(lines.length >= 2) {
				int resourceLevel = Integer.parseInt(lines[lines.length - 1])
						/ (lines.length - 1); //eigentlich -2, aber weil aktueller Node auch dividiert werden muss nur -1

				current_resources = resourceLevel;
			
				ExecutorService poolNodes = Executors.newCachedThreadPool();
				
				if(rmin < resourceLevel) {
					for (int i = 1; i < lines.length - 1; i++) {
						String[] addr = lines[i].trim().split(":");
						OtherNodeInfo otherNodeInfo = new OtherNodeInfo("-", Integer.parseInt(addr[1]), InetAddress.getByName(addr[0]));
						nodeResourceStatusList.add(otherNodeInfo);
						Socket socket = new Socket(otherNodeInfo.getInetAddress(), otherNodeInfo.getPort());						
						poolNodes.execute(new NodeCheckResourceThread(socket, resourceLevel, otherNodeInfo, true));
					}	
					
					try {
						poolNodes.awaitTermination(2, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						System.out.println("exception await");
					}

					for (OtherNodeInfo otherNodeInfo : nodeResourceStatusList) {
						Socket socket = new Socket(otherNodeInfo.getInetAddress(), otherNodeInfo.getPort());						
						poolNodes.execute(new NodeCheckResourceThread(socket, resourceLevel, otherNodeInfo, false));
						
						if(! otherNodeInfo.getStatus().equals("ok")) {
							goOnline = false;
						}
					}
				} else {
					goOnline = false;
				}		
			} else {
				shell.writeLine("init request has the wrong format");
				goOnline = false;
			}
			
		
		} catch (UnknownHostException e) {
			System.out.println("Cannot connect to host: " + e.getMessage());
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
		} finally{
			if (socketHello != null && !socketHello.isClosed()) {
				socketHello.close();
			}			
		}
		return goOnline;
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
		if(timerIsAlive != null) {
			timerIsAlive.cancel();
		}
		
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

	@Override
	@Command
	public String resources() throws IOException {
		return "The current resources are " + current_resources + "!";
	}
	
	class NodeRequestThread implements Runnable {

		private final Socket socket;
		
		public NodeRequestThread(Socket socket) { 
			this.socket = socket; 
		}	
		
		@Override
		public void run() {
			System.out.println("run NodeRequestThread");
			BufferedReader reader = null;
			PrintWriter writer = null;
			ObjectInputStream inStream = null;
//			ObjectOutputStream outStream = null;
			
			try {
				reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));				
				writer = new PrintWriter(socket.getOutputStream(), true);
								
				String request;
				while ((request = reader.readLine()) != null) {		
					if(request.startsWith("!compute")) {
						writer.println(calculate(request.replaceAll("!compute", "").trim()));
					} else if(request.startsWith("!share")) {
						int resourceLevel = Integer.parseInt(request.trim().split("\\s+")[1]);
						new_resources = resourceLevel;
						
						if(rmin <= resourceLevel) {
							writer.println("!ok");
						} else {
							writer.println("!nok");
						}							
					} else if(request.startsWith("!commit")) {															    
						int resourceLevel = Integer.parseInt(request.trim().split("\\s+")[1]);
						
						if(resourceLevel == new_resources) {
							current_resources = new_resources;
							new_resources = -1;
						}
					} else if(request.startsWith("!rollback")) {
						new_resources = -1;		
					} else {	
						writer.println("not valid command");
					}
				}
				
				inStream = new ObjectInputStream(socket.getInputStream());
				
				try {
//					Object o;
					while(true){
						Object o = inStream.readObject();
						shell.writeLine("object arrived");
							if(o instanceof String){
								if(((String) o).equals("!getLogs")){
									shell.writeLine("getLogs command arrived");
									sendLogsToCloudController();
								}
							}
					}
				} catch (ClassNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} catch (IOException e) {
//				der inputstream wirft immer eine IOException...
//				System.err.println("Error occurred while communicating with client: " + e);
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
			namesOfLogFiles.add(fileName);
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
		
		private void sendLogsToCloudController() {
//			List<ComputationRequestInfo> out = new ArrayList<>();
			try {
				ObjectOutputStream outStream = new ObjectOutputStream(socket.getOutputStream());
				
				for(String fname : namesOfLogFiles){
					FileInputStream fstream = new FileInputStream(fname);				
					BufferedReader fileReader = new BufferedReader(new InputStreamReader(fstream));
					ComputationRequestInfo computationRequestInfo = new ComputationRequestInfo();
					computationRequestInfo.setNodeName(componentName);
					String timeStamp = fname.replace("_"+componentName+".log", "");
					computationRequestInfo.setTimestamp(timeStamp);
					
					String strLine;
					String fileContent = "";
					while ((strLine = fileReader.readLine()) != null){
					     fileContent += strLine;
					}
					fileContent = fileContent.replace("/n", "=");
					computationRequestInfo.setFileContent(fileContent);

					outStream.writeObject(computationRequestInfo);

//					out.add(computationRequestInfo);
				}		
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}			
//			return out;
		}
	}

	class NodeCheckResourceThread implements Runnable {

		private final Socket socket;
		private int resourceLevel;
		private OtherNodeInfo otherNodeInfo;
		private boolean sendShareCommand;
		
		public NodeCheckResourceThread(Socket socket, int resourceLevel, OtherNodeInfo listIndex, boolean sendShareCommand) { 
			this.socket = socket; 
			this.resourceLevel = resourceLevel;
			this.otherNodeInfo = listIndex;
			this.sendShareCommand = sendShareCommand;
		}	
		
		@Override
		public void run() {
			System.out.println("run NodeCheckResourceThread");
			BufferedReader reader = null;
			PrintWriter writer = null;
			try {
				
				reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));				
				writer = new PrintWriter(socket.getOutputStream(), true);
				
				if(sendShareCommand) {
					writer.println("!share " + resourceLevel);
					String request = reader.readLine();		
					
					if(request.startsWith("!ok")) {					
						otherNodeInfo.setStatus("ok");		
					} else if(request.startsWith("!nok")){
						otherNodeInfo.setStatus("nok");		
					} 
				} else {
					if(otherNodeInfo.getStatus().equals("ok")) {					
						writer.println("!commit " + resourceLevel);		
					} else {
						writer.println("!rollback " + resourceLevel);
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
	}

}
