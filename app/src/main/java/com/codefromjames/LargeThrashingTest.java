package com.codefromjames;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.protocol.ConnectionIntent;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.lz4.LZ4SafeDecompressor;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.pool2.ObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class LargeThrashingTest implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LargeThrashingTest.class);
    private static final Random RANDOM = new Random();
    private static final LZ4Compressor lz4Compressor = LZ4Factory.fastestInstance().fastCompressor();
    private static final LZ4FastDecompressor lz4Decompressor = LZ4Factory.fastestInstance().fastDecompressor();

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
                .maxAttempts(30)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(50), 2, Duration.ofSeconds(1)))
                .failAfterMaxAttempts(true)
                .retryOnException(ex -> {
                    LOGGER.error("Error during retry", ex);
                    return ex instanceof RedisException;
                })
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
                    final AtomicInteger attemptCounter = new AtomicInteger(1);
                    retry.executeRunnable(() -> {
                        MDC.put("attempt", String.valueOf(attemptCounter.getAndIncrement()));

                        // Annoying stuff you have to do to get the right connection for the target hash slot.
                        // This way you can hold a specific connection to do the synchronous replication waits.
                        final int slot = SlotHash.getSlot(largeObject.key);
                        final String nodeId = cluster.getPartitions().getPartitionBySlot(slot).getNodeId();
                        final StatefulRedisConnection<String, byte[]> connection = cluster.getConnection(nodeId, ConnectionIntent.WRITE);
                        final RedisCommands<String, byte[]> client = connection.sync();

                        Optional.ofNullable(client.get(largeObject.key))
                                .map(value -> lz4Decompressor.decompress(value, largeObject.valueSizeBytes))
                                .map(DigestUtils::sha1Hex)
                                .ifPresent(currentHash -> {
                                    // The previous write might have succeeded before acking somehow, and might match the target hash.
                                    // That would be ok!
                                    final String targetHash = largeObject.rawHash;
                                    final String ackedHash = ackedKeyHashes.get(largeObject.key);
                                    if (ackedHash != null
                                            && !ackedHash.equals(currentHash)
                                            && !targetHash.equals(currentHash)) {
                                        LOGGER.warn("Current key {} hash {} does not match {} or {}", largeObject.key, currentHash, ackedHash, targetHash);
                                    }
                                });

                        client.set(largeObject.key, largeObject.compressedBlob);
                        final long replication = client.waitForReplication(1, 2000);
                        if (replication < 1) {
                            throw new RedisCommandTimeoutException("Replication failed w/ replication = " + replication);
                        }
                        LOGGER.info("Wrote #{}, {} w/ hash {} @ ratio {}", counter.getAndIncrement(), largeObject.key, largeObject.rawHash, largeObject.compressionRatioStr);
                        ackedKeyHashes.put(largeObject.key, largeObject.rawHash);
                    });
                } finally {
                    MDC.remove("attempt");
                    pool.returnObject(cluster);
                }
            } catch (RedisException ex) {
                LOGGER.error("DB failed", ex);
            } catch (Exception ex) {
                LOGGER.error("Unexpected exception!", ex);
                throw new RuntimeException(ex);
            }
        }
    }

    private static class LargeObject {
        final String key;
        final int valueSizeBytes;
        final byte[] rawBlob;
        final byte[] compressedBlob;
        final String rawHash;
        final String compressedHash;
        final double compressionRatio;
        final String compressionRatioStr;

        LargeObject(String key, int valueSizeBytes) {
            this.key = key;
            this.valueSizeBytes = valueSizeBytes;

            this.rawBlob = new byte[valueSizeBytes];
            RANDOM.nextBytes(rawBlob);
            this.rawHash = DigestUtils.sha1Hex(rawBlob);

            this.compressedBlob = lz4Compressor.compress(rawBlob);
            this.compressedHash = DigestUtils.sha1Hex(compressedBlob);
            this.compressionRatio = (double) this.compressedBlob.length / (double) rawBlob.length;
            this.compressionRatioStr = String.format("%.2f", compressionRatio);
        }
    }
}
