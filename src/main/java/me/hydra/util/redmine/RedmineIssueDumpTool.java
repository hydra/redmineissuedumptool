package me.hydra.util.redmine;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

public class RedmineIssueDumpTool {
	private static final String appVersionNumber = "v1.0.0";
	private static String defaultConnectionUrl = "jdbc:mysql:///redmine?user=redmine";
	private static String defaultConnectionDriver = "com.mysql.jdbc.Driver";
	private Connection con = null;
	private String connectionUrl = defaultConnectionUrl;
	private String connectionDriver = defaultConnectionDriver;
	private String targetDirectoryName = null;
	private boolean verbose = false;
	private boolean showStatistics = false;

	private int generatedFileCount = 0;
	private int issueCount = 0;

	private File targetDirectory;
	private String projectIdentifier;

	public static void main(String[] args) {

		RedmineIssueDumpTool switchTool = new RedmineIssueDumpTool();
		switchTool.run(args);
	}

	public void run(String[] args) {
		try {
			parseArgs(args);
			if (verbose || showStatistics) {
				showVersion();
			}
			inspectTargetDirectory();
			connect();
			processData();
			if (verbose || showStatistics) {
				showStatistics();
			}
		} finally {
			disconnect();
		}
	}

	protected static void showVersion() {
		System.out.println("RedmineIssueDumpTool " + appVersionNumber + " by Dominic Clifton (C) 2010.");
	}

	protected static void showUsage() {
		System.out.print("Usage: \n" +
				"--version             Show version information\n" +
				"--help                Show usage information\n" +
				"--driver              Set JDBC driver (default: is [" + defaultConnectionDriver + "]\n" +
				"--url                 Set JDBC connection string (default: is [" + defaultConnectionUrl + "]\n" +
				"--target              Set the target directory (no default, required argument)\n" +
				"--project             Set the project identifier (no default, required argument)\n" +
				"--statistics          Show statistics\n" +
				"--verbose             Generate verbose output\n");
	}

	//
	// Arguments
	//

	/**
	 * @todo allow specification of argument value type - Currently it's possible to go '--destination' which doesn't make sense as destination should not have a default argValue of "on"
	 */
	protected static boolean parseArg(String arg, StringBuffer argName, StringBuffer argValue) {
		String possibleArgs[] = {"statistics", "target", "project", "version", "help", "driver", "url", "verbose"};
		int index;
		for (index = 0; index < possibleArgs.length; index++) {
			String currentPossibleArg = possibleArgs[index];

			String expectedArg = "--" + currentPossibleArg;
			if (arg.compareTo(expectedArg) == 0) {
				argName.append(currentPossibleArg);
				argValue.append("on");
				return true;
			}

			if (arg.startsWith(expectedArg + "=")) {
				argName.append(currentPossibleArg);
				argValue.append(arg.substring(expectedArg.length() + 1));
				return true;
			}
		}
		return false;
	}

	protected void parseArgs(String [] args) {
		int index;
		for (index = 0; index < args.length; index++) {
			String currentArg = args[index];

			StringBuffer argValue = new StringBuffer();
			StringBuffer argName = new StringBuffer();

			if (!parseArg(currentArg, argName, argValue)) {
				showVersion();
				log(LogLevel.ERROR, "Invalid argument: [" + currentArg + "]", null);
				showUsage();
				System.exit(1);
			}

			if ("verbose".compareTo(argName.toString()) == 0 && "on".compareTo(argValue.toString()) == 0) {
				verbose = true;
				continue;
			}

			if ("statistics".compareTo(argName.toString()) == 0 && "on".compareTo(argValue.toString()) == 0) {
				showStatistics = true;
				continue;
			}

			if ("url".compareTo(argName.toString()) == 0) {
				connectionUrl = argValue.toString();
				continue;
			}

			if ("driver".compareTo(argName.toString()) == 0) {
				connectionDriver = argValue.toString();
				continue;
			}

			if ("target".compareTo(argName.toString()) == 0) {
				targetDirectoryName = argValue.toString();
				continue;
			}

			if ("project".compareTo(argName.toString()) == 0) {
				projectIdentifier = argValue.toString();
				continue;
			}

			if ("version".compareTo(argName.toString()) == 0) {
				showVersion();
				System.exit(0);
			}

			if ("help".compareTo(argName.toString()) == 0) {
				showVersion();
				showUsage();
				System.exit(0);
			}
		}

		ArrayList<String> errorMessages = new ArrayList<String>();
		if (targetDirectoryName == null || targetDirectoryName.length() == 0) {
			errorMessages.add("Target not set, see usage");
		}

		if (projectIdentifier == null || projectIdentifier.length() == 0) {
			errorMessages.add("Project not set, see usage");
		}

		if (!errorMessages.isEmpty()) {
			showVersion();
			for (String errorMessage : errorMessages) {
				log(LogLevel.FATAL, errorMessage, null);
			}
			showUsage();
			System.exit(1);
		}
	}

	//
	// Logging
	//

	protected static enum LogLevel {
		WARN ("WARNING"),
		ERROR ("ERROR"),
		FATAL ("FATAL");

		private final String name;

		LogLevel(String name) {
			this.name = name;
		}

		public String toString() {
			return this.name;
		}
	}

	protected static void log(LogLevel level, String message, Exception e) {
		System.out.println("*** " + level + " - "  + message);
		if (e != null) {
			System.out.println("Exception: " + e);
		}
	}

	protected static void die(String message, Exception e) {
		log(LogLevel.FATAL, message == null ? "Unrecoverable fatal error" : message, e);
		System.exit(1);
	}

	//
	// Main code
	//

	protected void inspectTargetDirectory() {
		targetDirectory = new File(targetDirectoryName);
		if (!targetDirectory.isDirectory()) {
			die("Target is not a directory", null);
		}
	}

	protected boolean generateFile(String fileName, String description) {

		File issueFile = new File(targetDirectory, fileName);
		try {
			FileWriter issueStream = new FileWriter(issueFile);
			BufferedWriter issueWriter = new BufferedWriter(issueStream);
			issueWriter.write(description);
			issueWriter.close();
			generatedFileCount++;
			return true;
		} catch (IOException e) {
			log(LogLevel.ERROR, "Unable to generate issue file [" + fileName + "]", e);
		}
		return false;
	}

	protected static String generateFileName(Long issueId, String trackerName, String subject) {
		String fileName = trackerName + " - " + subject.replaceAll("[\\&*/]", "_") + " (ISSUE " + issueId + ").txt";
		return fileName;
	}

	/**
	 * Processes a row from the result set
	 *
	 * Required fields:
	 * issue_id
	 * issue_name
	 * issue_details
	 *
	 *
	 * @param rs A suitable result set containing the required fields.
	 * @throws SQLException if there are problems getting field data in the expected format
	 */
	protected void processRow(ResultSet rs) throws SQLException {
		// retrieve and print the values for the current row
		Long issueId = rs.getLong("issue_id");
		String issueSubject = rs.getString("issue_subject");
		String issueDescription = rs.getString("issue_description");
		String trackerName = rs.getString("tracker_name");

		if (verbose) {
			System.out.println("Processing issue: [" + issueId + "], name: [" + issueSubject + "]");
		}

		String fileName = generateFileName(issueId, trackerName, issueSubject);
		generateFile(fileName, issueDescription);
	}

	//
	// Database
	//

	protected void processData() {
		if (verbose) {
			System.out.println("Processing data...");
		}

		try {
			PreparedStatement stmt = con.prepareStatement("SELECT" +
					"  i.id AS issue_id," +
					"  t.name AS tracker_name," +
					"  i.subject AS issue_subject," +
					"  i.description AS issue_description " +
					"FROM issues AS i" +
					"  INNER JOIN trackers AS t ON t.id = i.tracker_id" +
					"  INNER JOIN projects AS p on p.id = i.project_id " +
					"WHERE" +
					"  p.identifier = ? " +
					"ORDER BY" +
					"  i.subject");
			stmt.setString(1, projectIdentifier);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				issueCount++;
				processRow(rs);
			}


		} catch (SQLException e) {
			die(null, e);
		}


		if (verbose) {
			System.out.println("Processing data completed");
		}
	}

	protected void connect() {
		if (verbose) {
			System.out.println("Using connection url: [" + connectionUrl + "]");
			System.out.println("Using connection driver: [" + connectionDriver + "]");
			System.out.println("Connecting...");
		}

		try {
			Class.forName(connectionDriver).newInstance();
			con = DriverManager
					.getConnection(connectionUrl);

			if (!con.isClosed() && verbose) {
				System.out.println("Successfully connected to MySQL server");
			}
		} catch (Exception e) {
			die(null, e);
		}
	}

	protected void disconnect() {
		if (con == null) {
			return;
		}
		try {
			con.close();

			if (con.isClosed() && verbose) {
				System.out.println("Successfully disconnected from MySQL server");
			}
		} catch (SQLException e) {
			die(null, e);
		}
	}

	//
	// Statistics
	//

	protected void showStatistics() {
		System.out.println("Processed " + issueCount + " issue(s)");
		System.out.println("Generated/updated " + generatedFileCount + " issue file(s)");
	}
}
