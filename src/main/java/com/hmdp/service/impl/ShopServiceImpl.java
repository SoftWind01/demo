package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate ;

    @Override
    public Result queryById(Long id) {
        if(id==null){
            return Result.fail("参数错误");
        }
/*        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            Shop shop = this.getById(id);
            if(shop==null){
                //解决缓存击穿问题:缓存空对象
                shop = new Shop();
            }
            shopJson = JSONUtil.toJsonStr(shop);
            //生成随机数解决缓存雪崩问题
            long randomValue = (long)(1 + Math.random() * (3 - 1));
            log.info("随机数:{}",randomValue);
            stringRedisTemplate.opsForValue().set(key, shopJson,10+randomValue, TimeUnit.MINUTES);
            return Result.ok(shop);
        }
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return Result.ok(shop);*/
        Shop shop = queryWithMutex(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithMutex(Long id) {
        if(id==null){
            return null;
        }
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //缓存未命中,不是热点数据，返回null
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //缓存命中
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject jsonObject =(JSONObject) redisData.getData();
        LocalDateTime localDateTime =redisData.getExpireTime();
        Shop shop = JSONUtil.toBean(jsonObject, Shop.class);
        //缓存没过期
        if(localDateTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //缓存过期
        //获取锁
        String lock=RedisConstants.LOCK_SHOP_KEY+shop.getId();
        boolean isLock = tryLock(lock);
        if(isLock){
            //开启新线程重构缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    saveShop2Redis(id);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lock);
                }
            });
        }
        //返回旧缓存
        return shop;
    }

    public void saveShop2Redis(Long id) throws InterruptedException {
        RedisData redisData = new RedisData();
        Shop shop=getById(id);
        //模拟重构缓存延迟
        Thread.sleep(200);
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(10));
        String key = RedisConstants.CACHE_SHOP_KEY+shop.getId();
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    boolean tryLock(String lock){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lock, "1", 1, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    void unlock(String lock){
        stringRedisTemplate.delete(lock);
    }

    @Override
    public Result update(Shop shop) {
        if(shop==null||shop.getId()==null){
            return Result.fail("参数异常");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}
