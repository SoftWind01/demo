package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    //创建线程池消费消息队列里的任务
    ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    /**
     * 类初始化完成启动线程消费任务
     */
    @PostConstruct
    private void init() {
        try {
            stringRedisTemplate.opsForStream().createGroup("stream.orders","g1");
        }catch (Exception e){
            log.error("队列存在");
        }
        SECKILL_ORDER_EXECUTOR.submit(new voucherOrder());
    }

    private IVoucherOrderService proxy;
    private class voucherOrder implements Runnable{
        @Override
        public void run() {
            while(true){
                try{
                    List<MapRecord<String, Object, Object>> result = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    if(result==null||result.size()==0){
                        continue;
                    }
                    MapRecord<String, Object, Object> mapRecord = result.get(0);
                    Map<Object, Object> entries = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(entries, new VoucherOrder(), true);
                    proxy.createVoucherOrder(voucherOrder);
                    Long ack = stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", mapRecord.getId());
                    log.info("ack确认为：{}",ack);
                }catch (Exception e){
                    log.error("处理订单异常");
                    handlePandingList();
                }

            }
        }

        private void handlePandingList() {

            while(true){
                try {
                    List<MapRecord<String, Object, Object>> result = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    if(result==null||result.size()==0){
                        break;
                    }
                    MapRecord<String, Object, Object> mapRecord = result.get(0);
                    Map<Object, Object> entries = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(entries, new VoucherOrder(), true);
                    proxy.createVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",mapRecord.getId());
                }catch (Exception e){
                    try{
                        Thread.sleep(1000);
                    }catch (InterruptedException e1){
                        e1.printStackTrace();
                    }
                    log.info("处理订单异常");
                }
            }

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
        //创建订单，提交到消息队列
        VoucherOrder voucherOrder = new VoucherOrder();
        Long id=redisWorker.getNextId("voucherOrder");
        voucherOrder.setId(id);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(1);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        Long flag = stringRedisTemplate.execute(seckillVoucher, Collections.emptyList(),voucherId.toString(),userID.toString(),id.toString());
        if(flag==-1||flag==1) return Result.fail("库存不足");
        if(flag==2) return Result.fail("不允许重复购买");
        proxy=(IVoucherOrderService)AopContext.currentProxy();
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
        Long isBought = this.query().eq("user_id", userID).eq("voucher_id",voucherId).count();
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
