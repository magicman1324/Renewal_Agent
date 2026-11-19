package com.wanghongbo.renewalagent.service;

import com.wanghongbo.renewalagent.config.AppProperties;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class ScanLockService {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final AppProperties properties;
    private final StringRedisTemplate redis;
    private final Map<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, String>> redisTokens = ThreadLocal.withInitial(ConcurrentHashMap::new);

    public ScanLockService(AppProperties properties, StringRedisTemplate redis) {
        this.properties = properties;
        this.redis = redis;
    }

    public LockHandle acquire(String key, Duration ttl) {
        if ("redis".equalsIgnoreCase(properties.lock().mode())) {
            String token = UUID.randomUUID().toString();
            Boolean acquired = redis.opsForValue().setIfAbsent("renewal-agent:lock:" + key, token, ttl);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new IllegalStateException("Another risk scan is already running");
            }
            redisTokens.get().put(key, token);
            return () -> {
                String stored = redisTokens.get().remove(key);
                if (stored != null) {
                    redis.execute(
                            UNLOCK_SCRIPT,
                            java.util.List.of("renewal-agent:lock:" + key),
                            stored
                    );
                }
            };
        }

        ReentrantLock lock = localLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        try {
            if (!lock.tryLock(ttl.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Another risk scan is already running");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for risk scan lock", ex);
        }
        return lock::unlock;
    }

    @FunctionalInterface
    public interface LockHandle extends AutoCloseable {
        @Override
        void close();
    }
}
