package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局id生成工具类
 */
@Component
public class RedisWorker {

    private final static long BEGIN_TIMESTAMP=1704067200L;
    private final static int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    RedisWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     *
     * @param keyPrefix 业务前缀
     * @return id
     */
    public long getNextId(String keyPrefix) {
        //时间戳
        LocalDateTime now = LocalDateTime.now();
        long timeStamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        //序列号
        String data = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix +":"+ data);
        //拼接返回
        return timeStamp<<COUNT_BITS | count;
    }


}
