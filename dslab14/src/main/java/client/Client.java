package client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import javax.crypto.Cipher;

import org.bouncycastle.util.encoders.Base64;

import cli.Command;
import cli.MyShell;
import util.Base64Channel;
import util.Channel;
import util.Channel.NotConnectedException;
import util.Config;
import util.Keys;
import util.SecureChannel;
import util.SecurityUtil;

public class Client implements IClientCli, Runnable {
	private final String B64 = "a-zA-Z0-9/+";
	
	private String componentName;
	private Config config;
	
	private MyShell shell;
	private Channel channelToCC;

	private String loggedInUser;

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

		shell = new MyShell(componentName, userRequestStream, userResponseStream);
		shell.register(this);
		
	}

	@Override
	public void run() {
			shell.writeLine("Client " + componentName + " is started!");
		
			new Thread(shell).start();	
	}
	
	
	private String sendToCloudController(String message) {
		if(channelToCC != null && channelToCC.isConnected()) {
			try {
				channelToCC.sendMessage(message);
				String response = channelToCC.readMessage();
				if(response == null) {
					return "Error: CloudController unreachable";
				}
				return response;
			} catch (NotConnectedException e) {
				return "Error: not connected";
			} catch (Exception e) {
				return "Error: while read from CloudController";
			}
		} else {
			return "Error: not connected to cloud controller";
		}
	}


	@Override
	@Deprecated
	public String login(String username, String password) throws IOException {		
		return sendToCloudController("login " + username + " " + password);							
	}

	@Override
	@Command
	public String logout() throws IOException {
		String toPrint = sendToCloudController("logout");
		channelToCC.close();
		loggedInUser = null;
		return toPrint;				
	}

	@Override
	@Command
	public String credits() throws IOException {
		return sendToCloudController("credits");
	}

	@Override
	@Command
	public String buy(long credits) throws IOException {
		return sendToCloudController("buy " + credits);
	}

	@Override
	@Command
	public String list() throws IOException {
		return sendToCloudController("list");
	}

	@Override
	@Command
	public String compute(String term) throws IOException {
		return sendToCloudController("compute " + term);
	}

	@Override
	@Command
	public String exit() throws IOException {	
		if (channelToCC != null && channelToCC.isConnected()) {	
			try {
				logout();
			} catch(Exception ex) { }
			channelToCC.close();			
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
	@Command
	public String authenticate(String username) throws IOException {
		//Check if already connected
		if(channelToCC != null && channelToCC.isConnected())
			return "You are already connected and authenticated as " + loggedInUser;
		
		File ownPrivateKey = new File(config.getString("keys.dir") + "/" + username + ".pem");
		if(!ownPrivateKey.exists())
		{
			return "Error: An username " + username + " is unknown!";
		}
		
		//Create Channel
		channelToCC = new Base64Channel();
		boolean connected = channelToCC.connect(config.getString("controller.host"), config.getInt("controller.tcp.port"));
		if(!connected)
			return "Error: cannot connect to cloud controller";
		
		//Send challenge to controller
		byte[] challenge = SecurityUtil.createBase64Challenge();
		boolean ok = sendChallenge(username, challenge);
		if(!ok)
		{
			channelToCC.close();
			return "Error: cannot connect to cloud controller";
		}
		ok = readAndProcessAnswer(username, challenge, ownPrivateKey);
		if(!ok)
		{
			channelToCC.close();
			return "Error: cannot connect to cloud controller";
		}
		//Return result
		loggedInUser = username;
		return "Successfully authenticated";
	}
	
	private boolean sendChallenge(String username, byte[] challenge)
	{
		File publicKeyOfController = new File(config.getString("controller.key"));
		
		//Create unencrypted message
		String unencryptedMessage = String.format("!authenticate %s %s", username, new String(challenge));
		
		//Encrypt message using public key of host
		try {
			PublicKey publicKey = Keys.readPublicPEM(publicKeyOfController);
			Cipher cipher = Cipher.getInstance("RSA/NONE/OAEPWithSHA256AndMGF1Padding");
			cipher.init(Cipher.ENCRYPT_MODE, publicKey);
			byte[] encryptedMessage = cipher.doFinal(unencryptedMessage.getBytes());
			channelToCC.sendMessage(encryptedMessage);
		}
		catch (Exception e) { 
			e.printStackTrace();  //TODO: delete
			return false;
		}
		return true;
	}

	private boolean readAndProcessAnswer(String username, byte[] givenChallenge, File ownPrivateKey)
	{
		try{
			//Read message from client
			byte[] encryptedMessage = channelToCC.readByteMessage();
			
			//Decrypt it
			PrivateKey privateKey = Keys.readPrivatePEM(ownPrivateKey);
			Cipher privateCipher = Cipher.getInstance("RSA/NONE/OAEPWithSHA256AndMGF1Padding");
			privateCipher.init(Cipher.DECRYPT_MODE, privateKey);
			byte[] decryptedMessage = privateCipher.doFinal(encryptedMessage);
			String message = new String(decryptedMessage);
			assert message.matches("!ok ["+B64+"]{43}= ["+B64+"]{43}= ["+B64+"]{43}= ["+B64+"]{22}==") : "2nd message";
			
			//Split to parts
			String[] messageParts = message.split(" ");
			byte[] userChallengeAnswer = messageParts[1].getBytes();
			String controllerChallenge = messageParts[2];
			byte[] secretKey = Base64.decode(messageParts[3].getBytes());
			byte[] ivParam = Base64.decode(messageParts[4].getBytes());
			
			//Check the sent user challenge
			if(!Arrays.equals(givenChallenge, userChallengeAnswer))
				return false;
			//Create SecureChannel with secret key and ivParam
			channelToCC = new SecureChannel((Base64Channel) channelToCC, secretKey, ivParam);
			
			//Send controller challenge back over secure channel
			channelToCC.sendMessage(controllerChallenge);
		}
		catch(Exception e)
		{
			e.printStackTrace(); //TODO: delete
			return false;
		}
		return true;
	}
}
