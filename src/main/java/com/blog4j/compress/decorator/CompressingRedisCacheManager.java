package com.blog4j.compress.decorator;

import java.util.Collection;
import java.util.Map;
import org.springframework.cache.Cache;
import org.springframework.cache.transaction.AbstractTransactionSupportingCacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;

/**
 * CompressingRedisCacheManager that returns CompressingRedisCacheWrapper instead of RedisCache
 */
public class CompressingRedisCacheManager extends AbstractTransactionSupportingCacheManager {
    private final RedisCacheManager delegate;
    private final String beanName;

    private final long thresholdSize;

    public CompressingRedisCacheManager(RedisCacheManager delegate, String beanName, long thresholdSize) {
        this.delegate = delegate;
        this.beanName = beanName;
        this.thresholdSize = thresholdSize;
    }

    @Override protected Collection<? extends Cache> loadCaches() {
        return delegate.getCacheNames().stream()
                       .map(delegate::getCache)
                       .toList();
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = delegate.getCache(name);
        if (cache instanceof RedisCache) {
            return new CompressingRedisCacheWrapper((RedisCache) cache, thresholdSize);
        } else {
            return cache;
        }
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    public Map<String, RedisCacheConfiguration> getCacheConfigurations() {
        return delegate.getCacheConfigurations();
    }

    public String getBeanName() {
        return this.beanName;
    }
}
