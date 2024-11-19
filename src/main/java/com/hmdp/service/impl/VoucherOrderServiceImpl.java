package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
        synchronized (id.toString().intern()){
            //获取代理对象
            VoucherOrderServiceImpl currentProxy = (VoucherOrderServiceImpl)AopContext.currentProxy();
            return currentProxy.createOrder(seckillVoucher);
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

}
