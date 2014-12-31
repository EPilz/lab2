package cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import cli.Shell.ShellCommandDefinition;

public class MyShell extends Shell {

	public MyShell(String name, InputStream in, OutputStream out) {
		super(name, in, out);
	}

	@Override
	public void writeLine(String line)  {
		try {
			super.writeLine(line);
		} catch(IOException ex) {
			System.err.println(ex.getClass().getName() + ": "
					+ ex.getMessage());
		}
	}

	@Override
	public Object invoke(String cmd) throws Throwable {
		try {
			return super.invoke(cmd);
		} catch(IllegalArgumentException ex) {
			int pos = cmd.indexOf(' ');
			String cmdName = pos >= 0 ? cmd.substring(0, pos) : cmd;
            return String.format("Command '%s' not registered with this parameter.", cmdName);
		}
	}
		
}
