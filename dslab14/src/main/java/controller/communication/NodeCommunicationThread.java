package controller.communication;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import cli.MyShell;
import controller.CloudController;
import controller.info.NodeInfo;

/**
 * Thread to listen for incoming data packets on the given socket.
 */
public class NodeCommunicationThread extends Thread {

	private CloudController cloudController;
	private DatagramSocket datagramSocket;
	private Map<Integer, NodeInfo> nodeInfos;
	private int timeToOffline;
	private int checkPeriod;
	private int rmax;
	

	public NodeCommunicationThread(CloudController cloudController, DatagramSocket datagramSocket, int timeToOffline, int checkPeriod, int rmax, MyShell shell) {
		this.cloudController = cloudController;
		this.datagramSocket = datagramSocket;
		this.timeToOffline = timeToOffline;
		this.checkPeriod = checkPeriod;		
		this.rmax = rmax;
		
		this.nodeInfos = new ConcurrentHashMap<>();			
	}
	
	public List<NodeInfo> nodeInfos() {
		List<NodeInfo> infos = new ArrayList<>(nodeInfos.values());
		
		Collections.sort(infos, new Comparator<NodeInfo>() {
		    public int compare(NodeInfo one, NodeInfo other) {
		        int compareValue = one.getIp().compareTo(other.getIp());
		        if(compareValue == 0) {
		        	return Integer.compare(one.getTcpPort(), other.getTcpPort());
		        } else {
		        	return compareValue;
		        }
		        
		    }
		}); 
		return infos;
	}
	
	public List<NodeInfo> getNodeInfosWithAvailableOperation(Character operation) {
		List<NodeInfo> available = new ArrayList<>();
		
		for(NodeInfo nodeInfo : nodeInfos.values()) {
			if(nodeInfo.getStatus().equals(NodeInfo.Status.ONLINE) &&
					nodeInfo.getOperators().contains(operation)) {
				available.add(nodeInfo);
			}
		}
		return available;
	}

	public void run() {
		TimerTask action = new TimerTask() {				
            public void run() {
            	for(NodeInfo nodeInfo : nodeInfos.values()) {
            		if(nodeInfo.getTimeout() >= timeToOffline) {
            			nodeInfo.setStatus(NodeInfo.Status.OFFLINE);
            		}
            		nodeInfo.setTimeout(nodeInfo.getTimeout() + checkPeriod);
            	}
            }
        };
        
        Timer timer = new Timer();        
        timer.schedule(action, 10, checkPeriod);
			
		byte[] buffer;
		DatagramPacket packet;
		
		try {
			while (!cloudController.isStop()) {
				buffer = new byte[1024];
				packet = new DatagramPacket(buffer, buffer.length);

				datagramSocket.receive(packet);

				String request = new String(packet.getData());
				
				String[] array = request.split("\\s+");
				String ip = packet.getAddress().getHostAddress();
				
				
				if(request.startsWith("!alive")) {
					int tcpPort = Integer.valueOf(array[1]);
					String operators = array[2].trim();
					if(! nodeInfos.containsKey(tcpPort)) {
						nodeInfos.put(tcpPort,  new NodeInfo(ip, tcpPort, NodeInfo.Status.ONLINE, 0, operators));
					} else {
						nodeInfos.get(tcpPort).setTimeout(0);
						nodeInfos.get(tcpPort).setStatus(NodeInfo.Status.ONLINE);
						nodeInfos.get(tcpPort).addOperators(operators);
					}					
				} else if(request.startsWith("!hello")) {
					sendInitToNode(packet.getSocketAddress());
				}
			}
		} catch (IOException e) { 
		} finally {
			if (datagramSocket != null && !datagramSocket.isClosed()) {
				datagramSocket.close();
			}
			timer.cancel();
		}
	}
	
	public void sendInitToNode(SocketAddress socketAddress) {
		DatagramSocket socket = null;
		
		try {
			socket = new DatagramSocket();
			
			String message = "!init";
			
			for (NodeInfo nodeInfo : nodeInfos.values()) {
				if(nodeInfo.getStatus().equals(NodeInfo.Status.ONLINE)) {
					message += "\n" + nodeInfo.getIp() + ":" + nodeInfo.getTcpPort();
				}
			}
			message += "\n" + rmax;
			
			byte[] buffer = message.getBytes();
			
			DatagramPacket packet = new DatagramPacket(buffer, buffer.length, socketAddress);
			
			socket.send(packet);
		} catch (UnknownHostException e) {
			System.out.println("Cannot connect to host: " + e.getMessage());
		} catch (IOException e) {
			System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
		} finally {
			if (socket != null && !socket.isClosed()) {
				socket.close();
			}

		}
	}
}
