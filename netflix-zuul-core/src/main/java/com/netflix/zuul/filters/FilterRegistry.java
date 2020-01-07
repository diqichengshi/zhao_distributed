package com.netflix.zuul.filters;


import com.netflix.zuul.ZuulFilter;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mhawthorne
 */
public class FilterRegistry {
    /**
     * 饿汉式单例，确保全局只有一个ZuulFilter的缓存
     */
    private static final FilterRegistry INSTANCE = new FilterRegistry();

    public static final FilterRegistry instance() {
        return INSTANCE;
    }

    /**
     * 缓存字符串到ZuulFilter实例的映射关系，如果是从文件加载，字符串key的格式是：文件绝对路径 + 文件名，当然也可以自实现
     */
    private final ConcurrentHashMap<String, ZuulFilter> filters = new ConcurrentHashMap<String, ZuulFilter>();

    private FilterRegistry() {
    }

    public ZuulFilter remove(String key) {
        return this.filters.remove(key);
    }

    public ZuulFilter get(String key) {
        return this.filters.get(key);
    }

    public void put(String key, ZuulFilter filter) {
        this.filters.putIfAbsent(key, filter);
    }

    public int size() {
        return this.filters.size();
    }

    public Collection<ZuulFilter> getAllFilters() {
        return this.filters.values();
    }

}
