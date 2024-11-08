package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class RedisCacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将java对象转为json存储在Redis
     * @param key
     * @param value
     * @param expire
     * @param timeUnit
     */
    public void set(String key, Object value,Long expire, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),expire, timeUnit);
    }

    /**
     * 将java对象转为Json存储到Redis并设置逻辑过期
     * @param key
     * @param value
     * @param expire
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object value,Long expire, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expire)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查询缓存，解决了缓存穿透的
     * @param keyPrefix 缓存类型前缀
     * @param id
     * @param type
     * @param dbFallback 查询数据库的逻辑
     * @param expire
     * @param timeUnit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID>R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R>dbFallback,Long expire, TimeUnit timeUnit) {
        String key=keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if(json!=null){
            return null;
        }
        R r = dbFallback.apply(id);
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,r,expire,timeUnit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 解决缓存击穿问题
     * @param id
     * @return
     */
    public <R,ID>R queryWithLogicalExpire(
            String keyPrefix,String lockPrefix, ID id, Class<R> type, Function<ID,R>dbFallback,Long expire, TimeUnit timeUnit) {
        if(id==null){
            return null;
        }
        String key= keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //缓存未命中,不是热点数据，返回null
        if(StrUtil.isBlank(json)){
            return null;
        }
        //缓存命中
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonObject =(JSONObject) redisData.getData();
        LocalDateTime localDateTime =redisData.getExpireTime();
        R r = JSONUtil.toBean(jsonObject, type);
        //缓存没过期
        if(localDateTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //缓存过期
        //获取锁
        String lock=lockPrefix+id;
        boolean isLock = tryLock(lock);
        if(isLock){
            //开启新线程重构缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R rl = dbFallback.apply(id);
                    //写入Redis
                    this.setWithLogicalExpire(key,rl,expire,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lock);
                }
            });
        }
        //返回旧缓存
        return r;
    }


    boolean tryLock(String lock){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lock, "1", 1, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    void unlock(String lock){
        stringRedisTemplate.delete(lock);
    }
}
