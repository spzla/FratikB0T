/*
 * Copyright (C) 2019-2021 FratikB0T Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.fratik.core.cache;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.common.reflect.TypeToken;
import gg.amy.pgorm.annotations.PrimaryKey;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;
import pl.fratik.core.entity.DatabaseEntity;
import pl.fratik.core.event.DatabaseUpdateEvent;
import pl.fratik.core.util.GsonUtil;
import pl.fratik.core.util.NamedThreadFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public class RedisCacheManager {
    private final String PREFIX;
    @Getter(AccessLevel.PACKAGE) private final JedisPool jedisPool;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, new NamedThreadFactory("RedisCacheManager-AsyncThread"));

    public RedisCacheManager(long id) {
        GenericObjectPoolConfig pc = new GenericObjectPoolConfig();
        pc.setMinIdle(16);
        pc.setMaxIdle(32);
        pc.setMaxIdle(32);
        PREFIX = "FB0T-" + id;
        this.jedisPool = new JedisPool(pc, "localhost");
    }

    public RedisCacheManager(JedisPool jedisPool, long id) {
        PREFIX = "FB0T-" + id;
        this.jedisPool = jedisPool;
    }

    public <T> T get(String key, TypeToken<T> holds, String customName, Function<? super String, ? extends T> mappingFunction) {
        return get(key, holds, customName, mappingFunction, 300);
    }

    public <T> T get(String key, TypeToken<T> holds, String customName) {
        return getRaw(getDbkey(key, holds, customName), holds);
    }

    public <T> T getRaw(String dbkey, TypeToken<T> holds) {
        try (Jedis jedis = jedisPool.getResource()) {
            String dane = jedis.get(dbkey);
            if (dane == null) return null;
            return GsonUtil.fromJSON(dane, holds.getType());
        }
    }

    public <T> T get(String key, TypeToken<T> holds, String customName, Function<? super String, ? extends T> mappingFunction, int expiry) {
        try (Jedis jedis = jedisPool.getResource()) {
            String dbkey = getDbkey(key, holds, customName);
            String dane = jedis.get(dbkey);
            if (dane == null) {
                T v = mappingFunction.apply(key);
                jedis.set(dbkey, GsonUtil.toJSON(v));
                if (expiry > 0) {
                    scheduleAsync(() -> {
                        try (Jedis j = jedisPool.getResource()) {
                            j.expire(dbkey, expiry);
                        }
                    });
                }
                return v;
            }
            return GsonUtil.fromJSON(dane, holds.getType());
        }
    }

    public <T> void putAll(TypeToken<T> holds, String customName, Map<? extends String, ? extends T> map) {
        putAll(holds, customName, map, 300);
    }

    public <T> void putAll(TypeToken<T> holds, String customName, Map<? extends String, ? extends T> map, int expiry) {
        try (Jedis jedis = jedisPool.getResource()) {
            for (Map.Entry<? extends String, ? extends T> ent : map.entrySet()) {
                String dbkey = getDbkey(ent.getKey(), holds, customName);
                jedis.set(dbkey, GsonUtil.toJSON(ent.getValue()));
                if (expiry > 0) {
                    scheduleAsync(() -> {
                        try (Jedis j = jedisPool.getResource()) {
                            j.expire(dbkey, expiry);
                        }
                    });
                }
            }
        }
    }

    public <T> void put(String key, TypeToken<T> holds, String customName, T value) {
        put(key, holds, customName, value, 300);
    }

    public <T> void put(String key, TypeToken<T> holds, String customName, T value, int expiry) {
        try (Jedis jedis = jedisPool.getResource()) {
            String dbkey = getDbkey(key, holds, customName);
            jedis.set(dbkey, GsonUtil.toJSON(value));
            if (expiry > 0) {
                scheduleAsync(() -> {
                    try (Jedis j = jedisPool.getResource()) {
                        j.expire(dbkey, expiry);
                    }
                });
            }
        }
    }

    private void scheduleAsync(Runnable r) {
        executor.execute(r);
    }

    public <T> void invalidate(Object key, Class<T> holds, String customName) {
        try (Jedis jedis = jedisPool.getResource()) {
            String dbkey = getDbkey(key, holds, customName);
            jedis.del(dbkey);
        }
    }

    public <T> void invalidate(Object key, TypeToken<T> holds, String customName) {
        try (Jedis jedis = jedisPool.getResource()) {
            String dbkey = getDbkey(key, holds, customName);
            jedis.del(dbkey);
        }
    }

    public <T> void invalidateAll(Iterable<?> keys, TypeToken<T> holds, String customName) {
        List<String> str = new ArrayList<>();
        for (Object key : keys) {
            String dbkey = getDbkey(key, holds, customName);
            str.add(dbkey);
        }
        invalidateAllRaw(str);
    }

    public void invalidateAllRaw(Iterable<?> dbKeys) {
        List<String> str = new ArrayList<>();
        for (Object dbkey : dbKeys)
            str.add(dbkey.toString());
        if (str.isEmpty()) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(str.toArray(new String[]{}));
        }
    }

    public <T> long ttl(Object key, TypeToken<T> holds, String customName) {
        try (Jedis jedis = jedisPool.getResource()) {
            String dbkey = getDbkey(key, holds, customName);
            return jedis.ttl(dbkey);
        }
    }

    @NotNull
    private <T> String getDbkey(Object key, Class<T> holds, String customName) {
        final StringBuilder sb = new StringBuilder(PREFIX + "::" + holds.getSimpleName() + ":");
        if (customName != null) sb.append(':').append(customName).append(':');
        return sb.append(key).toString();
    }

    @NotNull
    private <T> String getDbkey(Object key, TypeToken<T> holds, String customName) {
        StringBuilder tak = new StringBuilder(PREFIX + "::" + holds.getRawType().getSimpleName());
        if (holds.getType() instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) holds.getType()).getActualTypeArguments();
            if (args.length > 0) {
                for (Type t : args) tak.append(":").append(resolveTypeSimpleName(t));
            }
        }
        if (customName != null) tak.append("::").append(customName);
        return tak.append(':').append(key).toString();
    }

    private String resolveTypeSimpleName(Type t) {
        if (t instanceof Class) return ((Class<?>) t).getSimpleName();
        else if (t instanceof ParameterizedType) {
            StringBuilder tak = new StringBuilder();
            Type[] args = ((ParameterizedType) t).getActualTypeArguments();
            if (args.length > 0) {
                for (Type t2 : args) {
                    tak.append(":").append(resolveTypeSimpleName(t2));
                }
            }
            return tak.toString();
        }
        return t.getTypeName();
    }

    public List<String> scanAll(TypeToken<?> holds, String customName) {
        return scanAll("*", holds, customName);
    }

    public List<String> scanAll(String pattern, TypeToken<?> holds, String customName) {
        List<String> keys = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            String match = getDbkey(pattern, holds, customName);
            String cursor = "0";
            do {
                ScanResult<String> xd = jedis.scan(cursor, new ScanParams().match(match));
                keys.addAll(xd.getResult());
                cursor = xd.getStringCursor();
            } while (!cursor.equals("0"));
        }
        return keys;
    }

    public abstract class CacheRetriever<T> {
        private final String customName;
        private boolean canHandleErrors = false;

        public CacheRetriever() {
            this(null);
        }

        public CacheRetriever(String customName) {
            this.customName = customName;
        }

        public RedisCache<T> getCache() {
            return getCache(300);
        }
        public RedisCache<T> getCache(int expiry) {
            return new RedisCache<>(RedisCacheManager.this, new TypeToken<T>(getClass()) {}, expiry, canHandleErrors, customName);
        }
        public CacheRetriever<T> setCanHandleErrors(boolean canHandleErrors) {
            this.canHandleErrors = canHandleErrors;
            return this;
        }
    }

    @Subscribe
    @AllowConcurrentEvents
    public void onDatabaseUpdate(DatabaseUpdateEvent e) {
        DatabaseEntity de = e.getEntity();
        Field field = null;
        for (Field f : de.getClass().getDeclaredFields()) {
            if (f.getAnnotation(PrimaryKey.class) == null) continue;
            field = f;
            break;
        }
        if (field == null) {
            LoggerFactory.getLogger(getClass()).warn("nie znaleziono field'a z PrimaryKey");
            return;
        }
        try {
            field.setAccessible(true);
            invalidate(field.get(de), de.getClass(), null);
        } catch (Exception ex) {
            LoggerFactory.getLogger(getClass()).warn("wielki błąd", ex);
        }
    }
}
