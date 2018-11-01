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
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractClusterInvoker
 * 集群模式调用模板类
 * 该类为Dubbo集群模式的调用模板类，主题解决一个服务服务有多个服务提供者，
 * 此时消息消费端在调用服务时如何选择具体的服务提供者。该类需要组织多个服
 * 务提供者，并按照指定算法选择一服务提供者进行调用。
 *
 * -------------------------------------------------------------
 *
 * 更多的集群策略，可以参考/dubbo-cluster/src/main/resources/META-INF/dubbo/internal/com.alibaba.dubbo.rpc.cluster.Cluster文件
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory
            .getLogger(AbstractClusterInvoker.class);
    protected final Directory<T> directory;

    protected final boolean availablecheck;

    private AtomicBoolean destroyed = new AtomicBoolean(false);

    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null) {
            throw new IllegalArgumentException("service directory == null");
        }

        this.directory = directory;
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(Constants.CLUSTER_AVAILABLE_CHECK_KEY, Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * Select a invoker using loadbalance policy.</br>
     * a) Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or,
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * <p>
     * b) Reselection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also
     * guarantees this invoker is available.
     *
     * @param loadbalance load balance policy 负载均衡算法
     * @param invocation  invocation  服务调用上下文环境
     * @param invokers    invoker candidates 待选的服务提供者列表
     * @param selected    exclude selected invokers or not  本次集群测试，已选择的服务提供者。
     * @return the invoker which will final to do invoke.
     * @throws RpcException
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.isEmpty()) {
            return null;
        }
        String methodName = invocation == null ? "" : invocation.getMethodName();

        /**
         * sticky机制（粘性），如果开启了粘性机制的话。通过< dubbo:method sticky=”true”/>,默认不开启。如果开启，
         * 上一次该服务调用的是哪个服务提供者，只要调用过程中不发生错误，后续都会选择该服务提供者进行调用。
         */
        boolean sticky = invokers.get(0).getUrl().getMethodParameter(methodName, Constants.CLUSTER_STICKY_KEY, Constants.DEFAULT_CLUSTER_STICKY);
        {
            //ignore overloaded method
            // 忽略重载方法
            if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
                stickyInvoker = null;
            }
            //ignore concurrency problem
            // 忽略并发问题
            if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
                if (availablecheck && stickyInvoker.isAvailable()) {
                    return stickyInvoker;
                }
            }
        }
        /**
         * 执行doSelect选择。
         *
         */
         /* 使用负载均衡策略选择invoker */
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);

        if (sticky) {
            stickyInvoker = invoker;
        }
        return invoker;
    }

    /**
     *
     * @param loadbalance
     * @param invocation
     * @param invokers
     * @param selected
     * @return
     * @throws RpcException
     */
    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.isEmpty())
            return null;
        /**
         * 如果可选Invoker只有一个的话，直接返回该Invoker。
         */
        if (invokers.size() == 1) return invokers.get(0);

        /**
         * 调用loadBalance负载均衡算法，选择一个服务提供者。
         */
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //If the `invoker` is in the  `selected` or invoker is unavailable && availablecheck is true, reselect.
        // 如果invoker在selected中或者invoker不可用并且availablecheck为true，则重新选择
        if ((selected != null && selected.contains(invoker)) || (!invoker.isAvailable() &&
                getUrl() != null && availablecheck)) {
            try {
                /**
                 * 如果选择的Invoker已被选择，则重新选择，这里有一个疑问，为什么不在选之前，先过滤掉已被选的Invoker。
                 * 从服务提供者列表中选择一个服务提供者算法就介绍到这里，接下来将一一分析Dubbo提供的集群容错方式。
                 */
                //todo:enter
                Invoker<T> rinvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rinvoker != null) {
                    invoker = rinvoker;
                } else {
                    //Check the index of current selected invoker, if it's not the last one, choose the one at index+1.
                    // 检查当前所选invoker的index，如果不是最后一个，请选择索引为index + 1的invoker
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision
                        invoker = index < invokers.size() - 1 ? invokers.get(index + 1) : invokers.get(0);
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`, just pick an available one using loadbalance policy.
     *
     * @param loadbalance
     * @param invocation
     * @param invokers
     * @param selected
     * @return
     * @throws RpcException
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck)
            throws RpcException {

        //Allocating one in advance, this list is certain to be used.
        // 事先分配一个列表，肯定会使用此列表
        List<Invoker<T>> reselectInvokers = new ArrayList<Invoker<T>>(invokers.size() > 1 ?
                (invokers.size() - 1) : invokers.size());

        //First, try picking a invoker not in `selected`.
        // 首先尝试从未在selected中的invoker中选择一个，
        if (availablecheck) { // invoker.isAvailable() should be checked
            for (Invoker<T> invoker : invokers) {
                // invoker.isAvailable() 需要被检查
                if (invoker.isAvailable()) {
                    if (selected == null || !selected.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        } else { // do not check invoker.isAvailable()
            // 不检查invoker.isAvailable()
            for (Invoker<T> invoker : invokers) {
                if (selected == null || !selected.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        // Just pick an available invoker using loadbalance policy
        // 只需使用loadbalance策略选择一个可用的invoker
        {
            if (selected != null) {
                for (Invoker<T> invoker : selected) {
                    if ((invoker.isAvailable()) // available first
                            && !reselectInvokers.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        return null;
    }

    /**
     *
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        checkWhetherDestroyed();

        // binding attachments into invocation.
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            ((RpcInvocation) invocation).addAttachments(contextAttachments);
        }
        /**
         * 根据调用上下文，获取服务提供者列表，服务提供者从Directory中获取。
         *
         * 最终会调用RegistryDirecotry的list方法，该方法的服务提供者是当该消费者订阅的服务的服务提
         * 供者列表发送变化后，会在注册中心产生事件，然后通知消费者更新服务提供者列表（本地缓存）。需要
         * 注意的是RegistryDirecotry在返回Invoker之前，已经使用Router进行了一次筛选，具体实现在
         * RegistryDirectory#notify方法时。
         *
         */
        List<Invoker<T>> invokers = list(invocation);

        /**
         * 根据SPI机制，获取负载均衡算法的实现类,根据< dubbo:consumer loadbalance=”“/>、
         * < dubbo:reference loadbalance=”“/>等标签的配置值，默认为random，加权随机算法。
         */
        LoadBalance loadbalance = initLoadBalance(invokers, invocation);
        // 幂等操作：默认情况下，将在异步操作中添加调用ID
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        /**
         * 根据调用上下文，服务提供者列表，负载均衡算法选择一服务提供者，具体代码有AbstractClusterInvoker的各个子类实现。
         *
         * -----------------------------------------------------------------
         * Dubbo目前支持的集群容错策略在中/dubbo-cluster/src/main/resources/META-INF/dubbo/internal/com.alibaba.dubbo.rpc.cluster.Cluster定义
         * 对应的执行器为Cluser+Invoker,例如FailoverCluster对应的Invoker为：FailoverClusterInvoker。
         */
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {

        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (invokers == null || invokers.isEmpty()) {
            throw new RpcException("Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        /* 这里的directory在RegistryProtocol的doRefer方法中构建Invoker时传入，为RegistryDirectory */
        return directory.list(invocation);
    }

    /**
     * Init LoadBalance.
     * <p>
     * if invokers is not empty, init from the first invoke's url and invocation
     * if invokes is empty, init a default LoadBalance(RandomLoadBalance)
     * </p>
     *
     * @param invokers   invokers
     * @param invocation invocation
     * @return LoadBalance instance. if not need init, return null.
     */
    protected LoadBalance initLoadBalance(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isNotEmpty(invokers)) {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(RpcUtils.getMethodName(invocation), Constants.LOADBALANCE_KEY, Constants.DEFAULT_LOADBALANCE));
        } else {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(Constants.DEFAULT_LOADBALANCE);
        }
    }
}
