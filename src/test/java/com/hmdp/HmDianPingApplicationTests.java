package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.VoucherServiceImpl;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private VoucherServiceImpl voucherService;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisWorker redisWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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

    @Test
    void addShopData() {
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> shopList = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : shopList.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> shops = entry.getValue();
            Map<String,Point> shopMap =new HashMap();
            for(Shop shop : shops){
                Point point=new Point(shop.getX(),shop.getY());
                shopMap.put(shop.getId().toString(),point);
                //stringRedisTemplate.opsForGeo().add("shopGeo:"+typeId,new Point(shop.getX(),shop.getY()),shop.getId().toString());
            }
            stringRedisTemplate.opsForGeo().add("shopGeo:"+typeId,shopMap);
        }
    }

}
