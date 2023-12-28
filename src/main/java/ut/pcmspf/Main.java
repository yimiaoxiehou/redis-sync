package ut.pcmspf;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.io.watch.SimpleWatcher;
import cn.hutool.core.io.watch.WatchMonitor;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.moilioncircle.redis.replicator.Configuration;
import com.moilioncircle.redis.replicator.RedisReplicator;
import com.moilioncircle.redis.replicator.RedisURI;
import com.moilioncircle.redis.replicator.Replicator;
import com.moilioncircle.redis.replicator.cmd.CommandName;
import com.moilioncircle.redis.replicator.cmd.impl.DefaultCommand;
import com.moilioncircle.redis.replicator.cmd.parser.DefaultCommandParser;
import com.moilioncircle.redis.replicator.cmd.parser.PingParser;
import com.moilioncircle.redis.replicator.cmd.parser.ReplConfParser;
import com.moilioncircle.redis.replicator.rdb.datatype.AuxField;
import com.moilioncircle.redis.replicator.rdb.datatype.DB;
import com.moilioncircle.redis.replicator.rdb.dump.DumpRdbVisitor;
import com.moilioncircle.redis.replicator.rdb.dump.datatype.DumpKeyValuePair;
import com.moilioncircle.redis.replicator.util.Strings;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;

import java.io.Closeable;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static redis.clients.jedis.Protocol.Command.*;
import static redis.clients.jedis.Protocol.toByteArray;

public class Main {
    static Log log = LogFactory.get();

    public static void main(String[] args) throws IOException, URISyntaxException {
        String source = System.getenv("source");
        String sourcePassword = System.getenv("sourcePassword");
        String target = System.getenv("target");
        String targetPassword = System.getenv("targetPassword");

        if (null == source || source.isBlank()){
            log.info("System env source not found. ");
            log.info("\t\tsimple:");
            log.info("\t\t\t- 127.0.0.1:6379");
            log.info("\t\t\t- ./appendonly.aof");
            log.info("\t\t\t- ./dump.rdb");
            System.exit(0);
        }
        if (null == sourcePassword || sourcePassword.isBlank()){
           log.info( "System env sourcePassword not found. Connect source Redis without AUTH.");
        }

        if (null == target || target.isBlank()){
            log.info("System env target not found. ");
            log.info("\t\tsimple:");
            log.info("\t\t\t- 127.0.0.1:6379");
            System.exit(0);
        }

        if (null == targetPassword || targetPassword.isBlank()){
            log.info("System env targetPassword not found. Connect source Redis without AUTH.");
        }

        StringBuilder sourceUrlBuilder = new StringBuilder("redis://");
        sourceUrlBuilder.append(source);
        if (sourcePassword != null && !sourcePassword.isBlank() && !source.endsWith(".aof") && !source.endsWith(".rdb")){
            sourceUrlBuilder.append("?").append("authPassword=").append(sourcePassword);
        }

        StringBuilder targetUrlBuilder = new StringBuilder("redis://");
        targetUrlBuilder.append(target);
        if (targetPassword != null && !targetPassword.isBlank()){
            targetUrlBuilder.append("?").append("authPassword=").append(targetPassword);
        }
        String sourceUrl  = sourceUrlBuilder.toString();
        String targetUrl = targetUrlBuilder.toString();
        if (sourceUrl.endsWith(".aof") || sourceUrl.endsWith(".rdb")) {
            ExecutorService executor = ExecutorBuilder.create()//
                    .setCorePoolSize(1)//
                    .setMaxPoolSize(1)//
                    .setKeepAliveTime(0)//
                    .setWorkQueue(new LinkedBlockingQueue<>(2))
                    .build();

            executor.submit(()->{
                    sync(sourceUrl,targetUrl);
                    log.info("==========  Sync done.  ==========");
                    return true;
                });
            WatchMonitor.createAll(source, new SimpleWatcher(){
                @Override
                public void onCreate(WatchEvent<?> event, Path currentPath) {
                    this.onModify(event, currentPath);
                }

                @Override
                public void onOverflow(WatchEvent<?> event, Path currentPath) {
                    this.onModify(event, currentPath);
                }

                @Override
                public void onModify(WatchEvent<?> event, Path currentPath) {
                    log.info("==========  Listen modify. ReSync again  ==========");
                    executor.submit(()->{
                        sync(sourceUrl,targetUrl);
                        log.info("==========  Sync done.  ==========");
                        return true;
                    });
                }
            }).start();
        } else{
            sync(sourceUrl, targetUrl);
        }
    }



    /*
     * Precondition:
     * 1. Make sure the two redis version is same.
     * 2. Make sure the single key-value is not very big.(highly recommend less then 1 MB)
     *
     * We running following steps to sync two redis.
     * 1. Get rdb stream from source redis.
     * 2. Convert source rdb stream to redis dump format.
     * 3. Use Jedis RESTORE command to restore that dump format to target redis.
     * 4. Get aof stream from source redis and sync to target redis.
     */
    public static void sync(String sourceUri, String targetUri) throws IOException, URISyntaxException {
        RedisURI suri = new RedisURI(sourceUri);
        RedisURI turi = new RedisURI(targetUri);
        final ExampleClient target = new ExampleClient(turi.getHost(), turi.getPort());
        Configuration tconfig = Configuration.valueOf(turi);
        if (tconfig.getAuthPassword() != null) {
            Object auth = target.send(AUTH, tconfig.getAuthPassword().getBytes());
            log.info("AUTH:" + auth);
        }
        final AtomicInteger dbnum = new AtomicInteger(-1);
        Replicator r = dress(new RedisReplicator(suri));

        r.addEventListener((replicator, event) -> {
            if (event instanceof AuxField) {
                if(((AuxField) event).getAuxKey().equals("ctime")) {
                    log.info(((AuxField) event).getAuxKey() +": " + DateTime.of(Long.parseLong(((AuxField) event).getAuxValue())));
                }
                return;
            }
            if (event instanceof DumpKeyValuePair) {
                DumpKeyValuePair dkv = (DumpKeyValuePair) event;
                // Step1: select db
                DB db = dkv.getDb();
                int index;
                if (db != null && (index = (int) db.getDbNumber()) != dbnum.get()) {
                    target.send(SELECT, toByteArray(index));
                    dbnum.set(index);
                    log.info("SELECT:" + index);
                }

                // Step2: restore dump data
                if (dkv.getExpiredMs() == null) {
                    target.restore(dkv.getKey(), 0L, dkv.getValue(), true);
                } else {
                    long ms = dkv.getExpiredMs() - System.currentTimeMillis();
                    if (ms <= 0) return;
                    target.restore(dkv.getKey(), ms, dkv.getValue(), true);
                }
            }

            if (event instanceof DefaultCommand) {
                // Step3: sync aof command
                DefaultCommand dc = (DefaultCommand) event;
                target.send(dc.getCommand(), dc.getArgs());
            }
        });

        r.addCloseListener(replicator -> target.close());
        r.open();
    }

    public static Replicator dress(Replicator r) {
        r.setRdbVisitor(new DumpRdbVisitor(r));
        // ignore PING REPLCONF GETACK
        r.addCommandParser(CommandName.name("PING"), new PingParser());
        r.addCommandParser(CommandName.name("REPLCONF"), new ReplConfParser());
        //
        r.addCommandParser(CommandName.name("APPEND"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SET"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SETEX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("MSET"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("DEL"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SADD"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("HMSET"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("HSET"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("LSET"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("EXPIRE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("EXPIREAT"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("GETSET"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("HSETNX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("MSETNX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("PSETEX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SETNX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SETRANGE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("HDEL"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("LPOP"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("LPUSH"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("LPUSHX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("LRem"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("RPOP"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("RPUSH"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("RPUSHX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZREM"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("RENAME"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("INCR"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("DECR"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("INCRBY"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("DECRBY"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("PERSIST"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SELECT"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("FLUSHALL"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("FLUSHDB"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("HINCRBY"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZINCRBY"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("MOVE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SMOVE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("PFADD"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("PFCOUNT"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("PFMERGE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SDIFFSTORE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SINTERSTORE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SUNIONSTORE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZADD"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZINTERSTORE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZUNIONSTORE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("BRPOPLPUSH"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("LINSERT"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("RENAMENX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("RESTORE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("PEXPIRE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("PEXPIREAT"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("GEOADD"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("EVAL"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("EVALSHA"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SCRIPT"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("PUBLISH"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("BITOP"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("BITFIELD"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SETBIT"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SREM"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("UNLINK"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SWAPDB"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("MULTI"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("EXEC"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZREMRANGEBYSCORE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZREMRANGEBYRANK"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZREMRANGEBYLEX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("LTRIM"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("SORT"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("RPOPLPUSH"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZPOPMIN"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZPOPMAX"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("XACK"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("XADD"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("XCLAIM"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("XDEL"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("XGROUP"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("XTRIM"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("XSETID"), new DefaultCommandParser());
        // since redis 6.2
        r.addCommandParser(CommandName.name("COPY"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("LMOVE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("BLMOVE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("ZDIFFSTORE"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("GEOSEARCHSTORE"), new DefaultCommandParser());
        // since redis 7.0
        r.addCommandParser(CommandName.name("SPUBLISH"), new DefaultCommandParser());
        r.addCommandParser(CommandName.name("FUNCTION"), new DefaultCommandParser());
        return r;
    }

    /*
     * Jedis is not a reliable redis client.
     * For simplicity we use Jedis to show this example.
     * In production you need to replace following code to yours.
     */
    public static class ExampleClient implements Closeable {

        private Jedis jedis;

        public ExampleClient(final String host, final int port) {
            DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder();
            config.timeoutMillis(10000);
            this.jedis = new Jedis(new HostAndPort(host, port), config.build());
        }

        public Object send(Protocol.Command cmd, final byte[]... args) {
            Object r = jedis.sendCommand(cmd, args);
            if (r instanceof byte[]) {
                return Strings.toString(r);
            } else {
                return r;
            }
        }

        public Object send(final byte[] cmd, final byte[]... args) {
            return send(Protocol.Command.valueOf(Strings.toString(cmd).toUpperCase()), args);
        }

        public Object restore(byte[] key, long expired, byte[] dumped, boolean replace) {
            if (!replace) {
                return send(RESTORE, key, toByteArray(expired), dumped);
            } else {
                return send(RESTORE, key, toByteArray(expired), dumped, "REPLACE".getBytes());
            }
        }

        @Override
        public void close() {
            if (jedis != null) {
                jedis.close();
            }
        }
    }
}