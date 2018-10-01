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

import java.util.concurrent.Semaphore;

/**
 * ThreadLimitInvokerFilter
 * < dubbo:service executes=”“/>与< dubbo:reference actives = “”/>的实现机制，深入探讨Dubbo自身的保护机制。
 *
 * 服务调用方并发度控制。
 * 对Dubbo服务提供者实现的一种保护机制，控制每个服务的最大并发度。
 * 当服务调用超过允许的并发度后，直接抛出RpcException异常。
 *
 * < dubbo:service executes=”“/>的含义是，针对每个服务每个方法的最大并发度。如果超过该值，则直接抛出RpcException。
 */
@Activate(group = Constants.PROVIDER, value = Constants.EXECUTES_KEY)
public class ExecuteLimitFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        Semaphore executesLimit = null;
        boolean acquireResult = false;
        /**
         * 从服务提供者列表中获取参数executes的值，如果该值小于等于0，表示不启用并发度控制，直接沿着调用链进行调用。
         */
        int max = url.getMethodParameter(methodName, Constants.EXECUTES_KEY, 0);
        if (max > 0) {
            /**
             * 根据服务提供者url和服务调用方法名，获取RpcStatus。
             */
            RpcStatus count = RpcStatus.getStatus(url, invocation.getMethodName());
//            if (count.getActive() >= max) {
            /**
             * http://manzhizhen.iteye.com/blog/2386408
             * use semaphore for concurrency control (to limit thread number)
             */
            /**
             * 根据服务提供者配置的最大并发度，创建该服务该方法对应的信号量对象。使用了双重检测来创建executesLimit 信号量。
             */
            executesLimit = count.getSemaphore(max);
            /**
             * 如果获取不到锁，并不会阻塞等待，而是直接抛出RpcException,服务端的策略是快速抛出异常，
             * 供服务调用方（消费者）根据集群策略进行执行，例如重试其他服务提供者。
             */
            if(executesLimit != null && !(acquireResult = executesLimit.tryAcquire())) {
                throw new RpcException("Failed to invoke method " + invocation.getMethodName() + " in provider " + url + ", cause: The service using threads greater than <dubbo:service executes=\"" + max + "\" /> limited.");
            }
        }
        long begin = System.currentTimeMillis();
        boolean isSuccess = true;
        RpcStatus.beginCount(url, methodName);
        try {
            /**
             * 执行真实的服务调用。
             */
            Result result = invoker.invoke(invocation);
            return result;
        } catch (Throwable t) {
            isSuccess = false;
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else {
                throw new RpcException("unexpected exception when ExecuteLimitFilter", t);
            }
        } finally {
            RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, isSuccess);
            if(acquireResult) {
                /**
                 * 如果成功申请到信号量，在服务调用结束后，释放信号量。
                 */
                executesLimit.release();
            }
        }
    }

}
