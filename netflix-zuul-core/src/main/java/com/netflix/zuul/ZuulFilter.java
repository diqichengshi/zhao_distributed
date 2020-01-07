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

import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.zuul.monitoring.Tracer;
import com.netflix.zuul.monitoring.TracerFactory;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.*;

/**
 * Base abstract class for ZuulFilters. The base class defines abstract methods to define:
 * filterType() - to classify a filter by type. Standard types in Zuul are "pre" for pre-routing filtering,
 * "route" for routing to an origin, "post" for post-routing filters, "error" for error handling.
 * We also support a "static" type for static responses see  StaticResponseFilter.
 * Any filterType made be created or added and run by calling FilterProcessor.runFilters(type)
 * <p/>
 * filterOrder() must also be defined for a filter. Filters may have the same  filterOrder if precedence is not
 * important for a filter. filterOrders do not need to be sequential.
 * <p/>
 * ZuulFilters may be disabled using Archius Properties.
 * <p/>
 * By default ZuulFilters are static; they don't carry state. This may be overridden by overriding the isStaticFilter() property to false
 *
 * @author Mikey Cohen
 *         Date: 10/26/11
 *         Time: 4:29 PM
 */
public abstract class ZuulFilter implements IZuulFilter, Comparable<ZuulFilter> {

    // netflix的配置组件，实际上就是基于配置文件提取的指定key的值
    private final DynamicBooleanProperty filterDisabled =
            DynamicPropertyFactory.getInstance().getBooleanProperty(disablePropertyName(), false);

    /**
     * 定义Filter的类型
     * to classify a filter by type. Standard types in Zuul are "pre" for pre-routing filtering,
     * "route" for routing to an origin, "post" for post-routing filters, "error" for error handling.
     * We also support a "static" type for static responses see  StaticResponseFilter.
     * Any filterType made be created or added and run by calling FilterProcessor.runFilters(type)
     *
     * @return A String representing that type
     */
    abstract public String filterType();

    /**
     * 定义当前Filter实例执行的顺序
     * filterOrder() must also be defined for a filter. Filters may have the same  filterOrder if precedence is not
     * important for a filter. filterOrders do not need to be sequential.
     *
     * @return the int order of a filter
     */
    abstract public int filterOrder();

    /**
     * 是否静态的Filter，静态的Filter是无状态的
     * By default ZuulFilters are static; they don't carry state. This may be overridden by overriding the isStaticFilter() property to false
     *
     * @return true by default
     */
    public boolean isStaticFilter() {
        return true;
    }

    /**
     * 禁用当前Filter的配置属性的Key名称
     * Key=zuul.${全类名}.${filterType}.disable
     * The name of the Archaius property to disable this filter. by default it is zuul.[classname].[filtertype].disable
     *
     * @return
     */
    public String disablePropertyName() {
        return "zuul." + this.getClass().getSimpleName() + "." + filterType() + ".disable";
    }

    /**
     * 判断当前的Filter是否禁用，通过disablePropertyName方法从配置中读取，默认是不禁用，也就是启用
     * If true, the filter has been disabled by archaius and will not be run
     *
     * @return
     */
    public boolean isFilterDisabled() {
        return filterDisabled.get();
    }

    /**
     * 这个是核心方法，执行Filter，如果Filter不是禁用、并且满足执行时机则调用run方法，返回执行结果，记录执行轨迹
     * runFilter checks !isFilterDisabled() and shouldFilter(). The run() method is invoked if both are true.
     *
     * @return the return from ZuulFilterResult
     */
    public ZuulFilterResult runFilter() {
        ZuulFilterResult zr = new ZuulFilterResult();
        if (!isFilterDisabled()) {
            if (shouldFilter()) {
                Tracer t = TracerFactory.instance().startMicroTracer("ZUUL::" + this.getClass().getSimpleName());
                try {
                    Object res = run();
                    zr = new ZuulFilterResult(res, ExecutionStatus.SUCCESS);
                } catch (Throwable e) {
                    t.setName("ZUUL::" + this.getClass().getSimpleName() + " failed");
                    zr = new ZuulFilterResult(ExecutionStatus.FAILED);
                    // 注意这里只保存异常的实例，即使执行抛出异常
                    zr.setException(e);
                } finally {
                    t.stopAndLog();
                }
            } else {
                zr = new ZuulFilterResult(ExecutionStatus.SKIPPED);
            }
        }
        return zr;
    }

    // 实现Comparable，基于filterOrder升序排序，也就是filterOrder越大，执行优先度越低
    public int compareTo(ZuulFilter filter) {
        return Integer.compare(this.filterOrder(), filter.filterOrder());
    }

    public static class TestUnit {
        @Mock
        private ZuulFilter f1;
        @Mock
        private ZuulFilter f2;

        @Before
        public void before() {
            MockitoAnnotations.initMocks(this);
        }

        @Test
        public void testSort() {

            when(f1.filterOrder()).thenReturn(1);
            when(f2.filterOrder()).thenReturn(10);
            when(f1.compareTo(any(ZuulFilter.class))).thenCallRealMethod();
            when(f2.compareTo(any(ZuulFilter.class))).thenCallRealMethod();

            ArrayList<ZuulFilter> list = new ArrayList<ZuulFilter>();
            list.add(f2);
            list.add(f1);

            Collections.sort(list);

            assertSame(f1, list.get(0));
        }

        @Test
        public void testShouldFilter() {
            class TestZuulFilter extends ZuulFilter {

                @Override
                public String filterType() {
                    return null;
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

            TestZuulFilter tf1 = spy(new TestZuulFilter());
            TestZuulFilter tf2 = spy(new TestZuulFilter());

            when(tf1.shouldFilter()).thenReturn(true);
            when(tf2.shouldFilter()).thenReturn(false);

            try {
                tf1.runFilter();
                tf2.runFilter();
                verify(tf1, times(1)).run();
                verify(tf2, times(0)).run();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }

        }

        @Test
        public void testIsFilterDisabled() {
            class TestZuulFilter extends ZuulFilter {

                @Override
                public String filterType() {
                    return null;
                }

                @Override
                public int filterOrder() {
                    return 0;
                }

                public boolean isFilterDisabled() {
                    return false;
                }

                public boolean shouldFilter() {
                    return true;
                }

                public Object run() {
                    return null;
                }
            }

            TestZuulFilter tf1 = spy(new TestZuulFilter());
            TestZuulFilter tf2 = spy(new TestZuulFilter());

            when(tf1.isFilterDisabled()).thenReturn(false);
            when(tf2.isFilterDisabled()).thenReturn(true);

            try {
                tf1.runFilter();
                tf2.runFilter();
                verify(tf1, times(1)).run();
                verify(tf2, times(0)).run();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }

        }

    }
}
