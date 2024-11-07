package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RegexUtils.isPhoneInvalid;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        //检验手机号是否合法
        if(!isPhoneInvalid(phone)){
            return Result.fail("手机号不合法");
        }
        //生成验证码
        String key = RandomUtil.randomString(6);
        //发送验证码
        log.info(key);
        //保存验证码
        //session.setAttribute(phone+"key",key);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY,key,2, TimeUnit.MINUTES);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // TODO 实现登录功能
        if(loginForm.getPhone() == null||loginForm.getCode() == null||!isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("参数异常");
        }
        //校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY);
        log.info("cacheCode:{}",cacheCode);
        String code = loginForm.getCode();
        if(!code.equals(cacheCode)){
            return Result.fail("验证码错误");
        }
        //校验手机号
        if(!isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号错误");
        }
        //登陆
        User user=userService.query().eq("phone",loginForm.getPhone()).one();
        if(user==null){
            user=new User();
            user.setPhone(loginForm.getPhone());
            user.setNickName(loginForm.getPhone());
            userService.save(user);
        }
        //保存
        //session.setAttribute("loginUser",user);
        Map<String, Object> map = BeanUtil.beanToMap(user,new HashMap<>(), CopyOptions.create()
                .ignoreNullValue()
                .setFieldValueEditor((fieldName, fieldValue) ->
                        fieldValue == null ? "" : fieldValue.toString()));
        String token= UUID.randomUUID().toString();
        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,map);
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(){
        // TODO 实现登出功能
        return Result.fail("功能未完成");
    }

    @GetMapping("/me")
    public Result me(){
        // TODO 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();

        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
}
