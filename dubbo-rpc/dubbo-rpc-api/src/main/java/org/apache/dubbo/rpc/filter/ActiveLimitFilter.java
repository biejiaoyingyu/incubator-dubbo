/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcStatus;

/**
 *  LimitInvokerFilter
 *  消费端调用服务的并发控制。
 *  控制同一个消费端对服务端某一服务的并发调用度，通常该值应该小于< dubbo:service executes=”“/>
 *  非阻断，但如果超过允许的并发度会阻塞，超过超时时间后将不再调用服务，而是直接抛出超时。
 *
 *  < dubbo:reference actives=”“/> 是控制消费端对 单个服务提供者单个服务允许调用的最大并发度。
 *  该值的取值不应该大于< dubbo:service executes=”“/>的值，并且如果消费者机器的配置，如果性能
 *  不尽相同，不建议对该值进行设置。
 */
@Activate(group = Constants.CONSUMER, value = Constants.ACTIVES_KEY)
public class ActiveLimitFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        /**
         * 从Invoker中获取消息端URL中的配置的actives参数，为什么从Invoker中获取的Url是消费端的Url呢？
         * 这是因为在消费端根据服务提供者URL创建调用Invoker时，会用服务提供者URL，然后合并消费端的配置属性，
         * 其优先级 -D > 消费端 > 服务端。其代码位于：
         *   RegistryDirectory#toInvokers
         *   URL url = mergeUrl(providerUrl);
         */
        int max = invoker.getUrl().getMethodParameter(methodName, Constants.ACTIVES_KEY, 0);
        /**
         * 根据服务提供者URL和调用服务提供者方法，获取RpcStatus。
         */
        RpcStatus count = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
        if (max > 0) {
            /**
             * 获取接口调用的超时时间，默认为1s。
             */
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, 0);
            long start = System.currentTimeMillis();
            long remain = timeout;
            /**
             * 获取当前消费者，针对特定服务，特定方法的并发调用度，active值。
             */
            int active = count.getActive();
            /**
             * 如果当前的并发 调用大于等于允许的最大值，则针对该RpcStatus申请锁，
             * 并调用其wait(timeout)进行等待，也就是在接口调用超时时间内，还是未被唤醒，则直接抛出超时异常。
             */
            if (active >= max) {
                synchronized (count) {
                    while ((active = count.getActive()) >= max) {
                        try {
                            count.wait(remain);
                        } catch (InterruptedException e) {
                        }
                        long elapsed = System.currentTimeMillis() - start;
                        remain = timeout - elapsed;
                        /**
                         * 判断被唤醒的原因是因为等待超时，还是由于调用结束，释放了”名额“，如果是超时唤醒，则直接抛出异常。
                         */
                        if (remain <= 0) {
                            throw new RpcException("Waiting concurrent invoke timeout in client-side for service:  "
                                    + invoker.getInterface().getName() + ", method: "
                                    + invocation.getMethodName() + ", elapsed: " + elapsed
                                    + ", timeout: " + timeout + ". concurrent invokes: " + active
                                    + ". max concurrent invoke limit: " + max);
                        }
                    }
                }
            }
        }
        try {
            long begin = System.currentTimeMillis();
            /**
             * 在一次服务调用前，先将 服务名+方法名对应的RpcStatus的active加一。
             */
            RpcStatus.beginCount(url, methodName);
            try {
                Result result = invoker.invoke(invocation);
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, true);
                return result;
            } catch (RuntimeException t) {
                /**
                 * 执行RPC服务调用。
                 */
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, false);
                throw t;
            }
        } finally {
            if (max > 0) {
                synchronized (count) {
                    /**
                     * 最终成功执行，如果开启了actives机制(dubbo:referecnce actives=”“)时，唤醒等待者。
                     */
                    count.notify();
                }
            }
        }
    }

}
