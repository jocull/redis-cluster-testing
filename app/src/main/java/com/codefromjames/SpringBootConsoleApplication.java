package com.codefromjames;

import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
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
                RedisURI.create("redis://redis01:6379"),
                RedisURI.create("redis://redis02:6380"),
                RedisURI.create("redis://redis03:6381"),
                RedisURI.create("redis://redis04:6382"),
                RedisURI.create("redis://redis05:6383"),
                RedisURI.create("redis://redis06:6384"));
        hosts.forEach(h -> h.setTimeout(Duration.ofSeconds(5))); // TODO: Not sure if this is the right way to timeout commands

        try (final RedisClusterClient redisClient = RedisClusterClient.create(hosts);
             final StatefulRedisClusterConnection<String, byte[]> connection = redisClient.connect(StringBytesCodec.INSTANCE)) {
            redisClient.setOptions(ClusterClientOptions.builder()
                    .autoReconnect(true)
                    .validateClusterNodeMembership(false) // important for when nodes are added/removed!
                    .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                            .enablePeriodicRefresh(Duration.of(10, ChronoUnit.SECONDS))
                            .enableAllAdaptiveRefreshTriggers()
                            .build())
                    .build());

            connection.setReadFrom(ReadFrom.MASTER); // Highest consistency

            final RedisAdvancedClusterCommands<String, byte[]> client = connection.sync();
            IntStream.range(0, 8)
                    .mapToObj(i -> {
                        Thread t = new Thread(new LargeThrashingTest(client, 50, 1024 * 10 * (i + 1)));
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
