package com.hmdp.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long id,@PathVariable("isFollow")Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        if(isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            followService.save(follow);
        }else {
            QueryWrapper<Follow> queryWrapper = new QueryWrapper();
            queryWrapper.eq("user_id", userId).eq("follow_user_id", id);
            followService.remove(queryWrapper);
        }
        return Result.ok();
    }

    @GetMapping("/or/not/{id}")
    public Result getFollow(@PathVariable("id") Long id) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        QueryWrapper<Follow> queryWrapper = new QueryWrapper();
        queryWrapper.eq("user_id", userId).eq("follow_user_id", id);
        return Result.ok(followService.count(queryWrapper));
    }

    @GetMapping("/common/{id}")
    public Result getCommonFollow(@PathVariable("id") Long id) {
        return followService.getCommonFollow(id);
    }

}
