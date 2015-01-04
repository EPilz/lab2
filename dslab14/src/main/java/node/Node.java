package node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;

import model.ComputationRequestInfo;

import org.apache.commons.logging.LogFactory;
import org.bouncycastle.util.encoders.Base64;

import util.Config;
import util.Keys;
import cli.Command;
import cli.MyShell;

public class Node implements INodeCli, Runnable {
	
	private static final String ALGORITHM = "HmacSHA256";
	
	private Key secretKey;
	private Mac hMac;

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
	private boolean connectToCloudController = true;
	
	private Map<String, String> namesOfLogFiles = new HashMap();
	
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
		
		try {
			this.secretKey = Keys.readSecretKey(new File(config.getString("hmac.key")));
		} catch (IOException e) {
			shell.writeLine("cannot read the secret Key...");
			e.printStackTrace();
		}

		try {
			this.hMac = Mac.getInstance(ALGORITHM);
			hMac.init(secretKey);
		} catch (NoSuchAlgorithmException e) {
			shell.writeLine("algorithm for mac is invalid...");
		} catch (InvalidKeyException e) {
			shell.writeLine("cannot init mac with the secret Key...");
			e.printStackTrace();
		}
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
		twoPhaseCommit();
        
        if(connectToCloudController) {
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
        	shell.writeLine("Node: " + componentName + " cannot connect to CloudController! Enter !exit");
        }
	}
	
	private void twoPhaseCommit() {
		DatagramSocket socketHello = null;
		ExecutorService poolNodes = null;
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
			
				poolNodes = Executors.newCachedThreadPool();
				
				if(rmin <= resourceLevel) {
					for (int i = 1; i < lines.length - 1; i++) {
						String[] addr = lines[i].trim().split(":");
						OtherNodeInfo otherNodeInfo = new OtherNodeInfo("-", Integer.parseInt(addr[1]), InetAddress.getByName(addr[0]));
						nodeResourceStatusList.add(otherNodeInfo);
						poolNodes.execute(new NodeCheckResourceThread(resourceLevel, otherNodeInfo, true));											
					}	
					
					try {
						poolNodes.awaitTermination(1, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						System.out.println("exception await");
						connectToCloudController = false;
					}

					for (OtherNodeInfo otherNodeInfo : nodeResourceStatusList) {					
						poolNodes.execute(new NodeCheckResourceThread(resourceLevel, otherNodeInfo, false));
					}
				} else {
					connectToCloudController = false;
				}		
			} else {
				shell.writeLine("init request has the wrong format");
				connectToCloudController = false;
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
			if (poolNodes != null) {
				poolNodes.shutdown();
			}
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
		if(timerIsAlive != null) {
			timerIsAlive.cancel();
		}
		
		shell.close();		
		
		return "Shut down completed! Bye ..";
	}

	@Override
	public String history(int numberOfRequests) throws IOException {
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
		if(connectToCloudController) {
			return "The current resources are " + current_resources + "!";
		} else {
			return "Not connected to cloud controller!";
		}
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
								
				String request = "";

				while ((request = reader.readLine()) != null) {		
					if(request.contains("!compute")) {
						int index = request.indexOf("!compute");
						String encodeHash = request.substring(0, index).trim();		
						String term = request.substring(index, request.length()).trim();
						if(verifyHash(encodeHash, term)) {
							String result = "!result " + calculate(term.replaceAll("!compute", "").trim());
							hMac.update(result.getBytes());	
							writer.println(new String(Base64.encode(hMac.doFinal())) + " " + result);
						} else {
							shell.writeLine("Hash Code invalid from Term: " + term);
							String message = "!tampered " + term; 
							hMac.update(message.getBytes());						
							writer.println(new String(Base64.encode(hMac.doFinal())) + " " + message);
						}
					} else if(request.startsWith("!share")) {
						int resourceLevel = Integer.parseInt(request.trim().split("\\s+")[1]);
						new_resources = resourceLevel;
						
						if(rmin <= resourceLevel) {
							writer.println("!ok");
						} else {
							writer.println("!nok");
						}							
					} else if(request.startsWith("!commit")) {		
						System.out.println("commit");
						int resourceLevel = Integer.parseInt(request.trim().split("\\s+")[1]);
						
						if(resourceLevel == new_resources) {
							current_resources = new_resources;
							new_resources = -1;
						}
					} else if(request.startsWith("!rollback")) {
						System.out.println("rollback");
						new_resources = -1;		
					} else if(request.startsWith("!getLogs")) {
						shell.writeLine("getLogs command arrived");
						sendLogsToCloudController();						
					} else {	
						writer.println("not valid command");
					}
				}
			} catch (IOException e) {
//				der inputstream wirft immer eine IOException...
//				System.err.println("Error occurred while communicating with client...");
//				e.printStackTrace();
			} catch (NumberFormatException e) {
				e.printStackTrace();
			}  finally {
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
		
		private boolean verifyHash(String encodeHash, String term) {
			byte[] receivedHash = Base64.decode(encodeHash);
			
			hMac.update(term.getBytes());						
			byte[] computedHash = hMac.doFinal();

			return MessageDigest.isEqual(computedHash, receivedHash);
		}
		
		private void writeLog(String term, String result) {
			String timeStamp =  dateFormater.get().format(new Date());			
			String fileName = logDir + timeStamp + "_" + componentName + ".log";
			String fileContent = term + " = " + result;
			namesOfLogFiles.put(timeStamp, fileContent);
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
			ObjectOutputStream outStream = null;
			try {
				outStream = new ObjectOutputStream(socket.getOutputStream());
				for(Map.Entry<String, String> logfile : namesOfLogFiles.entrySet()){
					ComputationRequestInfo computationRequestInfo = new ComputationRequestInfo(logfile.getKey(), componentName, logfile.getValue());
					outStream.writeObject(computationRequestInfo);
					outStream.flush();
				}		
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}	finally {
				if(outStream != null) {
					try {
						outStream.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	class NodeCheckResourceThread implements Runnable {

		private Socket socket;
		private int resourceLevel;
		private OtherNodeInfo otherNodeInfo;
		private boolean sendShareCommand;
		
		public NodeCheckResourceThread(int resourceLevel, OtherNodeInfo listIndex, boolean sendShareCommand) { 
			this.resourceLevel = resourceLevel;
			this.otherNodeInfo = listIndex;
			this.sendShareCommand = sendShareCommand;
		}	
		
		@Override
		public void run() {
			BufferedReader reader = null;
			PrintWriter writer = null;
			try {
				socket = new Socket(otherNodeInfo.getInetAddress(), otherNodeInfo.getPort());
				
				reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));				
				writer = new PrintWriter(socket.getOutputStream(), true);
				
				if(sendShareCommand) {
					writer.println("!share " + resourceLevel);
					String request = reader.readLine();		
					
					if(request.startsWith("!ok")) {					
						otherNodeInfo.setStatus("ok");		
					} else if(request.startsWith("!nok")){
						otherNodeInfo.setStatus("nok");		
						connectToCloudController = false;
					} 
				} else {
					if(connectToCloudController) {					
						writer.println("!commit " + resourceLevel);		
					} else {
						writer.println("!rollback");
					}
				}					
			} catch (IOException e) {
				System.err.println("Error occurred while communicating with the other nodes!");
				connectToCloudController = false;
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
