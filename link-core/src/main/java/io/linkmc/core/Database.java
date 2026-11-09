package io.linkmc.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Objects;

/** One connection per transaction; connection factory may be backed by a pool. */
public final class Database implements AutoCloseable {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws Exception; }
    private final Connections connections;
    private Runnable close=()->{};
    private volatile java.util.function.Supplier<io.linkmc.api.LinkApi.Session> nodeOwner;
    /** Backend-only fencing. Startup acquisition uses an unbound connection pool. */
    public void nodeOwner(java.util.function.Supplier<io.linkmc.api.LinkApi.Session> owner){nodeOwner=Objects.requireNonNull(owner);}
    private WorldBlobs worldBlobs=new WorldBlobs(null);
    public WorldBlobs worldBlobs(){return worldBlobs;}
    public void worldStorage(WorldBlobs.Store store){worldBlobs=new WorldBlobs(store);}
    public Database(Connections connections) { this.connections = Objects.requireNonNull(connections); }
    public static Database pooled(String url,String user,String password){
        var config=new com.zaxxer.hikari.HikariConfig();
        // Velocity uses an isolated plugin class loader; JDBC service discovery
        // on an event thread may otherwise see only the proxy's parent loader.
        boolean maria=url.startsWith("jdbc:mariadb:");
        if(!maria&&!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("Use PostgreSQL or MariaDB JDBC URL");
        config.setDriverClassName(maria?"org.mariadb.jdbc.Driver":"org.postgresql.Driver");
        config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);
        config.setPoolName("Link-SQL");config.setMaximumPoolSize(8);config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);config.setValidationTimeout(2000);
        config.addDataSourceProperty("tcpKeepAlive","true");
        config.addDataSourceProperty("connectTimeout",maria?"5000":"5");
        config.addDataSourceProperty("socketTimeout",maria?"15000":"15");
        var pool=new com.zaxxer.hikari.HikariDataSource(config);
        var db=new Database(pool::getConnection);db.close=pool::close;return db;
    }
    @Override public void close(){close.run();}
    public <T> T transaction(Work<T> work) throws Exception {return transaction(work,false);}
    <T> T exclusiveNodeTransaction(Work<T> work)throws Exception{return transaction(work,true);}
    private <T> T transaction(Work<T> work,boolean exclusiveNode)throws Exception {
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                try (Statement s = c.createStatement()) {
                    if(maria(c)){
                        s.execute("SET SESSION innodb_lock_wait_timeout=5");
                        s.execute("SET SESSION max_statement_time=10");
                        s.execute("SET time_zone='+00:00'");
                    }else{
                    s.execute("SET LOCAL statement_timeout = '10s'");
                    s.execute("SET LOCAL lock_timeout = '5s'");
                    s.execute("SET LOCAL synchronous_commit = on");
                    }
                }
                var owner=nodeOwner;
                if(owner!=null){
                    var session=Objects.requireNonNull(owner.get(),"Missing backend owner");
                    if(exclusiveNode)lock(c,"node-startup:"+session.node());else sharedLock(c,"node-startup:"+session.node());
                    try(var s=sql(c,"SELECT 1 FROM link_sessions WHERE player=? AND node=? AND epoch=? AND state='ACTIVE' AND expires_at>clock_timestamp()",session.player(),session.node(),session.epoch());var r=s.executeQuery()){
                        if(!r.next())throw new ConflictException("Backend instance was replaced or its lease expired");
                    }
                }
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }
    public void migrate() throws Exception {
        try(var c=connections.open()){
            if(maria(c)){migrateMaria(c);return;}
        }
        try (var input = Database.class.getResourceAsStream("/link/schema.sql")) {
            if (input == null) throw new IOException("Missing Link schema");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            transaction(c -> {
                lock(c, "schema");
                try (Statement s = c.createStatement()) { s.execute(sql); }
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT max(version) FROM link_schema")) {
                    r.next();
                    if (r.getInt(1) != 1) throw new SQLException("Unsupported Link schema");
                }
                return null;
            });
        }
    }
    /** Stable transaction lock; collisions serialize unrelated work but cannot corrupt it. */
    public static void lock(Connection c, String key) throws SQLException {
        if(maria(c)){
            String hash;
            try{hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException e){throw new SQLException(e);}
            update(c,"INSERT INTO link_locks(id) VALUES (?) ON DUPLICATE KEY UPDATE id=VALUES(id)",hash);
            try(var s=sql(c,"SELECT id FROM link_locks WHERE id=? FOR UPDATE",hash);var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing transaction lock");}
            return;
        }
        try (PreparedStatement s = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            s.setString(1, "link:" + key); s.execute();
        }
    }
    /** Concurrent backend work may proceed together; a startup takeover waits for all of it. */
    public static void sharedLock(Connection c,String key)throws SQLException{
        if(maria(c)){
            String hash;
            try{hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException e){throw new SQLException(e);}
            boolean exists;
            try(var s=sql(c,"SELECT id FROM link_locks WHERE id=? LOCK IN SHARE MODE",hash);var r=s.executeQuery()){exists=r.next();}
            if(!exists)update(c,"INSERT IGNORE INTO link_locks(id) VALUES (?)",hash);
            try(var s=sql(c,"SELECT id FROM link_locks WHERE id=? LOCK IN SHARE MODE",hash);var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing shared lock");}
        }else try(var s=sql(c,"SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))","link:"+key)){s.execute();}
    }
    public static PreparedStatement sql(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement s = c.prepareStatement(maria(c)?MariaDialect.translate(sql):sql);
        try {
            for (int i = 0; i < args.length; i++) {
                if(args[i] instanceof Timestamp timestamp)s.setTimestamp(i+1,timestamp,utc());
                else s.setObject(i + 1, args[i]);
            }
            return s;
        } catch (SQLException e) { s.close(); throw e; }
    }
    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = sql(c, sql, args)) { return s.executeUpdate(); }
    }
    public static boolean maria(Connection c)throws SQLException{return c.getMetaData().getDatabaseProductName().equals("MariaDB");}
    private static java.util.Calendar utc(){return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));}
    public static java.time.Instant instant(ResultSet r,String column)throws SQLException{return r.getTimestamp(column,utc()).toInstant();}
    public static java.time.Instant instant(ResultSet r,int column)throws SQLException{return r.getTimestamp(column,utc()).toInstant();}
    private void migrateMaria(Connection c)throws Exception{
        try(var s=c.createStatement();var r=s.executeQuery("SELECT @@innodb_flush_log_at_trx_commit,@@log_bin,@@sync_binlog")){
            if(!r.next()||r.getInt(1)!=1||(r.getBoolean(2)&&r.getInt(3)!=1))throw new SQLException("MariaDB requires innodb_flush_log_at_trx_commit=1 and sync_binlog=1 when binlog is enabled");
        }
        // MariaDB DDL implicitly commits: use a connection lock, not a transaction lock.
        try(var s=c.createStatement();var r=s.executeQuery("SELECT GET_LOCK('link-schema-v1',10)")){
            if(!r.next()||r.getInt(1)!=1)throw new SQLException("Schema lock timeout");
        }
        try{
            try(var input=Database.class.getResourceAsStream("/link/schema-mariadb.sql")){
                if(input==null)throw new IOException("Missing MariaDB schema");
                for(String statement:new String(input.readAllBytes(),StandardCharsets.UTF_8).split(";"))
                    if(!statement.isBlank())try(var s=c.createStatement()){s.execute(statement);}
            }
            try(var s=c.createStatement();var r=s.executeQuery("SELECT max(version) FROM link_schema")){
                if(!r.next()||r.getInt(1)!=1)throw new SQLException("Unsupported Link schema");
            }
        }finally{try(var s=c.createStatement()){s.execute("DO RELEASE_LOCK('link-schema-v1')");}}
    }
}
