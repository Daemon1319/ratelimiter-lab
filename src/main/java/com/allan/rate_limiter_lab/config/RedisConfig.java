package com.allan.rate_limiter_lab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

  @Bean
  public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);

    StringRedisSerializer stringSerializer = new StringRedisSerializer();

    // Force all keys and values to be stored as clean, readable UTF-8 strings
    template.setKeySerializer(stringSerializer);
    template.setValueSerializer(stringSerializer);
    
    // Also apply string serialization to Hash structures (used for your Master State & Token Bucket)
    template.setHashKeySerializer(stringSerializer);
    template.setHashValueSerializer(stringSerializer);

    template.afterPropertiesSet();
    return template;
  }
}