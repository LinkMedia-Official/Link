package io.linkmc.server;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Properties;
import org.bukkit.configuration.file.YamlConfiguration;

/** One startup snapshot; secrets are never copied into JVM properties or logged. */
public final class LinkConfiguration {
    private static LinkConfiguration instance;
    private final String mode, node, jdbcUrl, username, password, redisUri, template;
    private final int templateVersion, worldCapacity, nodeCapacity, maxWorlds;
    private final String storage,endpoint,region,bucket,prefix,accessKey,secretKey;
    private final boolean retentionEnabled;
    private final int retentionHours,retentionInterval,retentionBatch,retentionBatches;
    private final int saveIntervalSeconds,saveWorkers,saveStallSeconds,saveTimeoutSeconds;
    public static synchronized LinkConfiguration get(){
        if(instance==null)instance=load(Path.of("link.yml"),System.getenv(),System.getProperties());
        return instance;
    }
    static LinkConfiguration load(Path file,Map<String,String> environment,Properties properties){
        try{
            try{Files.writeString(file,DEFAULTS,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}
            var yaml=new YamlConfiguration();yaml.load(file.toFile());
            var config=new LinkConfiguration(yaml,environment,properties);
            if(!yaml.contains("retention"))Files.writeString(file,"\n"+RETENTION_DEFAULTS,StandardOpenOption.APPEND);
            if(!yaml.contains("saving"))Files.writeString(file,"\n"+SAVING_DEFAULTS,StandardOpenOption.APPEND);
            if(yaml.contains("saving")&&(!yaml.contains("saving.stall-timeout-seconds")||!yaml.contains("saving.total-timeout-seconds"))){
                yaml.load(file.toFile());
                if(!yaml.contains("saving.stall-timeout-seconds"))yaml.set("saving.stall-timeout-seconds",120);
                if(!yaml.contains("saving.total-timeout-seconds"))yaml.set("saving.total-timeout-seconds",1800);
                yaml.save(file.toFile());
            }
            return config;
        }catch(IOException|org.bukkit.configuration.InvalidConfigurationException e){
            // Parser errors may quote the line containing a password.
            throw new IllegalStateException("Cannot read link.yml; check YAML syntax and file permissions");
        }
    }
    private LinkConfiguration(YamlConfiguration yaml,Map<String,String> env,Properties props){
        mode=props.getProperty("link.mode",string(yaml,"mode","disabled"));
        if(!java.util.Set.of("disabled","tools","journal","strict").contains(mode))throw new IllegalArgumentException("Invalid mode in link.yml or -Dlink.mode");
        node=env.getOrDefault("LINK_NODE_ID",string(yaml,"node.id","node-a"));
        jdbcUrl=env.getOrDefault("LINK_JDBC_URL",string(yaml,"database.jdbc-url","jdbc:postgresql://127.0.0.1:5432/link"));
        username=env.getOrDefault("LINK_DB_USER",string(yaml,"database.username","link"));
        password=env.getOrDefault("LINK_DB_PASSWORD",string(yaml,"database.password",""));
        Object enabled=yaml.get("redis.enabled",false);
        if(!(enabled instanceof Boolean))throw new IllegalArgumentException("redis.enabled must be true or false in link.yml");
        redisUri=env.getOrDefault("LINK_REDIS_URI",Boolean.TRUE.equals(enabled)?string(yaml,"redis.uri","redis://127.0.0.1:6379"):"");
        template=props.getProperty("link.spawn.template",string(yaml,"spawn.template","lobby"));
        templateVersion=positive(yaml,props,"spawn.version","link.spawn.version",1);
        worldCapacity=positive(yaml,props,"spawn.capacity","link.spawn.capacity",50);
        nodeCapacity=positive(yaml,props,"node.capacity","link.node.capacity",150);
        maxWorlds=positive(yaml,props,"spawn.max-worlds","link.spawn.max-worlds",3);
        storage=string(yaml,"storage.worlds","database");
        if(!java.util.Set.of("database","slimevault").contains(storage))throw new IllegalArgumentException("storage.worlds must be database or slimevault");
        endpoint=string(yaml,"storage.slimevault.endpoint","http://127.0.0.1:3900");
        region=string(yaml,"storage.slimevault.region","slimevault");
        bucket=string(yaml,"storage.slimevault.bucket","worlds");
        prefix=string(yaml,"storage.slimevault.prefix","link");
        accessKey=env.getOrDefault("LINK_S3_ACCESS_KEY",string(yaml,"storage.slimevault.access-key",""));
        secretKey=env.getOrDefault("LINK_S3_SECRET_KEY",string(yaml,"storage.slimevault.secret-key",""));
        Object retention=yaml.get("retention.enabled",true);
        if(!(retention instanceof Boolean))throw new IllegalArgumentException("retention.enabled must be true or false");
        retentionEnabled=(Boolean)retention;
        retentionHours=bounded(yaml,"retention.event-hours",24,1,87600);
        retentionInterval=bounded(yaml,"retention.interval-seconds",60,10,86400);
        retentionBatch=bounded(yaml,"retention.batch-operations",500,1,1000);
        retentionBatches=bounded(yaml,"retention.max-batches",10,1,100);
        saveIntervalSeconds=bounded(yaml,"saving.interval-seconds",30,1,3600);
        saveWorkers=bounded(yaml,"saving.workers",4,1,16);
        saveStallSeconds=bounded(yaml,"saving.stall-timeout-seconds",120,10,3600);
        saveTimeoutSeconds=bounded(yaml,"saving.total-timeout-seconds",1800,30,86400);
        if(saveTimeoutSeconds<saveStallSeconds)throw new IllegalArgumentException("saving.total-timeout-seconds must be >= stall timeout");
        io.linkmc.api.LinkApi.requireName(node);io.linkmc.api.LinkApi.requireName(template);
        if(mode.equals("journal")||mode.equals("tools")){
            if((!jdbcUrl.startsWith("jdbc:postgresql:")&&!jdbcUrl.startsWith("jdbc:mariadb:"))||username.isBlank()||password.isBlank())
                throw new IllegalArgumentException("Configure database.jdbc-url, username and password in link.yml (or LINK_JDBC_URL, LINK_DB_USER, LINK_DB_PASSWORD)");
        }
    }
    private static String string(YamlConfiguration yaml,String key,String fallback){
        Object value=yaml.get(key,fallback);
        if(!(value instanceof String text))throw new IllegalArgumentException(key+" must be a quoted string in link.yml");
        return text;
    }
    private static int positive(YamlConfiguration yaml,Properties properties,String key,String property,int fallback){
        try{int value=Integer.parseInt(properties.getProperty(property,String.valueOf(yaml.get(key,fallback))));if(value>0)return value;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException(key+" must be a positive integer in link.yml or -D"+property);
    }
    public String mode(){return mode;}
    private static int bounded(YamlConfiguration yaml,String key,int fallback,int minimum,int maximum){
        Object value=yaml.get(key,fallback);
        if(value instanceof Integer number&&number>=minimum&&number<=maximum)return number;
        throw new IllegalArgumentException(key+" must be an integer between "+minimum+" and "+maximum);
    }
    public boolean retentionEnabled(){return retentionEnabled;}
    public int saveIntervalSeconds(){return saveIntervalSeconds;}
    public int saveWorkers(){return saveWorkers;}
    public int saveStallSeconds(){return saveStallSeconds;}
    public int saveTimeoutSeconds(){return saveTimeoutSeconds;}
    public int retentionHours(){return retentionHours;}
    public int retentionInterval(){return retentionInterval;}
    public int retentionBatch(){return retentionBatch;}
    public int retentionBatches(){return retentionBatches;}
    public String node(){return node;}
    public String jdbcUrl(){return jdbcUrl;}
    public String username(){return username;}
    public String password(){return password;}
    public String redisUri(){return redisUri;}
    public String template(){return template;}
    public int templateVersion(){return templateVersion;}
    public int worldCapacity(){return worldCapacity;}
    public int nodeCapacity(){return nodeCapacity;}
    public int maxWorlds(){return maxWorlds;}
    public io.linkmc.core.WorldBlobs.Store worldStorage(){
        return storage.equals("database")?null:new io.linkmc.core.SlimeVaultStore(endpoint,region,bucket,prefix,accessKey,secretKey);
    }
    private static final String SAVING_DEFAULTS="""
        # Gameplay runs in memory. A crash can lose changes after the last completed save.
        # Main-thread snapshot, parallel encoding/uploads, ordered SQL commit.
        saving:
          interval-seconds: 30
          workers: 4
          stall-timeout-seconds: 120
          total-timeout-seconds: 1800
        """;
    private static final String RETENTION_DEFAULTS="""
        # Deletes legacy event history; new commits store current state only.
        # Current state and idempotency receipts are retained. Restart after editing.
        retention:
          enabled: true
          event-hours: 24
          interval-seconds: 60
          batch-operations: 500
          max-batches: 10
        """;
    private static final String DEFAULTS="""
        # Link backend configuration. Restart the server after editing.
        # Environment variables override database/node/Redis values.
        # Existing -Dlink.* options override mode and spawn/capacity values.
        # journal is experimental; strict is not available yet.
        mode: disabled
        node:
          id: node-a
          capacity: 150
        database:
          # Supports jdbc:postgresql://host:5432/link or jdbc:mariadb://host:3306/link
          jdbc-url: 'jdbc:postgresql://127.0.0.1:5432/link'
          username: 'link'
          password: ''
        storage:
          worlds: database
          slimevault:
            endpoint: 'http://127.0.0.1:3900'
            region: slimevault
            bucket: worlds
            prefix: link
            access-key: ''
            secret-key: ''
        redis:
          enabled: false
          uri: 'redis://127.0.0.1:6379'
        spawn:
          template: lobby
          version: 1
          capacity: 50
          max-worlds: 3
        """+RETENTION_DEFAULTS+SAVING_DEFAULTS;
}
