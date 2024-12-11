package com.blog4j.compress.decorator;

import static com.blog4j.compress.CompressingUtils.compressGzip;
import static com.blog4j.compress.CompressingUtils.decompressGzip;
import static com.blog4j.compress.CompressingUtils.isCompressedGzip;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.util.ByteUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;


/*
    It is a wrapper class that wraps RedisCache and has all the same functions as RedisCache,
    but adds compression and decompression during serialization and deserialization.
 */
public class CompressingRedisCacheWrapper extends AbstractValueAdaptingCache {
    private static final byte[] BINARY_NULL_VALUE = RedisSerializer.java().serialize(NullValue.INSTANCE);
    private static final String CACHE_RETRIEVAL_UNSUPPORTED_OPERATION_EXCEPTION_MESSAGE = "The Redis driver configured with RedisCache through RedisCacheWriter does not support CompletableFuture-based retrieval";
    private final RedisCache delegate;
    private final long thresholdSize;
    public CompressingRedisCacheWrapper(RedisCache delegate, long thresholdSize) {
        super(delegate.getCacheConfiguration().getAllowCacheNullValues());
        this.delegate = delegate;
        this.thresholdSize = thresholdSize;
    }

    public CompressingRedisCacheWrapper(RedisCache delegate) {
        super(delegate.getCacheConfiguration().getAllowCacheNullValues());
        this.delegate = delegate;
        this.thresholdSize = 1;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public ValueWrapper get(Object key) {
        // 1. serialize key
        byte[] binaryKey = createAndConvertCacheKey(key);
        // 2. get value(compressed & serialized)
        byte[] binaryValue = delegate.getNativeCache().get(delegate.getName(), binaryKey);
        if (binaryValue == null ) return null;
        // 3. get value(serialized) & decompress(isCompressed==true)
        if (isCompressedGzip(binaryValue)) {
            binaryValue = decompressGzip(binaryValue);
        }
        // 4. get value(decompress & deserialize)
        Serializable deserializedValue = deserialize(binaryValue);
        return () -> deserializedValue;
    }

    @Override
    public void put(Object key, Object value) {
        // 1. serialize value using origin serialize cacheConfig
        byte[] serializedValue = serialize((Serializable) value);

        // 2. compress value using gzip if serializedValue.length >= thresholdSize
        if (serializedValue.length >= thresholdSize) {
            serializedValue = compressGzip(serializedValue);
        }
        // 3. serialize key
        byte[] binaryKey = createAndConvertCacheKey(key);
        // 4. get TTL
        Duration timeToLive = getTimeToLive(key, value);

        RedisCacheWriter cacheWriter = delegate.getNativeCache();
        // 5. put cache
        cacheWriter.put(delegate.getName(), binaryKey, serializedValue, timeToLive);
    }

    /*
       origin serialize
     */
    private byte[] serialize(Serializable value) {
        return ByteUtils.getBytes(delegate.getCacheConfiguration()
                .getValueSerializationPair()
                .write(value));
    }

    /*
       origin deserialize
     */
    private Serializable deserialize(byte[] data) {
        return (Serializable) delegate.getCacheConfiguration()
                .getValueSerializationPair()
                .read(ByteBuffer.wrap(data));
    }

    /*
       deserializeCompressCacheValue
     */
    public <T> T get(Object key, Callable<T> valueLoader) {
        byte[] binaryKey = createAndConvertCacheKey(key);
        byte[] binaryValue = getCacheWriter().get(getName(), binaryKey,
                () -> serializeCompressCacheValue(toStoreValue(loadCacheValue(key, valueLoader))), getTimeToLive(key),
                delegate.getCacheConfiguration().isTimeToIdleEnabled());

        ValueWrapper result = toValueWrapper(deserializeCompressCacheValue(binaryValue));

        return result != null ? (T) result.get() : null;
    }

    private Duration getTimeToLive(Object key) {
        return getTimeToLive(key, null);
    }
    private Duration getTimeToLive(Object key, @Nullable Object value) {
        return delegate.getCacheConfiguration().getTtlFunction().getTimeToLive(key, value);
    }

    private byte[] createAndConvertCacheKey(Object key) {
        return serializeCacheKey(createCacheKey(key));
    }

    /**
     * Serialize the given {@link String cache key}.
     *
     * @param cacheKey {@link String cache key} to serialize; must not be {@literal null}.
     * @return an array of bytes from the given, serialized {@link String cache key}; never {@literal null}.
     * @see RedisCacheConfiguration#getKeySerializationPair()
     */
    protected byte[] serializeCacheKey(String cacheKey) {
        return ByteUtils.getBytes(delegate.getCacheConfiguration().getKeySerializationPair().write(cacheKey));
    }

    /**
     * Customization hook for creating cache key before it gets serialized.
     *
     * @param key will never be {@literal null}.
     * @return never {@literal null}.
     */
    protected String createCacheKey(Object key) {
        String convertedKey = convertKey(key);
        return delegate.getCacheConfiguration().usePrefix() ? prefixCacheKey(convertedKey) : convertedKey;
    }

    /**
     * Convert {@code key} to a {@link String} used in cache key creation.
     *
     * @param key will never be {@literal null}.
     * @return never {@literal null}.
     * @throws IllegalStateException if {@code key} cannot be converted to {@link String}.
     */
    protected String convertKey(Object key) {
        if (key instanceof String stringKey) {
            return stringKey;
        }
        TypeDescriptor source = TypeDescriptor.forObject(key);
        ConversionService conversionService = getConversionService();

        if (conversionService.canConvert(source, TypeDescriptor.valueOf(String.class))) {
            try {
                return conversionService.convert(key, String.class);
            } catch (ConversionFailedException ex) {
                // May fail if the given key is a collection
                if (isCollectionLikeOrMap(source)) {
                    return convertCollectionLikeOrMapKey(key, source);
                }
                throw ex;
            }
        }
        if (hasToStringMethod(key)) {
            return key.toString();
        }
        throw new IllegalStateException(("Cannot convert cache key %s to String; Please register a suitable Converter"
            + " via 'RedisCacheConfiguration.configureKeyConverters(...)' or override '%s.toString()'")
            .formatted(source, key.getClass().getName()));
    }

    /**
     * Gets the configured {@link RedisCacheWriter} used to adapt Redis for cache operations.
     *
     * @return the configured {@link RedisCacheWriter} used to adapt Redis for cache operations.
     */
    private RedisCacheWriter getCacheWriter() {
        return delegate.getNativeCache();
    }


    /**
     * Gets the configured {@link ConversionService} used to convert {@link Object cache keys} to a {@link String} when
     * accessing entries in the cache.
     *
     * @return the configured {@link ConversionService} used to convert {@link Object cache keys} to a {@link String} when
     *         accessing entries in the cache.
     * @see RedisCacheConfiguration#getConversionService()
     * @see # getCacheConfiguration()
     */
    protected ConversionService getConversionService() {
        return delegate.getCacheConfiguration().getConversionService();
    }

    private String prefixCacheKey(String key) {
        // allow contextual cache names by computing the key prefix on every call.
        return delegate.getCacheConfiguration().getKeyPrefixFor(getName()) + key;
    }

    private boolean isCollectionLikeOrMap(TypeDescriptor source) {
        return source.isArray() || source.isCollection() || source.isMap();
    }

    private String convertCollectionLikeOrMapKey(Object key, TypeDescriptor source) {

        if (source.isMap()) {

            int count = 0;

            StringBuilder target = new StringBuilder("{");

            for (Entry<?, ?> entry : ((Map<?, ?>) key).entrySet()) {
                target.append(convertKey(entry.getKey())).append("=").append(convertKey(entry.getValue()));
                target.append(++count > 1 ? ", " : "");
            }

            target.append("}");

            return target.toString();

        } else if (source.isCollection() || source.isArray()) {

            StringJoiner stringJoiner = new StringJoiner(",");

            Collection<?> collection = source.isCollection() ? (Collection<?>) key
                : Arrays.asList(ObjectUtils.toObjectArray(key));

            for (Object collectedKey : collection) {
                stringJoiner.add(convertKey(collectedKey));
            }

            return "[" + stringJoiner + "]";
        }

        throw new IllegalArgumentException("Cannot convert cache key [%s] to String".formatted(key));
    }

    private boolean hasToStringMethod(Object target) {
        return hasToStringMethod(target.getClass());
    }

    private boolean hasToStringMethod(Class<?> type) {

        Method toString = ReflectionUtils.findMethod(type, "toString");

        return toString != null && !Object.class.equals(toString.getDeclaringClass());
    }


    /**
     * Serialize the {@link Object value} to cache as an array of bytes.
     *
     * @param value {@link Object} to serialize and cache; must not be {@literal null}.
     * @return an array of bytes from the serialized {@link Object value}; never {@literal null}.
     * @see RedisCacheConfiguration#getValueSerializationPair()
     * origin serializeCacheValue -> add compression process ->  serializeCompressCacheValue
     */
    protected byte[] serializeCompressCacheValue(Object value) {
        if (isAllowNullValues() && value instanceof NullValue) {
            return BINARY_NULL_VALUE;
        }
        //byte[] serialized = ByteUtils.getBytes(delegate.getCacheConfiguration().getValueSerializationPair().write(value));
        byte[] serialized = serialize((Serializable) value);

        return compressGzip(serialized);
    }

    /**
     * Deserialize the given the array of bytes to the actual {@link Object cache value}.
     *
     * @param value array of bytes to deserialize; must not be {@literal null}.
     * @return an {@link Object} deserialized from the array of bytes using the configured value
     *         {@link RedisSerializationContext.SerializationPair}; can be {@literal null}.
     * @see RedisCacheConfiguration#getValueSerializationPair()
     * origin deserializeCacheValue -> add decompression process ->  deserializeCompressCacheValue
     */
    @Nullable
    protected Object deserializeCompressCacheValue(byte[] value) {

        if (isAllowNullValues() && ObjectUtils.nullSafeEquals(value, BINARY_NULL_VALUE)) {
            return NullValue.INSTANCE;
        }
        byte[] decompressedValue = decompressGzip(value);
        return deserialize(decompressedValue);
    }

    /**
     * Loads the {@link Object} using the given {@link Callable valueLoader}.
     *
     * @param <T> {@link Class type} of the loaded {@link Object cache value}.
     * @param key {@link Object key} mapped to the loaded {@link Object cache value}.
     * @param valueLoader {@link Callable} object used to load the {@link Object value} for the given {@link Object key}.
     * @return the loaded {@link Object value}.
     */
    protected <T> T loadCacheValue(Object key, Callable<T> valueLoader) {

        try {
            return valueLoader.call();
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
    }

    protected Object lookup(Object key) {

        byte[] binaryKey = createAndConvertCacheKey(key);

        byte[] binaryValue = delegate.getCacheConfiguration().isTimeToIdleEnabled()
                ? getCacheWriter().get(getName(), binaryKey, getTimeToLive(key))
                : getCacheWriter().get(getName(), binaryKey);

        return binaryValue != null ? deserializeCompressCacheValue(binaryValue) : null;
    }

    @Override
    public CompletableFuture<ValueWrapper> retrieve(Object key) {

        if (!getCacheWriter().supportsAsyncRetrieve()) {
            throw new UnsupportedOperationException(CACHE_RETRIEVAL_UNSUPPORTED_OPERATION_EXCEPTION_MESSAGE);
        }

        return retrieveValue(key);
    }

    private CompletableFuture<ValueWrapper> retrieveValue(Object key) {

        CompletableFuture<byte[]> retrieve = delegate.getCacheConfiguration().isTimeToIdleEnabled()
                ? getCacheWriter().retrieve(getName(), createAndConvertCacheKey(key), getTimeToLive(key))
                : getCacheWriter().retrieve(getName(), createAndConvertCacheKey(key));

        return retrieve //
                .thenApply(binaryValue -> binaryValue != null ? deserializeCompressCacheValue(binaryValue) : null) //
                .thenApply(this::toValueWrapper);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {

        return retrieve(key).thenCompose(wrapper -> {

            if (wrapper != null) {
                return CompletableFuture.completedFuture((T) wrapper.get());
            }

            return valueLoader.get().thenCompose(value -> {

                Object cacheValue = processAndCheckValue(value);

                byte[] binaryKey = createAndConvertCacheKey(key);
                byte[] binaryValue = serializeCompressCacheValue(cacheValue);

                Duration timeToLive = getTimeToLive(key, cacheValue);

                return getCacheWriter().store(getName(), binaryKey, binaryValue, timeToLive).thenApply(v -> value);
            });
        });
    }
    private Object processAndCheckValue(@Nullable Object value) {

        Object cacheValue = preProcessCacheValue(value);

        if (nullCacheValueIsNotAllowed(cacheValue)) {
            throw new IllegalArgumentException(("Cache '%s' does not allow 'null' values; Avoid storing null"
                    + " via '@Cacheable(unless=\"#result == null\")' or configure RedisCache to allow 'null'"
                    + " via RedisCacheConfiguration").formatted(getName()));
        }

        return cacheValue;
    }


    private boolean nullCacheValueIsNotAllowed(@Nullable Object cacheValue) {
        return cacheValue == null && !isAllowNullValues();
    }

    /**
     * Customization hook called before passing object to
     * {@link RedisSerializer}.
     *
     * @param value can be {@literal null}.
     * @return preprocessed value. Can be {@literal null}.
     */
    @Nullable
    protected Object preProcessCacheValue(@Nullable Object value) {
        return value != null ? value : isAllowNullValues() ? NullValue.INSTANCE : null;
    }


    /**
     * Reset all statistics counters and gauges for this cache.
     *
     * @since 2.4
     */
    public void clearStatistics() {
        getCacheWriter().clearStatistics(getName());
    }

    /**
     * Return the {@link CacheStatistics} snapshot for this cache instance.
     * <p>
     * Statistics are accumulated per cache instance and not from the backing Redis data store.
     *
     * @return {@link CacheStatistics} object for this {@link RedisCache}.
     * @since 2.4
     */
    public CacheStatistics getStatistics() {
        return getCacheWriter().getCacheStatistics(getName());
    }
    @Nullable
    private Object nullSafeDeserializedStoreValue(@Nullable byte[] value) {
        return value != null ? fromStoreValue(deserializeCompressCacheValue(value)) : null;
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {

        Object cacheValue = preProcessCacheValue(value);

        if (nullCacheValueIsNotAllowed(cacheValue)) {
            return get(key);
        }

        Duration timeToLive = getTimeToLive(key, value);

        byte[] binaryKey = createAndConvertCacheKey(key);
        byte[] binaryValue = serializeCompressCacheValue(cacheValue);
        byte[] result = getCacheWriter().putIfAbsent(getName(), binaryKey, binaryValue, timeToLive);

        return result != null ? new SimpleValueWrapper(fromStoreValue(deserializeCompressCacheValue(result))) : null;
    }
}
