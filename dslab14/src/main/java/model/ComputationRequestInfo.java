package model;

import java.io.Serializable;

/**
 * Please note that this class is not needed for Lab 1, but will later be used
 * in Lab 2. Hence, you do not have to implement it for the first submission.
 */
public class ComputationRequestInfo implements Serializable {

	private String timestamp;
	private String nodeName;
	private String fileContent;

	
	public ComputationRequestInfo(String timestamp, String nodeName,
			String fileContent) {
		super();
		this.timestamp = timestamp;
		this.nodeName = nodeName;
		this.fileContent = fileContent;
	}

	public ComputationRequestInfo() {
	}

	public String getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(String timestamp) {
		this.timestamp = timestamp;
	}

	public String getNodeName() {
		return nodeName;
	}

	public void setNodeName(String nodeName) {
		this.nodeName = nodeName;
	}

	public String getFileContent() {
		return fileContent;
	}

	public void setFileContent(String fileContent) {
		this.fileContent = fileContent;
	}

	@Override
	public String toString() {
		return timestamp + " [" + nodeName.toLowerCase() + "]: " + fileContent;
	}
}
