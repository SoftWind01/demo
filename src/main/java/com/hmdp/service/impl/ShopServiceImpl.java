package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(!StrUtil.isBlank(shopJson)){
            //缓存命中
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //缓存未命中
        //获取锁
        String lock=RedisConstants.LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean islock = tryLock(lock);
            if(!islock){
                //获取锁失败
                Thread.sleep(100);
                queryById(id);
            }
            //获取锁成功
            //再次检查缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(!StrUtil.isBlank(shopJson)){
                //缓存命中
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return Result.ok(shop);
            }
            //缓存未命中,重建缓存
            shop = this.getById(id);
            //模拟延迟
            Thread.sleep(200);
            if(shop==null){
                //解决缓存击穿问题:缓存空对象
                shop = new Shop();
            }
            shopJson = JSONUtil.toJsonStr(shop);
            //生成随机数解决缓存雪崩问题
            long randomValue = (long)(1 + Math.random() * (3 - 1));
            log.info("随机数:{}",randomValue);
            stringRedisTemplate.opsForValue().set(key, shopJson,10+randomValue, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unlock(lock);
        }
        return Result.ok(shop);
    }

    private boolean tryLock(String lock){
        Boolean bool = stringRedisTemplate.opsForValue().setIfAbsent(lock, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(bool);
    }

    private void unlock(String lock){
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
