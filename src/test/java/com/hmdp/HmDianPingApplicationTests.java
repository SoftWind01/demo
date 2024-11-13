package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@SpringBootTest
class HmDianPingApplicationTests {


    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisWorker redisWorker;

    @Test
    void testSaveShop() throws InterruptedException {
        //shopService.saveShop2Redis(1L);
    }

    @Test
    void testRedisWorker() throws InterruptedException {
        long shopOrder = redisWorker.getNextId("shopOrder");
        System.out.println(shopOrder);
    }

    public static void main(String[] args){
        LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        long epochSecond = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(epochSecond);
    }
}
