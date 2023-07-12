package com.codefromjames;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.exceptions.JedisClusterOperationException;
import redis.clients.jedis.exceptions.JedisException;

import java.nio.charset.StandardCharsets;
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

    private final JedisCluster client;
    private final int valueSizeBytes;

    private final Retry retry;
    private final List<String> keySet;
    private final Map<String, String> ackedKeyHashes = new HashMap<>();

    public LargeThrashingTest(JedisCluster client, int keyCount, int valueSizeBytes) {
        if (keyCount < 1) {
            throw new IllegalArgumentException("Key count must be >= 1");
        }
        if (valueSizeBytes < 0) {
            throw new IllegalArgumentException("Value byte size must be >= 0");
        }

        this.client = client;
        this.valueSizeBytes = valueSizeBytes;

        this.retry = Retry.of("default", RetryConfig.custom()
                .maxAttempts(30)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(50), 2, Duration.ofSeconds(1)))
                .failAfterMaxAttempts(true)
                .retryOnException(ex -> {
                    LOGGER.error("Error during retry", ex);
                    return ex instanceof JedisException;
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
                try {
                    final AtomicInteger attemptCounter = new AtomicInteger(1);
                    retry.executeRunnable(() -> {
                        MDC.put("attempt", String.valueOf(attemptCounter.getAndIncrement()));

                        Optional.ofNullable(client.get(largeObject.keyBytes))
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

                        client.set(largeObject.keyBytes, largeObject.compressedBlob);
                        final long replication = client.waitReplicas(largeObject.keyBytes, 1, 2000);
                        if (replication < 1) {
                            throw new JedisClusterOperationException("Replication failed w/ replication = " + replication);
                        }
                        LOGGER.info("Wrote #{}, {} w/ hash {} @ ratio {}", counter.getAndIncrement(), largeObject.key, largeObject.rawHash, largeObject.compressionRatioStr);
                        ackedKeyHashes.put(largeObject.key, largeObject.rawHash);
                    });
                } finally {
                    MDC.remove("attempt");
                }
            } catch (JedisException ex) {
                LOGGER.error("DB failed", ex);
            } catch (Exception ex) {
                LOGGER.error("Unexpected exception!", ex);
                throw new RuntimeException(ex);
            }
        }
    }

    private static class LargeObject {
        final String key;
        final byte[] keyBytes;
        final int valueSizeBytes;
        final byte[] rawBlob;
        final byte[] compressedBlob;
        final String rawHash;
        final String compressedHash;
        final double compressionRatio;
        final String compressionRatioStr;

        LargeObject(String key, int valueSizeBytes) {
            this.key = key;
            this.keyBytes = key.getBytes(StandardCharsets.UTF_8);
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
