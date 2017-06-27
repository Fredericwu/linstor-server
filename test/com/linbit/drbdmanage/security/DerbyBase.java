package com.linbit.drbdmanage.security;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import com.linbit.InvalidNameException;
import com.linbit.drbdmanage.DatabaseSetter;
import com.linbit.drbdmanage.NetInterfaceName;
import com.linbit.drbdmanage.Node.NodeType;
import com.linbit.drbdmanage.NodeName;
import com.linbit.drbdmanage.ResourceName;
import com.linbit.drbdmanage.StorPoolName;
import com.linbit.drbdmanage.VolumeNumber;
import com.linbit.drbdmanage.dbcp.DbConnectionPool;
import com.linbit.drbdmanage.dbdrivers.DerbyDriver;
import com.linbit.drbdmanage.logging.StdErrorReporter;
import com.linbit.utils.UuidUtils;

public abstract class DerbyBase implements DerbyConstants
{
    private static final String DB_FOLDER = "/tmp/drbdmanageNG/tests/database/derby";

    private static final String DB_URL = "jdbc:derby:directory:" + DB_FOLDER;
    private static final String DB_USER = "drbdmanage";
    private static final String DB_PASSWORD = "linbit";
    private static final Properties DB_PROPS = new Properties();

    private List<Statement> statements = new ArrayList<>();
    private static Connection con;
    private static DbConnectionPool dbConnPool;
    private static List<Connection> connections = new ArrayList<>();

    protected static final AccessContext sysCtx;
    private static boolean initialized = false;
    private static DbDerbyPersistence secureDbDriver;

    static
    {
        PrivilegeSet sysPrivs = new PrivilegeSet(Privilege.PRIV_SYS_ALL);

        sysCtx = new AccessContext(
            Identity.SYSTEM_ID,
            Role.SYSTEM_ROLE,
            SecurityType.SYSTEM_TYPE,
            sysPrivs
        );
        try
        {
            sysCtx.privEffective.enablePrivileges(Privilege.PRIV_SYS_ALL);
        }
        catch (AccessDeniedException iAmNotRootExc)
        {
            throw new RuntimeException(iAmNotRootExc);
        }
    }

    public DerbyBase() throws SQLException
    {
        if (!initialized)
        {
            createTables();
            insertDefaults();

            try
            {
                Identity.load(dbConnPool, secureDbDriver);
                SecurityType.load(dbConnPool, secureDbDriver);
                Role.load(dbConnPool, secureDbDriver);
            }
            catch (SQLException | InvalidNameException exc)
            {
                throw new RuntimeException(exc);
            }


            initialized = true;
        }
    }

    @BeforeClass
    public static void setUpBeforeClass() throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException
    {
        File dbFolder = new File(DB_FOLDER);
        if (dbFolder.exists())
        {
            deleteFolder(dbFolder);
        }

        // load the clientDriver...
        DB_PROPS.setProperty("create", "true");
        DB_PROPS.setProperty("user", DB_USER);
        DB_PROPS.setProperty("password", DB_PASSWORD);

        dbConnPool = new DbConnectionPool();
        dbConnPool.initializeDataSource(DB_URL, DB_PROPS);

        con = dbConnPool.getConnection();

        secureDbDriver = new DbDerbyPersistence(sysCtx);
        DerbyDriver persistenceDbDriver = new DerbyDriver(
            new StdErrorReporter("TESTING"),
            sysCtx
        );
        DatabaseSetter.setDatabaseClasses(
            secureDbDriver,
            persistenceDbDriver
        );
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception
    {
//        dropTables();
        File dbFolder = new File(DB_FOLDER);
        deleteFolder(dbFolder);

        con.close();
        dbConnPool.shutdown();
    }

    private static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if(files!=null) { //some JVMs return null for empty dirs
            for(File f: files) {
                if(f.isDirectory()) {
                    deleteFolder(f);
                } else {
                    f.delete();
                }
            }
        }
        folder.delete();
    }

    @Before
    public void setUp() throws SQLException
    {
        truncateTables();
        insertDefaults();
    }

    @After
    public void tearDown() throws SQLException
    {
        for (Statement statement : statements)
        {
            statement.close();
        }
        for (Connection connection : connections)
        {
            connection.close();
        }
        connections.clear();
    }

    protected static Connection getConnection() throws SQLException
    {
        Connection connection = dbConnPool.getConnection();
        connection.setAutoCommit(false);
        connections.add(connection);
        return connection;
    }

    protected void add(Statement stmt)
    {
        statements.add(stmt);
    }

    private void createTables() throws SQLException
    {
        for (int idx = 0; idx < CREATE_TABLES.length; ++idx)
        {
            createTable(con, true, idx);
        }
        con.commit();
    }

    private void insertDefaults() throws SQLException
    {
        for (String insert : INSERT_DEFAULT_VALUES)
        {
            try (PreparedStatement stmt = con.prepareStatement(insert))
            {
                stmt.executeUpdate();
            }
        }
        con.commit();
    }

    private static void dropTables() throws SQLException
    {
        for (int idx = 0; idx < DROP_TABLES.length; ++idx)
        {
            dropTable(con, idx);
        }
    }

    private void truncateTables() throws SQLException
    {
        for (String sql : TRUNCATE_TABLES)
        {
            PreparedStatement stmt = con.prepareStatement(sql);
            stmt.executeUpdate();
            stmt.close();
        }
    }

    private void createTable(Connection connection, boolean dropIfExists, int idx) throws SQLException
    {
        try
        {
//            System.out.print("creating... " + CREATE_TABLES[idx]);
            try (PreparedStatement stmt = connection.prepareStatement(CREATE_TABLES[idx]))
            {
                stmt.executeUpdate();
//                System.out.println("... done");
            }
        }
        catch (SQLException sqlExc)
        {
            String sqlState = sqlExc.getSQLState();
            if ("X0Y32".equals(sqlState)) // table already exists
            {
                if (dropIfExists)
                {
//                    System.out.print("exists, ");
                    dropTable(connection, DROP_TABLES.length - 1 - idx);
                    createTable(connection, false, idx);
                }
                else
                {
                    System.out.println(CREATE_TABLES[idx]);
                    throw sqlExc;
                }
            }
            else
            {
                System.out.println(CREATE_TABLES[idx]);
                throw sqlExc;
            }
        }
        connection.commit();
    }

    private static void dropTable(Connection connection, int idx) throws SQLException
    {
        try (PreparedStatement stmt = connection.prepareStatement(DROP_TABLES[idx]))
        {
//            System.out.print("dropping... " + DROP_TABLES[idx]);
            stmt.executeUpdate();
//            System.out.println("... done");
        }
        catch (SQLException sqlExc)
        {
            if ("42Y55".equals(sqlExc.getSQLState()))
            {
                // table does not exists.... yay - ignore
            }
            else
            {
                System.out.println(DROP_TABLES[idx]);
                throw sqlExc;
            }
        }
        connection.commit();
    }



    protected void insertObjProt(Connection dbCon, String objPath, AccessContext accCtx) throws SQLException
    {
        PreparedStatement stmt = dbCon.prepareStatement(INSERT_SEC_OBJECT_PROTECTION);
        stmt.setString(1, objPath);
        stmt.setString(2, accCtx.subjectId.name.value);
        stmt.setString(3, accCtx.subjectRole.name.value);
        stmt.setString(4, accCtx.subjectDomain.name.value);
        stmt.executeUpdate();
    }

    protected void insertNode(Connection dbCon, NodeName nodeName, long flags, NodeType... types)throws SQLException
    {
        long typeMask = 0;
        for (NodeType type : types)
        {
            typeMask |= type.getFlagValue();
        }

        PreparedStatement stmt = dbCon.prepareStatement(INSERT_NODES);
        stmt.setString(1, nodeName.value);
        stmt.setString(2, nodeName.displayValue);
        stmt.setLong(3, flags);
        stmt.setLong(4, typeMask);
        stmt.setString(5, ObjectProtection.buildPath(nodeName));
        stmt.executeUpdate();
    }

    protected void insertNetInterface(
        Connection dbCon,
        java.util.UUID uuid,
        NodeName nodeName,
        NetInterfaceName netName,
        String inetAddr,
        String transportType
    )
        throws SQLException
    {
        PreparedStatement stmt = dbCon.prepareStatement(INSERT_NODE_NET_INTERFACES);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, nodeName.value);
        stmt.setString(3, netName.value);
        stmt.setString(4, netName.displayValue);
        stmt.setString(5, inetAddr);
        stmt.setString(6, transportType);
        stmt.executeUpdate();
    }

    protected void insertResDfn(Connection dbCon, ResourceName resName) throws SQLException
    {
        PreparedStatement stmt = dbCon.prepareStatement(INSERT_RESOURCE_DEFINITIONS);
        stmt.setString(1, resName.value);
        stmt.setString(2, resName.displayValue);
        stmt.executeUpdate();
    }

    protected void insertRes(Connection dbCon, NodeName nodeName, ResourceName resName) throws SQLException
    {
        PreparedStatement stmt = dbCon.prepareStatement(INSERT_RESOURCE_DEFINITIONS);
        stmt.setString(1, nodeName.value);
        stmt.setString(2, resName.value);
        stmt.executeUpdate();
    }

    protected void insertVolDfn(Connection dbCon, ResourceName resName, VolumeNumber volId, long volSize, int minorNr) throws SQLException
    {
        PreparedStatement stmt = dbCon.prepareStatement(INSERT_VOLUME_DEFINITIONS);
        stmt.setString(1, resName.value);
        stmt.setInt(2, volId.value);
        stmt.setLong(3, volSize);
        stmt.setInt(4, minorNr);
        stmt.executeUpdate();
    }

    protected void insertStorPoolDfn(Connection dbCon, java.util.UUID uuid, StorPoolName poolName) throws SQLException
    {
        PreparedStatement stmt = dbCon.prepareStatement(INSERT_STOR_POOL_DEFINITIONS);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, poolName.value);
        stmt.setString(3, poolName.displayValue);
        stmt.executeUpdate();
    }

    protected void insertStorPool(Connection dbCon, java.util.UUID uuid, NodeName nodeName, StorPoolName poolName, String driver) throws SQLException
    {
        PreparedStatement stmt = dbCon.prepareStatement(INSERT_NODE_STOR_POOL);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, nodeName.value);
        stmt.setString(3, poolName.value);
        stmt.setString(4, driver);
        stmt.executeUpdate();
    }

    protected void insertProp(Connection dbCon, String instance, String key, String value) throws SQLException
    {
        PreparedStatement stmt = dbCon.prepareStatement(INSERT_PROPS_CONTAINERS);
        stmt.setString(1, instance.toUpperCase());
        stmt.setString(2, key);
        stmt.setString(3, value);
        stmt.executeUpdate();
    }

}
