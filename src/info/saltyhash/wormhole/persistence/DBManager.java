package info.saltyhash.wormhole.persistence;

import java.io.File;
import java.sql.*;
import java.util.logging.Logger;

/** Manages the database. */
@SuppressWarnings({"WeakerAccess", "SameParameterValue"})
public final class DBManager {
    private static Connection connection;
    private static File dbFile;
    private static Logger logger;
    
    private DBManager() {}
    
    @SuppressWarnings("unused")
    public static void setup(File dbFile) {
        DBManager.setup(dbFile, null);
    }
    
    public static void setup(File dbFile, Logger logger) {
        closeConnection();
        DBManager.dbFile = dbFile;
        DBManager.logger = logger;
    }
    
    /**
     * Returns a connection to the database, reusing previous connection if possible.
     * The connection is configured with foreign keys ON.  Logs errors.
     * @return Database connection, or null on error.
     */
    static Connection getConnection() {
        if (dbFile == null) throw new NullPointerException("DBManager.dbFile must not be null");
        
        // Create connection if necessary
        try {
            if (connection == null || connection.isClosed()) {
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection(
                        "jdbc:sqlite:"+dbFile.getAbsolutePath());
            }
        } catch (ClassNotFoundException | SQLException e) {
            logSevere("Failed to connect to database");
            logSevere(e.toString());
            return (connection = null);
        }
        
        // Turn on foreign keys
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON;");
        } catch (SQLException e) {
            logSevere("Failed to enable database foreign keys");
            logSevere(e.toString());
            DBManager.closeConnection();
            return (connection = null);
        }
        
        return connection;
    }
    
    /**
     * If a database connection exists, then its changes are committed
     * and the connection is closed.  Logs errors.
     * @return true on success; false on error.
     */
    @SuppressWarnings("UnusedReturnValue")
    public static boolean closeConnection() {
        boolean success = true;
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    try {
                        if (!connection.getAutoCommit()) connection.commit();
                    } catch (SQLException e) {
                        logSevere("Failed to commit changes to database before closing");
                        success = false;
                    }
                    connection.close();
                }
            } catch (SQLException e) {
                logWarning("Failed to close database connection");
                success = false;
            }
            connection = null;
        }
        return success;
    }
    
    static void logInfo(String msg) {
        if (logger != null) logger.info(msg);
    }
    static void logWarning(String msg) {
        if (logger != null) logger.warning(msg);
    }
    static void logSevere(String msg) {
        if (logger != null) logger.severe(msg);
    }
    
    /** Returns the current database version, or -1 on error. */
    private static int getDatabaseVersion() {
        try (Statement s = getConnection().createStatement()) {
            // Try to get the 1st row
            ResultSet results = s.executeQuery(
                    "SELECT version FROM schema_version LIMIT 1;");
            
            // Results are empty?
            if (!results.isBeforeFirst()) {
                // Either table DNE or row DNE
                return -1;
            }
            return results.getInt("version");
        } catch (SQLException e) {
            // File or table DNE
            return -1;
        }
    }
    
    /**
     * Sets the database version.  Logs errors.
     * @param  version The version number to set the database to.
     * @return true on success; false on error.
     */
    private static boolean setDatabaseVersion(int version) {
        try (Statement s = getConnection().createStatement()) {
            s.executeUpdate("DELETE FROM schema_version;");
            s.executeUpdate("INSERT INTO schema_version\n"+
                    "(`version`) VALUES ("+version+");"
            );
        } catch (SQLException e) {
            logSevere("Failed to set database version");
            logSevere(e.toString());
            return false;
        }
        
        return true;    // Success
    }
    
    /**
     * Migrates the database to the latest version.
     * @return true on success; false on failure.
     */
    public static boolean migrate() {
        switch (getDatabaseVersion()) {
            case -1: if (!migration0()) return false;
            default: break;
        }
        return true;    // Success
    }
    
    /* <Migrations> */
    
    /**
     * Migrates to database version from previous.  Logs errors.
     * @return true on success; false on error.
     */
    private static boolean migration0() {
        logInfo("Creating database v0...");
        
        Connection conn = getConnection();
        
        // Create table 'schema_version'
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (\n" +
                    "  `version` INTEGER);"
            );
        } catch (SQLException e) {
            logSevere("Failed to create table schema_version");
            e.printStackTrace();
            return false;
        }
        
        // Create table 'players'
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS players (\n" +
                    "  `uuid`     CHAR(36) PRIMARY KEY,\n" +
                    "  `username` VARCHAR(16));"
            );
        } catch (SQLException e) {
            logSevere("Failed to create table 'players'");
            e.printStackTrace();
            return false;
        }
        
        // Create table 'jumps'
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS jumps (\n" +
                    "  `id`          INTEGER PRIMARY KEY,\n" +
                    "  `player_uuid` CHAR(36) REFERENCES players(`uuid`)\n" +
                    "                ON DELETE CASCADE ON UPDATE CASCADE,\n" +
                    "  `name`        TEXT,\n" +
                    "  `world_uuid`  CHAR(36),\n" +
                    "  `x` REAL, `y` REAL, `z` REAL, `yaw` REAL,\n" +
                    "  UNIQUE (`player_uuid`, `name`));"
            );
        } catch (SQLException e) {
            logSevere("Failed to create table 'jumps'");
            e.printStackTrace();
            return false;
        }
        
        // Create table 'signs'
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS signs (\n" +
                    "  `world_uuid` CHAR(36),\n" +
                    "  `x` INTEGER, `y` INTEGER, `z` INTEGER,\n" +
                    "  `jump_id` INTEGER REFERENCES jumps(`id`)\n" +
                    "            ON DELETE CASCADE ON UPDATE CASCADE,\n" +
                    "  PRIMARY KEY (`world_uuid`, `x`, `y`, `z`));"
            );
        } catch (SQLException e) {
            logSevere("Failed to create table 'signs'");
            e.printStackTrace();
            return false;
        }
        
        if (!setDatabaseVersion(0)) {
            logSevere("Failed to set database version to 0");
            return false;
        }
        
        logInfo("Done.");
        return true;
    }
    
    /* </Migrations> */
}