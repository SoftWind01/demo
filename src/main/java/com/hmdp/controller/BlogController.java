package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        blogService.save(blog);
        //推送blog给粉丝
        List<Long> fansIdList = followService.query().select("user_id").eq("follow_user_id", user.getId()).list()
                .stream().map(Follow::getUserId).collect(Collectors.toList());
        for(Long fansId : fansIdList){
            stringRedisTemplate.opsForZSet().add("MailBox:"+fansId,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 修改点赞
        UserDTO user= UserHolder.getUser();
        Long userId=user.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember("isLiked:" + id, userId.toString());
        if(isMember){
            blogService.update().setSql("liked=liked-1").eq("id", id).update();
            stringRedisTemplate.opsForSet().remove("isLiked:" + id, userId.toString());
            stringRedisTemplate.opsForZSet().remove("isLiked:" + id, userId.toString());
        }else{
            blogService.update().setSql("liked=liked+1").eq("id", id).update();
            long epochSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
            double timeStap=epochSecond*1.0;
            stringRedisTemplate.opsForZSet().add("likedRankingList:"+id,userId.toString(),timeStap);
            stringRedisTemplate.opsForSet().add("isLiked:" + id,userId.toString());
        }
        return Result.ok();
    }

    @GetMapping("/likes/{id}")
    public Result likeBlogs(@PathVariable("id") Long id) {
        return blogService.getLiked(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isLiked(blog);
        });
        return Result.ok(records);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        Blog blog = blogService.getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        //查询blog是否被点赞
        isLiked(blog);
        return Result.ok(blog);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(@RequestParam("lastId")Long max,@RequestParam(value = "offset",defaultValue = "0")Integer offset){
        return blogService.queryBlogOfFollow(max,offset);
    }

    private void isLiked(Blog blog){
        UserDTO user= UserHolder.getUser();
        Long userId=user.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember("isLiked:" + blog.getId(), userId.toString());
        if(isMember){
            blog.setIsLike(true);
        }
    }
}
