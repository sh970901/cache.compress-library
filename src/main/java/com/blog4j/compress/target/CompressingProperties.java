package com.blog4j.compress.target;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "spring.redis.compress")
@Configuration
public class CompressingProperties extends CompressingRedisTargetProperties{
}
