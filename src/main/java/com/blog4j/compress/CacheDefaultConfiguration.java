package com.blog4j.compress;

import com.blog4j.compress.target.RedisDefaultProperties;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@AutoConfiguration
@RequiredArgsConstructor
@ComponentScan(basePackages = "com.blog4j.compress")
public class CacheDefaultConfiguration  {

    private final RedisDefaultProperties redisProperties;

    @Bean("redisCacheManager")
    @ConditionalOnMissingBean(CacheManager.class)
    public CacheManager redisCacheManager() {

        RedisCacheConfiguration redisCacheConfiguration = defaultConfiguration();

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("defaultRedisCache", redisCacheConfiguration.entryTtl(Duration.ofSeconds(CacheExpireSec.DEFAULT_EXPIRE_SEC.getSec())));

        return RedisCacheManager.RedisCacheManagerBuilder
            .fromConnectionFactory(defaultRedisConnectionFactory())
            .withInitialCacheConfigurations(cacheConfigurations)
            .cacheDefaults(redisCacheConfiguration)
            .build();
    }

    private RedisConnectionFactory defaultRedisConnectionFactory() {
        LettuceClientConfiguration lettuceClientConfiguration =
            LettuceClientConfiguration.builder()
                                      .commandTimeout(Duration.ofMinutes(1))
                                      .shutdownTimeout(Duration.ZERO)
                                      .clientResources(defaultClientResources())
                                      .build();

        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration(redisProperties.getHost(), redisProperties.getPort());
        redisStandaloneConfiguration.setPassword("");
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisStandaloneConfiguration, lettuceClientConfiguration);
        factory.afterPropertiesSet();
        return factory;
    }

    private RedisCacheConfiguration defaultConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                                      .disableCachingNullValues()
                                      .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                                      .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                                      .entryTtl(Duration.ofSeconds(CacheExpireSec.DEFAULT_EXPIRE_SEC.getSec()));
    }


    private ClientResources defaultClientResources() {
        return DefaultClientResources.create();
    }

    private enum CacheExpireSec {
        DEFAULT_EXPIRE_SEC(60 * 59);
        private final Integer sec;

        CacheExpireSec(Integer sec) {
            this.sec = sec;
        }

        public Integer getSec() {
            return sec;
        }
    }
}
