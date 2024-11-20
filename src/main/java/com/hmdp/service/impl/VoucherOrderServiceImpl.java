package com.hmdp.service.impl;


import com.hmdp.dto.Result;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

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

    private static final DefaultRedisScript<Long> seckillVoucher;
    static {
        seckillVoucher=new DefaultRedisScript<Long>();
        seckillVoucher.setLocation(new ClassPathResource("seckillVoucher.lua"));
        seckillVoucher.setResultType(Long.class);
    }

    //模拟消息队列
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    //创建线程池消费消息队列里的任务
    ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    /**
     * 类初始化完成启动线程消费任务
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new voucherOrder());
    }

    private class voucherOrder implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    voucherOrderHandler(voucherOrder);
                } catch (InterruptedException e) {
                    log.error(e.getMessage());
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private IVoucherOrderService proxy;

    /**
     * 线程处理订单函数
     * @param voucherOrder
     */
    private void voucherOrderHandler(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    /**
     * 秒杀抢券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId){
        Long userID=UserHolder.getUser().getId();
        Long flag = stringRedisTemplate.execute(seckillVoucher, Collections.emptyList(),voucherId.toString(),userID.toString());
        if(flag==-1||flag==1) return Result.fail("库存不足");
        if(flag==2) return Result.fail("不允许重复购买");
        //创建订单，提交到消息队列
        VoucherOrder voucherOrder = new VoucherOrder();
        Long id=redisWorker.getNextId("voucherOrder");
        voucherOrder.setId(id);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(1);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        proxy=(IVoucherOrderService)AopContext.currentProxy();
        orderTasks.add(voucherOrder);
        return Result.ok(id);
    }

    /**
     * 内部调用，创建订单
     * @param voucherOrder
     * @return
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        long userID=voucherOrder.getId();
        long voucherId=voucherOrder.getVoucherId();
        Integer isBought = this.query().eq("user_id", userID).eq("voucher_id",voucherId).count();
        if(isBought != 0){
            //不允许重复购买
            return ;
        }
        //扣减库存
        //乐观锁思想解决超卖问题
        boolean success=seckillVoucherServiceImpl.update().
                setSql("stock=stock-1").
                eq("voucher_id",voucherId)
                .ge("stock",1).
                update();
        if(!success){
            //没有库存
            return ;
        }
        //保存订单信息到数据库
        this.save(voucherOrder);
        return ;
    }

}
