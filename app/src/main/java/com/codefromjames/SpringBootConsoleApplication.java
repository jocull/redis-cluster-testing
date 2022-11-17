package com.codefromjames;

import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.support.ConnectionPoolSupport;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@SpringBootApplication
public class SpringBootConsoleApplication implements CommandLineRunner {
    private static Logger LOGGER = LoggerFactory.getLogger(SpringBootConsoleApplication.class);

    public static void main(String[] args) {
        LOGGER.info("STARTING THE APPLICATION");
        SpringApplication.run(SpringBootConsoleApplication.class, args);
        LOGGER.info("APPLICATION FINISHED");
    }

    @Override
    public void run(String... args) {
        LOGGER.info("EXECUTING : command line runner");

        for (int i = 0; i < args.length; ++i) {
            LOGGER.info("args[{}]: {}", i, args[i]);
        }

        final List<RedisURI> hosts = List.of(
                RedisURI.create("redis://redis01"),
                RedisURI.create("redis://redis02"),
                RedisURI.create("redis://redis03"),
                RedisURI.create("redis://redis04"),
                RedisURI.create("redis://redis05"),
                RedisURI.create("redis://redis06"),
                RedisURI.create("redis://redis07"),
                RedisURI.create("redis://redis08"),
                RedisURI.create("redis://redis09"));
        hosts.forEach(h -> h.setTimeout(Duration.ofSeconds(5))); // TODO: Not sure if this is the right way to timeout commands

        try (final RedisClusterClient redisClient = RedisClusterClient.create(hosts);
             final ObjectPool<StatefulRedisClusterConnection<String, byte[]>> pool = ConnectionPoolSupport
                     .createGenericObjectPool(() -> {
                         final StatefulRedisClusterConnection<String, byte[]> connection = redisClient.connect(StringBytesCodec.INSTANCE);
                         connection.setReadFrom(ReadFrom.MASTER); // Highest consistency
                         return connection;
                     }, new GenericObjectPoolConfig<>())) {
            redisClient.setOptions(ClusterClientOptions.builder()
                    .autoReconnect(true)
                    .validateClusterNodeMembership(false) // important for when nodes are added/removed!
                    .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                            .enablePeriodicRefresh(Duration.of(5, ChronoUnit.SECONDS))
                            .enableAllAdaptiveRefreshTriggers()
                            .build())
                    .build());

            IntStream.range(0, 8)
                    .mapToObj(i -> {
                        Thread t = new Thread(new LargeThrashingTest(pool, 50, 1024 * 10 * (i + 1)));
                        t.setName("data-pusher-" + i);
                        t.start();
                        return t;
                    })
                    .collect(Collectors.toList())
                    .forEach(t -> {
                        try {
                            t.join();
                        } catch (InterruptedException ex) {
                            LOGGER.info("Interrupted", ex);
                        }
                    });
        }
    }

    private static class StringBytesCodec implements RedisCodec<String, byte[]> {
        static StringBytesCodec INSTANCE = new StringBytesCodec();

        @Override
        public String decodeKey(ByteBuffer byteBuffer) {
            return StringCodec.UTF8.decodeKey(byteBuffer);
        }

        @Override
        public byte[] decodeValue(ByteBuffer byteBuffer) {
            return ByteArrayCodec.INSTANCE.decodeValue(byteBuffer);
        }

        @Override
        public ByteBuffer encodeKey(String s) {
            return StringCodec.UTF8.encodeKey(s);
        }

        @Override
        public ByteBuffer encodeValue(byte[] bytes) {
            return ByteArrayCodec.INSTANCE.encodeValue(bytes);
        }
    }
}
