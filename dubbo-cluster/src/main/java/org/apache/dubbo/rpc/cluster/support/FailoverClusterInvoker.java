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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * When invoke fails, log the initial error and retry other invokers (retry n times, which means at most n different invokers will be invoked)
 * Note that retry causes latency.
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Failover">Failover</a>
 * 通过< dubbo:service cluster = “failover” …/> 或 < dubbo:reference cluster=”failover” …/>
 * 集群策略：服务调用后，如果出现失败，则重试其他服务提供者，默认重试2次，总共执行3次，重试次数由retries配置，dubbo集群默认方式。
 * 源码分析FailoverClusterInvoker（FailoverCluster，dubbo默认策略）
 * 策略：失败后自动选择其他服务提供者进行重试，重试次数由retries属性设置，< dubbo:reference retries = “2”/>设置，默认为2，代表重试2次，最多执行3次。

 */
public class FailoverClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailoverClusterInvoker.class);

    public FailoverClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        List<Invoker<T>> copyinvokers = invokers;
        checkInvokers(copyinvokers, invocation);
        String methodName = RpcUtils.getMethodName(invocation);
        /**
         * 首先校验服务提供者列表，如果为空，则抛出RpcException，提示没有可用的服务提供者。
         */
        // 重试次数，第一次调用不算重试，所以加1
        int len = getUrl().getMethodParameter(methodName, Constants.RETRIES_KEY, Constants.DEFAULT_RETRIES) + 1;
        if (len <= 0) {
            len = 1;
        }
        // retry loop.
        RpcException le = null; // last exception.
        // 存储已经调用过的invoker
        List<Invoker<T>> invoked = new ArrayList<Invoker<T>>(copyinvokers.size()); // invoked invokers.
        /**
         * 构建Set< Stirng> providers,主要用来已调用服务提供者的地址，如果本次调用失败，将在日志信息中打印已调用的服务提供者信息。
         */
        Set<String> providers = new HashSet<String>(len);
        /**
         * 循环执行次数，等于retries + 1 次。
         *
         */
        // 重试循环
        for (int i = 0; i < len; i++) {
            //Reselect before retry to avoid a change of candidate `invokers`.
            //NOTE: if `invokers` changed, then `invoked` also lose accuracy.
            /**
             * 如果i>0，表示服务调用，在重试，此时需要重新调用Directory#list方法，获取最小的服务提供者列表。
             */
            // 在重试之前重新选择以避免候选invokers的更改
            // 注意：如果invokers改变了，那么invoked集合也会失去准确性
            if (i > 0) {
                // 重复第一次调用的几个步骤
                checkWhetherDestroyed();
                copyinvokers = list(invocation);
                // check again
                checkInvokers(copyinvokers, invocation);
            }
            /**
             * 根据负载均衡算法，选择Invoker，后续详细分析。
             */
            Invoker<T> invoker = select(loadbalance, invocation, copyinvokers, invoked);
            invoked.add(invoker);
            RpcContext.getContext().setInvokers((List) invoked);
            try {
                /**
                 * 根据负载算法，路由算法从服务提供者列表选一个服务提供者，发起RPC调用。
                 */
                Result result = invoker.invoke(invocation);
                if (le != null && logger.isWarnEnabled()) {
                    logger.warn("Although retry the method " + methodName
                            + " in the service " + getInterface().getName()
                            + " was successful by the provider " + invoker.getUrl().getAddress()
                            + ", but there have been failed providers " + providers
                            + " (" + providers.size() + "/" + copyinvokers.size()
                            + ") from the registry " + directory.getUrl().getAddress()
                            + " on the consumer " + NetUtils.getLocalHost()
                            + " using the dubbo version " + Version.getVersion() + ". Last error is: "
                            + le.getMessage(), le);
                }
                return result;
            } catch (RpcException e) {
                if (e.isBiz()) { // biz exception.
                    throw e;
                }
                le = e;
            } catch (Throwable e) {
                le = new RpcException(e.getMessage(), e);
            } finally {
                /**
                 * 将本次服务提供者的地址添加到providers集合中，如果多次重试后，无法完成正常的调用，将在错误日志中包含这些信息。
                 */
                providers.add(invoker.getUrl().getAddress());
            }
        }
        throw new RpcException(le.getCode(), "Failed to invoke the method "
                + methodName + " in the service " + getInterface().getName()
                + ". Tried " + len + " times of the providers " + providers
                + " (" + providers.size() + "/" + copyinvokers.size()
                + ") from the registry " + directory.getUrl().getAddress()
                + " on the consumer " + NetUtils.getLocalHost() + " using the dubbo version "
                + Version.getVersion() + ". Last error is: "
                + le.getMessage(), le.getCause() != null ? le.getCause() : le);
    }

}
