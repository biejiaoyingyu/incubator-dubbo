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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;

/**
 * 是一个Protocol的支持过滤器的装饰器
 * 通过该装饰器的对原始对象的包装使得Protocol支持可扩展的过滤器链，已经支持的包括
 * ExceptionFilter、ExecuteLimitFilter和TimeoutFilter等多种支持不同特性的过滤器
 * --------------------------------------------------------------------
 * 这个地方非常重要，dubbo 机制里面日志记录、超时等等功能都是在这一部分实现的。
 * 这个类有 3 个特点，第一它有一个参数为 Protocol protocol 的构造函数；第二，它
 * 实现了 Protocol 接口；第三，它使用职责链模式，对 export 和 refer 函数进行了封装
 * 对于函数封装，有点类似于 tomcat 的 filter 机制
 * ListenerProtocol
 */
public class ProtocolFilterWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    /**
     * 它读取所有的 filter 类，利用这些类封装 invoker
     * /dubbo-rpc-api/src/main/resources/META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Filter
     * 这其中涉及到很多功能，包括权限验证、异常、超时等等，当然可以预计计算调用时间等
     * 等应该也是在这其中的某个类实现的；
     * 这里我们可以看到 export 和 refer 过程都会被 filter 过滤，那么如果记录接口调用时
     * 间时，服务器端部分只是记录接口在服务器端的执行时间，而客户端部分会记录接口在服务器端
     * 的执行时间+网络传输时间。
     *
     *
     * 这个filter处理的时候建立了一条调用链InvokerChain
     * https://www.jianshu.com/p/f390bb88574d ===>要考的
     * @param invoker
     * @param key
     * @param group
     * @param <T>
     * @return
     */
    private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {
        Invoker<T> last = invoker;
        //通过该句获得扩展配置的过滤器列表，具体机制需要研究该类的实现
        /**
         * 加载系统配置的所有Filer，并根据作用对象（服务提供者、服务消费者），返回合适的Filter链表。
         *
         * 从SPI中获取激活的Filter类的实例，在@activite的那一节也讲过，他的主要用法是在Filter上，
         * 其实就是说的这里，然后将他们变成链式结构，保证他们再调用的时候，一个接着一个，当然这是常用
         * 的filter的使用模式。
         *
         */
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(invoker.getUrl(), key, group);
        if (!filters.isEmpty()) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                //循环将过滤器列表组装成为过滤器链，目标invoker是最后一个执行的。
                final Filter filter = filters.get(i);
                final Invoker<T> next = last;
                /**
                 * 根据Filter构建Invoker链。
                 *  // 典型的装饰器模式，将invoker用filter逐层进行包装
                 */
                last = new Invoker<T>() {

                    @Override
                    public Class<T> getInterface() {
                        return invoker.getInterface();
                    }

                    @Override
                    public URL getUrl() {
                        return invoker.getUrl();
                    }

                    @Override
                    public boolean isAvailable() {
                        return invoker.isAvailable();
                    }

                    //重点，每个filter在执行invoke方法时，会触发其下级节点的invoke方法，最后一级节点即为最原始的服务
                    @Override
                    public Result invoke(Invocation invocation) throws RpcException {
                        return filter.invoke(next, invocation);
                    }

                    @Override
                    public void destroy() {
                        invoker.destroy();
                    }

                    @Override
                    public String toString() {
                        return invoker.toString();
                    }
                };
            }
        }
        return last;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }


    /**
     * 暴露协议的时候先暴露这个？？？？？？重要，要考试的
     * @param invoker Service invoker 服务的执行体
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        /**
         * ：如果协议为registry，则直接调用RegistryProtocol#expoert完成协议导出，协议为registry其含义是通过注册中心暴露，
         * 最终会根据expoert，调用具体的协议进行服务暴露，最终会再次进入该方法。
         */
        if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);
        }
        /**
         * 如果为具体协议，例如dubbo等，则通过buildInvokerChain构建Invoker链。
         * 这个filter处理的时候建立了一条调用链InvokerChain
         */
        return protocol.export(buildInvokerChain(invoker, Constants.SERVICE_FILTER_KEY, Constants.PROVIDER));
    }

    /**
     * // 客户端引用服务
     * @param type Service class 服务的类型
     * @param url  URL address for the remote service 远程服务的URL地址
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return protocol.refer(type, url);
        }
        return buildInvokerChain(protocol.refer(type, url), Constants.REFERENCE_FILTER_KEY, Constants.CONSUMER);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

}
