package com.yourname.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

/**
 * LESSON 1 - talking to Redis directly.
 *
 * Spring Boot already auto-configures a {@link RedisConnectionFactory} (Lettuce) and a
 * {@code StringRedisTemplate}. What it does NOT give you is a template that stores real
 * objects as JSON, which is what we need for manual "cache-aside" code.
 *
 * Serialization is THE classic Redis-caching trap:
 *  - The default JDK serializer writes unreadable binary and explodes the moment a class changes.
 *  - A plain JSON serializer writes {"id":...} with no type info, so on read Redis hands you a
 *    LinkedHashMap instead of your record -> ClassCastException.
 *  - The fix is "default typing": Jackson also writes an "@class" property. That is powerful and
 *    dangerous (deserializing an arbitrary class name = remote code execution), so we restrict it
 *    with a {@link PolymorphicTypeValidator} allow-list.
 *
 * Note for Spring Boot 4: Jackson 3 is the default, so the classes live under
 * {@code tools.jackson.*} and the serializer is {@code GenericJacksonJsonRedisSerializer}
 * (the older {@code GenericJackson2JsonRedisSerializer} is the Jackson 2 variant).
 */
@Configuration
public class RedisConfig {

    /** Only these packages may ever be resurrected from an "@class" property in Redis. */
    @Bean
    public PolymorphicTypeValidator redisTypeValidator() {
        return BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.yourname.inventory.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.lang.")
                .build();
    }

    /**
     * Shared JSON serializer used both by the manual {@link RedisTemplate} and by the
     * cache manager in {@code CacheConfig}, so a value written by one is readable by the other.
     *
     * {@code enableSpringCacheNullValueSupport()} teaches Redis how to store Spring's
     * {@code NullValue} marker - that is what makes "cache the fact that nothing was found"
     * (cache-penetration protection) possible.
     */
    @Bean
    public GenericJacksonJsonRedisSerializer redisJsonSerializer(PolymorphicTypeValidator validator) {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(validator)
                .enableSpringCacheNullValueSupport()
                .typePropertyName("@class")
                .build();
    }

    /**
     * Template for hand-written cache-aside code (see {@code CatalogService#topProducts}).
     *
     * Keys are plain strings so you can read them with {@code redis-cli KEYS 'inventory:*'};
     * values are JSON so you can inspect them with {@code redis-cli GET <key>}.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       GenericJacksonJsonRedisSerializer jsonSerializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * LESSON 3 preview - releasing a distributed lock safely.
     *
     * "GET then DEL" is a bug: between the two commands the lock may expire and be taken by
     * somebody else, and you would delete THEIR lock. Redis runs a Lua script atomically, so
     * compare-and-delete becomes a single indivisible operation.
     */
    @Bean
    public DefaultRedisScript<Long> releaseLockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                else
                    return 0
                end
                """);
        script.setResultType(Long.class);
        return script;
    }
}
