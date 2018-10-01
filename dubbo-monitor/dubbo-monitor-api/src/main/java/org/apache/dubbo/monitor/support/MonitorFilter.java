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
package org.apache.dubbo.monitor.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.monitor.Monitor;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MonitorFilter. (SPI, Singleton, ThreadSafe)
 *  监控过滤器，向监控中心汇报服务调用数据。搭建监控中心监控Dubbo服务调用。非阻断过滤器。注：MonitorFilter会在生产者、消费者两端生效。
 */
@Activate(group = {Constants.PROVIDER, Constants.CONSUMER})
public class MonitorFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(MonitorFilter.class);

    private final ConcurrentMap<String, AtomicInteger> concurrents = new ConcurrentHashMap<String, AtomicInteger>();

    private MonitorFactory monitorFactory;

    public void setMonitorFactory(MonitorFactory monitorFactory) {
        this.monitorFactory = monitorFactory;
    }

    // intercepting invocation
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        /**
         * 如果url中存在monitor,则设置了监控中心，收集调用信息。
         */
        if (invoker.getUrl().hasParameter(Constants.MONITOR_KEY)) {
            /**
             * 获取本次服务调用的上下文环境。
             */
            RpcContext context = RpcContext.getContext(); // provider must fetch context before invoke() gets called
            String remoteHost = context.getRemoteHost();
            long start = System.currentTimeMillis(); // record start timestamp
            /**
             * 服务调用并发次数增加1，（非服务调用总次数，而是当前服务的并发调用）。
             */
            getConcurrent(invoker, invocation).incrementAndGet(); // count up
            try {
                /**
                 * 执行方法之前先记录当前时间，然后调用下一个过滤器，直到真实服务被调用。
                 */
                Result result = invoker.invoke(invocation); // proceed invocation chain
                /**
                 * 调用collect方法收集调用信息。
                 */
                collect(invoker, invocation, result, remoteHost, start, false);
                return result;
            } catch (RpcException e) {
                /**
                 * 如果调用发送RPC异常，则收集错误信息。
                 */
                collect(invoker, invocation, null, remoteHost, start, true);
                throw e;
            } finally {
                /**
                 * 一次服务调用结束，并发次数减一。
                 */
                getConcurrent(invoker, invocation).decrementAndGet(); // count down
            }
        } else {
            return invoker.invoke(invocation);
        }
    }

    // collect info

    /**
     *
     * @param invoker 服务调用Invoker。
     * @param invocation 本次服务调用信息
     * @param result 执行结果
     * @param remoteHost 调用者host信息。
     * @param start 服务开始调用时间。
     * @param error 是否发生错误。
     */
    private void collect(Invoker<?> invoker, Invocation invocation, Result result, String remoteHost, long start, boolean error) {
        try {
            // ---- service statistics ----
            /**
             * 统计基础信息字段说明：
             */
            long elapsed = System.currentTimeMillis() - start; // invocation cost 服务调用时长。
            int concurrent = getConcurrent(invoker, invocation).get(); // current concurrent count 当前并发度。（当前服务并发调用次数）。
            String application = invoker.getUrl().getParameter(Constants.APPLICATION_KEY);// 服务归属应用名。
            String service = invoker.getInterface().getName(); // service name 服务名。
            String method = RpcUtils.getMethodName(invocation); // method name 方法名。
            String group = invoker.getUrl().getParameter(Constants.GROUP_KEY); //服务所属组。
            String version = invoker.getUrl().getParameter(Constants.VERSION_KEY); //服务版本号
            URL url = invoker.getUrl().getUrlParameter(Constants.MONITOR_KEY); //监控中心url。
            /**
             * 根据监控中心获取监控中心实现类，这是监控中心实现扩展点，默认使用com.alibaba.dubbo.monitor.dubbo.DubboMonitor。
             */
            Monitor monitor = monitorFactory.getMonitor(url);
            if (monitor == null) {
                return;
            }
            int localPort;
            String remoteKey;
            String remoteValue;
            /**
             * 如果是消费端，由于Monitor在消费端与服务端都会生效：
             */
            if (Constants.CONSUMER_SIDE.equals(invoker.getUrl().getParameter(Constants.SIDE_KEY))) {
                // ---- for service consumer ----
                localPort = 0; //本地端口设置为0；
                remoteKey = MonitorService.PROVIDER; //MonitorService.PROVIDER，表示为服务端。
                remoteValue = invoker.getUrl().getAddress(); //为invoker.getUrl().getAddress()，其值为（注册中心地址）或服务提供者地址（客户端直连服务端）。
            } else {
                // ---- for service provider ----
                /**
                 * 如果为服务端：
                 */
                localPort = invoker.getUrl().getPort(); //为服务端的服务端口号。
                remoteKey = MonitorService.CONSUMER; //MonitorService.CONSUMER，表示远端为服务消费者。
                remoteValue = remoteHost; //消费者host(ip:port)。
            }
            String input = "", output = "";
            /**
             * 获取本次服务调用请求包的字节数，在服务端解码时会在RpcContext中。
             */
            if (invocation.getAttachment(Constants.INPUT_KEY) != null) {
                input = invocation.getAttachment(Constants.INPUT_KEY);
            }
            /**
             * 获取本次服务调用响应包的字节数，在服务端对响应包编码时会写入，具体代码请参考DubboCountCodec类。
             */
            if (result != null && result.getAttachment(Constants.OUTPUT_KEY) != null) {
                output = result.getAttachment(Constants.OUTPUT_KEY);
            }
            /**
             * 调用monitor#collect收集调用信息，Monitor默认实现为DubboMonitor。
             * 使用的协议为count://localhost:localPort/service/method?
             * application=applicationName&
             * remoteKey=remoteValue&
             * success|failure=1&
             * elapsed=调用开销&
             * concurrent=并发调用次数&
             * input=入参字节数&
             * output=响应字节数&
             * group=服务所属组&
             * version=版本。
             */
            monitor.collect(new URL(Constants.COUNT_PROTOCOL,
                    NetUtils.getLocalHost(), localPort,
                    service + "/" + method,
                    MonitorService.APPLICATION, application,
                    MonitorService.INTERFACE, service,
                    MonitorService.METHOD, method,
                    remoteKey, remoteValue,
                    error ? MonitorService.FAILURE : MonitorService.SUCCESS, "1",
                    MonitorService.ELAPSED, String.valueOf(elapsed),
                    MonitorService.CONCURRENT, String.valueOf(concurrent),
                    Constants.INPUT_KEY, input,
                    Constants.OUTPUT_KEY, output,
                    Constants.GROUP_KEY, group,
                    Constants.VERSION_KEY, version));
        } catch (Throwable t) {
            logger.error("Failed to monitor count service " + invoker.getUrl() + ", cause: " + t.getMessage(), t);
        }
    }

    // concurrent counter
    private AtomicInteger getConcurrent(Invoker<?> invoker, Invocation invocation) {
        /**
         * 使用的是ConcurrentMap< String, AtomicInteger >作为缓存容器，其key为：interfaceName + “.” + methodName。
         */
        String key = invoker.getInterface().getName() + "." + invocation.getMethodName();
        AtomicInteger concurrent = concurrents.get(key);
        /**
         * 如果是第一次调用，则创建AtomicInteger，否则返回原先的计数器。
         */
        if (concurrent == null) {
            concurrents.putIfAbsent(key, new AtomicInteger());
            concurrent = concurrents.get(key);
        }
        return concurrent;
    }

}
