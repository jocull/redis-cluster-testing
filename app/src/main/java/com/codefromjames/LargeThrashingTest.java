package com.codefromjames;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.protocol.ConnectionIntent;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.pool2.ObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class LargeThrashingTest implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LargeThrashingTest.class);
    private static final Random RANDOM = new Random();

    private final ObjectPool<StatefulRedisClusterConnection<String, byte[]>> pool;
    private final int valueSizeBytes;

    private final Retry retry;
    private final List<String> keySet;
    private final Map<String, String> ackedKeyHashes = new HashMap<>();

    public LargeThrashingTest(ObjectPool<StatefulRedisClusterConnection<String, byte[]>> pool, int keyCount, int valueSizeBytes) {
        if (keyCount < 1) {
            throw new IllegalArgumentException("Key count must be >= 1");
        }
        if (valueSizeBytes < 0) {
            throw new IllegalArgumentException("Value byte size must be >= 0");
        }

        this.pool = pool;
        this.valueSizeBytes = valueSizeBytes;

        this.retry = Retry.of("default", RetryConfig.custom()
                .maxAttempts(20)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(50), 2, Duration.ofSeconds(5)))
                .failAfterMaxAttempts(true)
                .retryOnException(ex -> ex instanceof RedisException)
                .build());

        this.keySet = IntStream.range(0, keyCount)
                .mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.toList());
    }

    @Override
    public void run() {
        final MutableInt counter = new MutableInt(0);
        final Deque<String> keyRotation = new ArrayDeque<>(keySet);
        while (true) {
            if (keyRotation.isEmpty()) {
                keyRotation.addAll(keySet);
            }
            final LargeObject largeObject = new LargeObject(keyRotation.pop(), valueSizeBytes);
            try {
                final StatefulRedisClusterConnection<String, byte[]> cluster = pool.borrowObject();
                try {
                    retry.executeRunnable(() -> {
                        // Annoying stuff you have to do to get the right connection for the target hash slot.
                        // This way you can hold a specific connection to do the synchronous replication waits.
                        final int slot = SlotHash.getSlot(largeObject.key);
                        final String nodeId = cluster.getPartitions().getPartitionBySlot(slot).getNodeId();
                        final StatefulRedisConnection<String, byte[]> connection = cluster.getConnection(nodeId, ConnectionIntent.WRITE);
                        final RedisCommands<String, byte[]> client = connection.sync();

                        Optional.ofNullable(client.get(largeObject.key))
                                .map(DigestUtils::sha1Hex)
                                .ifPresent(currentHash -> {
                                    // The previous write might have succeeded before acking somehow, and might match the target hash.
                                    // That would be ok!
                                    final String targetHash = largeObject.hash;
                                    final String ackedHash = ackedKeyHashes.get(largeObject.key);
                                    if (ackedHash != null
                                            && !ackedHash.equals(currentHash)
                                            && !targetHash.equals(currentHash)) {
                                        LOGGER.warn("Current key {} hash {} does not match {} or {}", largeObject.key, currentHash, ackedHash, targetHash);
                                    }
                                });

                        client.set(largeObject.key, largeObject.blob);
                        final long replication = client.waitForReplication(1, 2000);
                        if (replication < 1) {
                            throw new RedisCommandTimeoutException("Replication failed w/ replication = " + replication);
                        }
                        LOGGER.info("Wrote #{}, {} w/ hash {}", counter.getAndIncrement(), largeObject.key, largeObject.hash);
                        ackedKeyHashes.put(largeObject.key, largeObject.hash);
                    });
                } finally {
                    pool.returnObject(cluster);
                }
            } catch (RedisException ex) {
                LOGGER.error("DB failed", ex);
            } catch (Exception ex) {
                LOGGER.error("Unexpected exception!", ex);
                throw new RuntimeException(ex);
            }
//            try {
//                Thread.sleep(250);
//            } catch (InterruptedException ex) {
//                LOGGER.info("Interrupted", ex);
//                return;
//            }
        }
    }

    private static class LargeObject {
        final String key;
        final byte[] blob;
        final String hash;

        LargeObject(String key, int valueSizeBytes) {
            this.key = key;
            blob = new byte[valueSizeBytes];
            RANDOM.nextBytes(blob);
            this.hash = DigestUtils.sha1Hex(blob);
        }
    }
}
