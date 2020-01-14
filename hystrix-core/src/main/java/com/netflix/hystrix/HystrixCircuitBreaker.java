/**
 * Copyright 2012 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.hystrix.HystrixCommandMetrics.HealthCounts;
import rx.Subscriber;
import rx.Subscription;

/**
 * Circuit-breaker logic that is hooked into {@link HystrixCommand} execution and will stop allowing executions if failures have gone past the defined threshold.
 * <p>
 * The default (and only) implementation  will then allow a single retry after a defined sleepWindow until the execution
 * succeeds at which point it will again close the circuit and allow executions again.
 */
public interface HystrixCircuitBreaker {

    /**
     * 是否允许HystrixCommand请求执行，如果断路器是half-open（半开）状态，则根据逻辑允许部分请求执行和改变状态。
     * Every {@link HystrixCommand} requests asks this if it is allowed to proceed or not.  It is idempotent and does
     * not modify any internal state, and takes into account the half-open logic which allows some requests through
     * after the circuit has been opened
     * 
     * @return boolean whether a request should be permitted
     */
    boolean allowRequest();

    /**
     * 断路器当前是否打开状态
     * Whether the circuit is currently open (tripped).
     * 
     * @return boolean state of circuit breaker
     */
    boolean isOpen();

    /**
     * half-open（半开）状态的反馈机制，执行成功时调用
     * Invoked on successful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     */
    void markSuccess();

    /**
     * half-open（半开）状态的反馈机制，执行失败调用
     * Invoked on unsuccessful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     */
    void markNonSuccess();

    /**
     * HystrixCommand执行行前，判断是否尝试执行
     * Invoked at start of command execution to attempt an execution.  This is non-idempotent - it may modify internal
     * state.
     */
    boolean attemptExecution();

    /**
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    class Factory {
        // String is HystrixCommandKey.name() (we can't use HystrixCommandKey directly as we can't guarantee it implements hashcode/equals correctly)
        private static ConcurrentHashMap<String, HystrixCircuitBreaker> circuitBreakersByCommand = new ConcurrentHashMap<String, HystrixCircuitBreaker>();

        /**
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey}.
         * <p>
         * This is thread-safe and ensures only 1 {@link HystrixCircuitBreaker} per {@link HystrixCommandKey}.
         * 
         * @param key
         *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @param group
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @param properties
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @param metrics
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key, HystrixCommandGroupKey group, HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            // this should find it for all but the first time
            HystrixCircuitBreaker previouslyCached = circuitBreakersByCommand.get(key.name());
            if (previouslyCached != null) {
                return previouslyCached;
            }

            // if we get here this is the first time so we need to initialize

            // Create and add to the map ... use putIfAbsent to atomically handle the possible race-condition of
            // 2 threads hitting this point at the same time and let ConcurrentHashMap provide us our thread-safety
            // If 2 threads hit here only one will get added and the other will get a non-null response instead.
            HystrixCircuitBreaker cbForCommand = circuitBreakersByCommand.putIfAbsent(key.name(), new HystrixCircuitBreakerImpl(key, group, properties, metrics));
            if (cbForCommand == null) {
                // this means the putIfAbsent step just created a new one so let's retrieve and return it
                return circuitBreakersByCommand.get(key.name());
            } else {
                // this means a race occurred and while attempting to 'put' another one got there before
                // and we instead retrieved it and will now return it
                return cbForCommand;
            }
        }

        /**
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey} or null if none exists.
         * 
         * @param key
         *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key) {
            return circuitBreakersByCommand.get(key.name());
        }

        /**
         * Clears all circuit breakers. If new requests come in instances will be recreated.
         */
        /* package */static void reset() {
            circuitBreakersByCommand.clear();
        }
    }


    /**
     * The default production implementation of {@link HystrixCircuitBreaker}.
     * 
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    /* package */
    class HystrixCircuitBreakerImpl implements HystrixCircuitBreaker {
        // 用户配置定义
        private final HystrixCommandProperties properties;
        // HystrixCommand 执行指标数据
        private final HystrixCommandMetrics metrics;

        // 断路器状态定义
        enum Status {
            CLOSED, OPEN, HALF_OPEN;
        }

        // 断路器当前状态，利用原子操作保证线程安全
        private final AtomicReference<Status> status = new AtomicReference<Status>(Status.CLOSED);
        // 断路器是否打开，利用原子操作保证线程安全，circuitOpened>0则为打开状态，同时该变量记录了打开的时间，用于间隔'circuitBreakerSleepWindowInMilliseconds'重试，
        private final AtomicLong circuitOpened = new AtomicLong(-1);
        // 指标数据订阅
        private final AtomicReference<Subscription> activeSubscription = new AtomicReference<Subscription>(null);

        protected HystrixCircuitBreakerImpl(HystrixCommandKey key, HystrixCommandGroupKey commandGroup, final HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            this.properties = properties;
            this.metrics = metrics;

            //On a timer, this will set the circuit between OPEN/CLOSED as command executions occur
            // 订阅 metrics 指标数据，一旦HystrixCommand执行结束，触发监控更新，断路器'断路器'HystrixCircuitBreaker重新计算并进入'关闭'或'打开'状态
            Subscription s = subscribeToStream();
            activeSubscription.set(s);
        }

        /**
         * 本方法是核心：订阅metrics指标数据事件，计算OPEN/CLOSED状态，并更新
         */
        private Subscription subscribeToStream() {
            /*
             * This stream will recalculate the OPEN/CLOSED status on every onNext from the health stream
             */
            return metrics.getHealthCountsStream()
                    .observe()
                    .subscribe(new Subscriber<HealthCounts>() {
                        @Override
                        public void onCompleted() {

                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onNext(HealthCounts hc) {
                            // check if we are past the statisticalWindowVolumeThreshold
                            if (hc.getTotalRequests() < properties.circuitBreakerRequestVolumeThreshold().get()) {
                                // we are not past the minimum volume threshold for the stat window,
                                // so no change to circuit status.
                                // if it was CLOSED, it stays CLOSED
                                // if it was half-open, we need to wait for a successful command execution
                                // if it was open, we need to wait for sleep window to elapse
                                // 判断一个滑动窗口内'触发熔断'要达到的最小访问次数，没有达到，则不改变状态；
                                // 指标由用户通过'statisticalWindowVolumeThreshold'定义；
                            } else {
                                if (hc.getErrorPercentage() < properties.circuitBreakerErrorThresholdPercentage().get()) {
                                    // we are not past the minimum error threshold for the stat window,
                                    // so no change to circuit status.
                                    // if it was CLOSED, it stays CLOSED
                                    // if it was half-open, we need to wait for a successful command execution
                                    // if it was open, we need to wait for sleep window to elapse
                                    // 判断滑动窗口时间内，错误率是否达到用户指定的错误率，没有达到，则不改变状态
                                    // 指标由用户通过'circuitBreakerErrorThresholdPercentage'定义；
                                } else {
                                    // our failure rate is too high, we need to set the state to OPEN
                                    // 滑动窗口内错误率超过用户指定阈值，断路器进入打开状态，记录打开时间
                                    // 特别注意：这里使用了compareAndSet，不会重复打开
                                    if (status.compareAndSet(Status.CLOSED, Status.OPEN)) {
                                        circuitOpened.set(System.currentTimeMillis());
                                    }
                                }
                            }
                        }
                    });
        }

        @Override
        public void markSuccess() {
            // 半开状态下，尝试执行成功，断路器关闭
            if (status.compareAndSet(Status.HALF_OPEN, Status.CLOSED)) {
                //This thread wins the race to close the circuit - it resets the stream to start it over from 0
                metrics.resetStream();
                Subscription previousSubscription = activeSubscription.get();
                if (previousSubscription != null) {
                    previousSubscription.unsubscribe();
                }
                Subscription newSubscription = subscribeToStream();
                activeSubscription.set(newSubscription);
                circuitOpened.set(-1L);
            }
        }

        @Override
        public void markNonSuccess() {
            // 半开状态，尝试执行失败，断路器打开
            if (status.compareAndSet(Status.HALF_OPEN, Status.OPEN)) {
                //This thread wins the race to re-open the circuit - it resets the start time for the sleep window
                circuitOpened.set(System.currentTimeMillis());
            }
        }

        @Override
        public boolean isOpen() {
            if (properties.circuitBreakerForceOpen().get()) {
                return true;
            }
            if (properties.circuitBreakerForceClosed().get()) {
                return false;
            }
            return circuitOpened.get() >= 0;
        }
        /**
         *  HystrixCommand执行行前，判断是否允许请求
         *
         * @return: boolean
         * @author: Administrator
         * @date: 2020/1/6 14:49
         */
        @Override
        public boolean allowRequest() {
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            if (circuitOpened.get() == -1) {
                // 断路器关闭，可以执行
                return true;
            } else {
                // 断路器半开状态，不允许执行
                if (status.get().equals(Status.HALF_OPEN)) {
                    return false;
                } else {
                    // 根判断是否经过了重试等待时间，即'当前时间'是否大于'断路器打开时间' + 用户指定的'circuitBreakerSleepWindowInMilliseconds'等待重试时间
                    return isAfterSleepWindow();
                }
            }
        }

        private boolean isAfterSleepWindow() {
            final long circuitOpenTime = circuitOpened.get();
            final long currentTime = System.currentTimeMillis();
            final long sleepWindowTime = properties.circuitBreakerSleepWindowInMilliseconds().get();
            return currentTime > circuitOpenTime + sleepWindowTime;
        }

        @Override
        public boolean attemptExecution() {
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            if (circuitOpened.get() == -1) {
                // 断路器半开状态，不允许执行
                return true;
            } else {
                // 根判断是否经过了重试等待时间，即'当前时间'是否大于'断路器打开时间' + 用户指定的'circuitBreakerSleepWindowInMilliseconds'等待重试时间
                if (isAfterSleepWindow()) {
                    // 等待重试时间已到
                    if (status.compareAndSet(Status.OPEN, Status.HALF_OPEN)) {
                        // only the first request after sleep window should execute
                        // 首次请求，断路器状态进入'半开状态'，可以尝试执行HystrixCommand
                        return true;
                    } else {
                        // 非首次进入'半开状态'，禁止执行
                        return false;
                    }
                } else {
                    // 等待重试时间未到，继续禁止执行
                    return false;
                }
            }
        }
    }

    /**
     * An implementation of the circuit breaker that does nothing.
     * 
     * @ExcludeFromJavadoc
     */
    /* package */static class NoOpCircuitBreaker implements HystrixCircuitBreaker {

        @Override
        public boolean allowRequest() {
            return true;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void markSuccess() {

        }

        @Override
        public void markNonSuccess() {

        }

        @Override
        public boolean attemptExecution() {
            return true;
        }
    }

}
