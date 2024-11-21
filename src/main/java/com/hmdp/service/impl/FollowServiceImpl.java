package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private IUserService userService;

    @Override
    public Result getCommonFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        List<Long> followList = query().select("follow_user_id").eq("user_id", userId).list()
                .stream().map(follow -> follow.getFollowUserId()).collect(Collectors.toList());
        List<Long> commonIdList = query().select("follow_user_id").eq("user_id", id).in("follow_user_id", followList).list()
                .stream().map(follow -> follow.getFollowUserId()).collect(Collectors.toList());
        List<User> userList = userService.listByIds(commonIdList);
        List<UserDTO> userDTOS = userList.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
