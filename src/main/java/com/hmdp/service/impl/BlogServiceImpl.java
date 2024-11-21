package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;


    @Override
    public Result getLiked(Long id) {
        Set<String> idSet = stringRedisTemplate.opsForZSet().range("likedRankingList:" + id, 0, 4);
        if(idSet==null||idSet.size()==0){
            return  Result.ok(Collections.emptyList());
        }
        List<Long> idList = idSet.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr= StrUtil.join(",",idList);
        List<User> userList = userService.query().in("id", idList).last("Order By Field(id," + idStr + ")").list();
        List<UserDTO> userDTOS = userList.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> set = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores("MailBox:" + userId, 0, max, offset, 2);
        if(set==null||set.size()==0){
            return  Result.ok();
        }
        List<Long> idList=new ArrayList<>(set.size());
        int os=1;
        long minTime=0;
        for(ZSetOperations.TypedTuple<String> tuple:set){
            long score = tuple.getScore().longValue();
            if(score==minTime){
                os++;
            }else{
                minTime=score;
                os=1;
            }
            String value = tuple.getValue();
            idList.add(Long.valueOf(value));
        }
        String idStr= StrUtil.join(",",idList);
        List<Blog> blogList = query().in("id",idList).last("Order By Field(id," + idStr + ")").list();
        ScrollResult sr=new ScrollResult();
        sr.setList(blogList);
        sr.setOffset(os);
        sr.setMinTime(minTime);
        return Result.ok(sr);
    }


}
