package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherServiceImpl;
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<Long>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    /**
     * 秒杀抢券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId){
        //查询数据库
        SeckillVoucher seckillVoucher = seckillVoucherServiceImpl.getById(voucherId);
        if(seckillVoucher == null){
            return Result.fail("秒杀券不存在");
        }
        //判断秒杀是否开始
        if(LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())){
            //秒杀尚未开始
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if(LocalDateTime.now().isAfter(seckillVoucher.getEndTime())){
            //秒杀已经结束
            return Result.fail("秒杀已经结束");
        }
        if(seckillVoucher.getStock()<1){
            //无余量
            return Result.fail("秒杀券没有余量");
        }
        Long id = UserHolder.getUser().getId();
        //获取锁
        RLock lock = redissonClient.getLock("lock:seckillVoucher:" + id);
        boolean isLock = lock.tryLock();
        if(!isLock){
            return Result.fail("获取锁失败");
        }
        try{
            //获取代理对象
            VoucherOrderServiceImpl currentProxy = (VoucherOrderServiceImpl)AopContext.currentProxy();
            return currentProxy.createOrder(seckillVoucher);
        }finally {
            lock.unlock();
        }

    }

    /**
     * 内部调用，创建订单
     * @param seckillVoucher
     * @return
     */
    @Transactional
    public Result createOrder(SeckillVoucher seckillVoucher){
        long userID=UserHolder.getUser().getId();
        long voucherId=seckillVoucher.getVoucherId();
        Integer isBought = this.query().eq("user_id", userID).eq("voucher_id",voucherId).count();
        if(isBought != 0){
            //不允许重复购买
            return Result.fail("不允许重复购买");
        }

        //扣减库存
        log.info("{}",seckillVoucher.getStock());
        //乐观锁思想解决超卖问题
        boolean success=seckillVoucherServiceImpl.update().
                setSql("stock=stock-1").
                eq("voucher_id",voucherId)
                .ge("stock",1).
                update();
        if(!success){
            //没有库存
            return Result.fail("秒杀券没有余量");
        }
        //保存订单信息到数据库
        VoucherOrder voucherOrder = new VoucherOrder();
        Long id=redisWorker.getNextId("voucherOrder");
        voucherOrder.setId(id);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(1);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        this.save(voucherOrder);
        return Result.ok();
    }

    private boolean tryLock(long userID){
        long threadId=Thread.currentThread().getId();
        String lock="lock:seckillVoucher:"+userID;
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lock, threadId+"", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(long userID){
//        String id=stringRedisTemplate.opsForValue().get(lock);
//        if(threadId.equals(id)){
//            stringRedisTemplate.delete(lock);
//        }
        stringRedisTemplate.execute(UNLOCK_SCRIPT,Collections.singletonList("lock:seckillVoucher:"+userID),Thread.currentThread().getId()+"");
    }

}
