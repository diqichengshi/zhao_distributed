/*
 * Copyright 2013 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package com.netflix.zuul;

import com.netflix.zuul.filters.FilterRegistry;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This class is one of the core classes in Zuul. It compiles, loads from a File, and checks if source code changed.
 * It also holds ZuulFilters by filterType.
 *
 * @author Mikey Cohen
 *         Date: 11/3/11
 *         Time: 1:59 PM
 */
public class FilterLoader {
    // 静态final实例，注意到访问权限是包许可，实际上就是饿汉式单例
    final static FilterLoader INSTANCE = new FilterLoader();

    private static final Logger LOG = LoggerFactory.getLogger(FilterLoader.class);

    // 缓存Filter名称(主要是从文件加载，名称为绝对路径 + 文件名的形式)->Filter最后修改时间戳的映射
    private final ConcurrentHashMap<String, Long> filterClassLastModified = new ConcurrentHashMap<String, Long>();
    // 缓存Filter名字->Filter代码的映射，实际上这个Map只使用到get方法进行存在性判断，一直是一个空的结构
    private final ConcurrentHashMap<String, String> filterClassCode = new ConcurrentHashMap<String, String>();
    // 缓存Filter名字->Filter名字的映射，用于存在性判断
    private final ConcurrentHashMap<String, String> filterCheck = new ConcurrentHashMap<String, String>();
    // 缓存Filter类型名称->List<ZuulFilter>的映射
    private final ConcurrentHashMap<String, List<ZuulFilter>> hashFiltersByType = new ConcurrentHashMap<String, List<ZuulFilter>>();

    // 前面提到的ZuulFilter全局缓存的单例
    private FilterRegistry filterRegistry = FilterRegistry.instance();

    // 动态代码编译器实例，Zuul提供的默认实现是GroovyCompiler
    static DynamicCodeCompiler COMPILER;

    // ZuulFilter的工厂类
    static FilterFactory FILTER_FACTORY = new DefaultFilterFactory();

    // 下面三个方法说明DynamicCodeCompiler、FilterRegistry、FilterFactory可以被覆盖
    /**
     * Sets a Dynamic Code Compiler
     *
     * @param compiler
     */
    public void setCompiler(DynamicCodeCompiler compiler) {
        COMPILER = compiler;
    }

    // overidden by tests
    public void setFilterRegistry(FilterRegistry r) {
        this.filterRegistry = r;
    }

    /**
     * Sets a FilterFactory
     * 
     * @param factory
     */
    public void setFilterFactory(FilterFactory factory) {
        FILTER_FACTORY = factory;
    }
    
    /**
     * 饿汉式单例获取自身实例
     * @return Singleton FilterLoader
     */
    public static FilterLoader getInstance() {
        return INSTANCE;
    }

    /**
     * 通过ZuulFilter的类代码和Filter名称获取ZuulFilter实例
     * Given source and name will compile and store the filter if it detects that the filter code has changed or
     * the filter doesn't exist. Otherwise it will return an instance of the requested ZuulFilter
     *
     * @param sCode source code
     * @param sName name of the filter
     * @return the ZuulFilter
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    @SuppressWarnings("rawtypes")
    public ZuulFilter getFilter(String sCode, String sName) throws Exception {
        // 检查filterCheck是否存在相同名字的Filter，如果存在说明已经加载过
        if (filterCheck.get(sName) == null) {
            // filterCheck中放入Filter名称
            filterCheck.putIfAbsent(sName, sName);
            // filterClassCode中不存在加载过的Filter名称对应的代码
            if (!sCode.equals(filterClassCode.get(sName))) {
                LOG.info("reloading code " + sName);
                // 从全局缓存中移除对应的Filter
                filterRegistry.remove(sName);
            }
        }
        ZuulFilter filter = filterRegistry.get(sName);
        // 如果全局缓存中不存在对应的Filter，就使用DynamicCodeCompiler加载代码，使用FilterFactory实例化ZuulFilter
        // 注意加载的ZuulFilter类不能是抽象的，必须是继承了ZuulFilter的子类
        if (filter == null) {
            Class clazz = COMPILER.compile(sCode, sName);
            if (!Modifier.isAbstract(clazz.getModifiers())) {
                filter = (ZuulFilter) FILTER_FACTORY.newInstance(clazz);
            }
        }
        return filter;

    }

    /**
     * 返回所有缓存的ZuulFilter实例的总数量
     * @return the total number of Zuul filters
     */
    public int filterInstanceMapSize() {
        return filterRegistry.size();
    }


    /**
     * 通过文件加加载ZuulFilter
     * From a file this will read the ZuulFilter source code, compile it, and add it to the list of current filters
     * a true response means that it was successful.
     *
     * @param file
     * @return true if the filter in file successfully read, compiled, verified and added to Zuul
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws IOException
     */
    @SuppressWarnings("rawtypes")
    public boolean putFilter(File file) throws Exception {
        // Filter名称为文件的绝对路径+文件名(这里其实绝对路径已经包含文件名，这里再加文件名的目的不明确)
        String sName = file.getAbsolutePath() + file.getName();
        // 如果文件被修改过则从全局缓存从移除对应的Filter以便重新加载
        if (filterClassLastModified.get(sName) != null && (file.lastModified() != filterClassLastModified.get(sName))) {
            LOG.debug("reloading filter " + sName);
            filterRegistry.remove(sName);
        }
        // 下面的逻辑和上一个方法类似
        ZuulFilter filter = filterRegistry.get(sName);
        if (filter == null) {
            Class clazz = COMPILER.compile(file);
            if (!Modifier.isAbstract(clazz.getModifiers())) {
                filter = (ZuulFilter) FILTER_FACTORY.newInstance(clazz);
                List<ZuulFilter> list = hashFiltersByType.get(filter.filterType());
                // 这里说明了一旦文件有修改，hashFiltersByType中对应的当前文件加载出来的Filter类型的缓存要移除，原因见下一个方法
                if (list != null) {
                    hashFiltersByType.remove(filter.filterType()); //rebuild this list
                }
                filterRegistry.put(file.getAbsolutePath() + file.getName(), filter);
                filterClassLastModified.put(sName, file.lastModified());
                return true;
            }
        }

        return false;
    }

    /**
     * 通过Filter类型获取同类型的所有ZuulFilter
     * Returns a list of filters by the filterType specified
     *
     * @param filterType
     * @return a List<ZuulFilter>
     */
    public List<ZuulFilter> getFiltersByType(String filterType) {

        List<ZuulFilter> list = hashFiltersByType.get(filterType);
        if (list != null) return list;

        list = new ArrayList<ZuulFilter>();

        // 如果hashFiltersByType缓存被移除，这里从全局缓存中加载所有的ZuulFilter，按照指定类型构建一个新的列表
        Collection<ZuulFilter> filters = filterRegistry.getAllFilters();
        for (Iterator<ZuulFilter> iterator = filters.iterator(); iterator.hasNext(); ) {
            ZuulFilter filter = iterator.next();
            if (filter.filterType().equals(filterType)) {
                list.add(filter);
            }
        }
        // 注意这里会进行排序，是基于filterOrder
        Collections.sort(list); // sort by priority

        // 这里总是putIfAbsent，这就是为什么上个方法可以放心地在修改的情况下移除指定Filter类型中的全部缓存实例的原因
        hashFiltersByType.putIfAbsent(filterType, list);
        return list;
    }


    public static class TestZuulFilter extends ZuulFilter {

        public TestZuulFilter() {
            super();
        }

        @Override
        public String filterType() {
            return "test";
        }

        @Override
        public int filterOrder() {
            return 0;
        }

        public boolean shouldFilter() {
            return false;
        }

        public Object run() {
            return null;
        }
    }


    public static class UnitTest {

        @Mock
        File file;

        @Mock
        DynamicCodeCompiler compiler;

        @Mock
        FilterRegistry registry;

        FilterLoader loader;

        TestZuulFilter filter = new TestZuulFilter();

        @Before
        public void before() {
            MockitoAnnotations.initMocks(this);

            loader = spy(new FilterLoader());
            loader.setCompiler(compiler);
            loader.setFilterRegistry(registry);
        }

        @Test
        public void testGetFilterFromFile() throws Exception {
            doReturn(TestZuulFilter.class).when(compiler).compile(file);
            assertTrue(loader.putFilter(file));
            verify(registry).put(any(String.class), any(ZuulFilter.class));
        }

        @Test
        public void testGetFiltersByType() throws Exception {
            doReturn(TestZuulFilter.class).when(compiler).compile(file);
            assertTrue(loader.putFilter(file));

            verify(registry).put(any(String.class), any(ZuulFilter.class));

            final List<ZuulFilter> filters = new ArrayList<ZuulFilter>();
            filters.add(filter);
            when(registry.getAllFilters()).thenReturn(filters);

            List< ZuulFilter > list = loader.getFiltersByType("test");
            assertTrue(list != null);
            assertTrue(list.size() == 1);
            ZuulFilter filter = list.get(0);
            assertTrue(filter != null);
            assertTrue(filter.filterType().equals("test"));
        }


        @Test
        public void testGetFilterFromString() throws Exception {
            String string = "";
            doReturn(TestZuulFilter.class).when(compiler).compile(string, string);
            ZuulFilter filter = loader.getFilter(string, string);

            assertNotNull(filter);
            assertTrue(filter.getClass() == TestZuulFilter.class);
//            assertTrue(loader.filterInstanceMapSize() == 1);
        }


    }


}
