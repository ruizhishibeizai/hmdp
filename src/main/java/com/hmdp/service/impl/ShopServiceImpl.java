package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import io.netty.channel.ChannelPromiseAggregator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.beans.Transient;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    /**
     * 获得锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //这里flag如果是空的，直接返回会做Boolean到boolean的拆箱，就会报错
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     * @return
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


    /**
     * 根据id获取店铺信息，并解决缓存穿透和击穿问题
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //Shop shop1 = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 7. 返回
        return Result.ok(shop);
    }


    /**
     * 利用互斥锁解决查询id时缓存击穿的问题
     * @param id
     * @return 店铺信息
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 3.存在，把string数据转成java对象返回
            Shop shop1 = JSONUtil.toBean(shopJson, Shop.class);
            return shop1;
        }
        //判断命中的是否是已经加入redis的空值
        /**
         * 用(shopJson != null)也可以，
         * StrUtil.isNotBlank(shopJson)对于null，“”，“/t/n”
         * 都是返回false，只有有值的字符串才会返回true
         */

        if("".equals(shopJson)){
            return null;
        }

        // 4.redis中不存在，实现缓存重建
        // 4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2判断是否成功
            // 4.3不成功则休眠一段时间后，重新查询redis，不存在则尝试获取锁
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4如果成功再查询redis做二次检查
            String shopJson2 = stringRedisTemplate.opsForValue().get(key);
            // 判断是否存在
            if(StrUtil.isNotBlank(shopJson2)){
                // 存在，把string数据转成java对象返回
                Shop shop2 = JSONUtil.toBean(shopJson, Shop.class);
                return shop2;
            }
            // 4.5二次查询redis也不存在，则查询数据库，并重建redis缓存
            shop = getById(id);

            // 4.6为了测试缓存击穿的通常情况（高并发，缓存重建时间长），
            //用休眠模拟重建延时,用apifox软件模拟高并发
            Thread.sleep(200);

            // 5.数据库中也不存在，返回错误
            if (shop == null){
                /**
                 * 不存在将空值也加入redis，并设置ttl。防止缓存穿透
                 */
                stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6.存在，则保存数据到redis
            stringRedisTemplate.opsForValue().set(
                    key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.重建redis后要释放互斥锁
            unLock(lockKey);
        }

        // 8. 返回
        return shop;
    }
    /**
     * 查询id缓存穿透的解决方案
     * @param id
     * @return 店铺信息
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 3.存在，把string数据转成java对象返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是已经加入redis的空值
        /**
         * 用(shopJson != null)也可以，
         * StrUtil.isNotBlank(shopJson)对于null，“”，“/t/n”
         * 都是返回false，只有有值的字符串才会返回true
         */

        if("".equals(shopJson)){
            return null;
        }

        // 4.不存在，查询数据库
        Shop shop = getById(id);
        // 5.数据库中也不存在，返回错误
        if (shop == null){
            /**
             * 不存在将空值也加入redis，并设置ttl。防止缓存穿透
             */
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，则保存数据到redis
        stringRedisTemplate.opsForValue().set(
                key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7. 返回
        return shop;
    }




    /**
     * 单体系统（数据库和缓存在同一系统上）可用事物来保证原子性
     * @Transactional:添加事物
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        /**
         * 先更新数据库，再删除缓存，可以相对有效保证原子性
         */

        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }

        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
