package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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

    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 3.存在，把string数据转成java对象返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //判断命中的是否是已经加入redis的空值
        /**
         * 用(shopJson != null)也可以，
         * StrUtil.isNotBlank(shopJson)对于null，“”，“/t/n”
         * 都是返回false，只有有值的字符串才会返回true
         */

        if("".equals(shopJson)){
            return Result.fail("店铺不存在");
        }

        // 4.不存在，查询数据库
        Shop shop = getById(id);
        // 5.数据库中也不存在，返回错误
        if (shop == null){
            /**
             * 不存在将空值也加入redis，并设置ttl。防止缓存穿透
             */
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        // 6.存在，则保存数据到redis
        stringRedisTemplate.opsForValue().set(
                key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7. 返回
        return Result.ok(shop);
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
