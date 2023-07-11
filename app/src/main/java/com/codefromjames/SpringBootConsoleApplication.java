package com.codefromjames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.providers.ClusterConnectionProvider;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
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

        final Set<HostAndPort> hosts = Set.of(
                HostAndPort.from("redis01:6379"),
                HostAndPort.from("redis02:6379"),
                HostAndPort.from("redis03:6379"),
                HostAndPort.from("redis04:6379"),
                HostAndPort.from("redis05:6379"),
                HostAndPort.from("redis06:6379"),
                HostAndPort.from("redis07:6379"),
                HostAndPort.from("redis08:6379"),
                HostAndPort.from("redis09:6379"));

        final JedisClientConfig jedisClientConfig = DefaultJedisClientConfig.builder()
                .timeoutMillis(5_000)
                .build();

        try (final JedisCluster cluster = new JedisCluster(hosts, jedisClientConfig)) {
            LOGGER.info("Flushing entire cluster to clear memory...");
            cluster.flushAll();
            LOGGER.info("... done!");
        }

        try (final ClusterConnectionProvider connectionProvider = new ClusterConnectionProvider(hosts, jedisClientConfig)) {
            // Kind of a silly background loop, but it's easy to write. Better as a scheduled task.
            final Thread topologyRefreshThread = new Thread(() -> {
                while (true) {
                    try {
                        //noinspection BusyWait
                        Thread.sleep(15_000);
                        connectionProvider.renewSlotCache(); // Background thread refreshes in case JedisConnectionException is not caught in runtime tests
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            topologyRefreshThread.setName("jedis-topology-refresh");
            topologyRefreshThread.setDaemon(true);
            topologyRefreshThread.start();

            try {
                IntStream.range(0, 8)
                        .mapToObj(i -> {
                            Thread t = new Thread(new LargeThrashingTest(connectionProvider, 50, 1024 * 128 * (i + 1)));
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
            } finally {
                topologyRefreshThread.interrupt();
            }
        }
    }
}
