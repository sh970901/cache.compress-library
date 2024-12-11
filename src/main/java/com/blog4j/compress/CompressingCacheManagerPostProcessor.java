package com.blog4j.compress;


import com.blog4j.compress.decorator.CompressingRedisCacheManager;
import com.blog4j.compress.target.CompressingProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class CompressingCacheManagerPostProcessor implements BeanPostProcessor {

    private final CompressingProperties compressingProperties;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (isCompressingTargetRedisCache(bean, beanName)){
            long thresholdSize = compressingProperties.getThresholdSize();

            return new CompressingRedisCacheManager((RedisCacheManager) bean, beanName, thresholdSize);
        }
        return bean;
    }

    private boolean isCompressingTargetRedisCache(Object bean, String beanName){
        List<String> redisCacheManagers = compressingProperties.getTargetCacheManagers();
        return (bean instanceof RedisCacheManager) && redisCacheManagers.contains(beanName);
    }
}
