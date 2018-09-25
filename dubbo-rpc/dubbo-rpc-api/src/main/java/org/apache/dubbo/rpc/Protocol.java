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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

/**
 * Protocol. (API/SPI, Singleton, ThreadSafe)
 * 首先 Protocol 类带有 SPI 注解，因此我们可以确认默认是使
 * 用 dubbo=com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol 作为默认扩展点；
 */
@SPI("dubbo")
public interface Protocol {

    /**
     * Get default port when user doesn't config the port.
     * 获取缺省端口，当用户没有配置端口时使用。
     * @return default port
     */
    int getDefaultPort();

    /**
     * Export service for remote invocation: <br>
     * 暴露远程服务
     * 1. Protocol should record request source address after receive a request:  RpcContext.getContext().setRemoteAddress();<br>
     *  协议在接收请求时，应记录请求来源方地址信息RpcContext.getContext().setRemoteAddress();
     * 2. export() must be idempotent, that is, there's no difference between invoking once and invoking twice when
     *  export the same URL<br>
     *  export()必须是幂等的，也就是暴露同一个URL的Invoker两次，和暴露一次没有区别。
     * 3. Invoker instance is passed in by the framework, protocol needs not to care <br>
     *  export()传入的Invoker由框架实现并传入，协议不需要关心。
     * @param <T>     Service type 服务的类型
     * @param invoker Service invoker 服务的执行体
     * @return exporter reference for exported service, useful for unexport the service later 暴露服务的引用，用于取消暴露
     * @throws RpcException thrown when error occurs during export the service, for example: port is occupied 当暴露服务出错时抛出，比如端口已占用
     */
    @Adaptive
    <T> Exporter<T> export(Invoker<T> invoker) throws RpcException;

    /**
     * Refer a remote service: <br>
     *     引用远程服务：
     * 1. When user calls `invoke()` method of `Invoker` object which's returned from `refer()` call, the protocol
     * needs to correspondingly execute `invoke()` method of `Invoker` object <br>
     *  当用户调用refer()所返回的Invoker对象的invoke()方法时，协议需相应执行同URL远端export()传入的Invoker对象的invoke()方法。
     * 2. It's protocol's responsibility to implement `Invoker` which's returned from `refer()`. Generally speaking,
     * protocol sends remote request in the `Invoker` implementation. <br>
     *  refer()返回的Invoker由协议实现，协议通常需要在此Invoker中发送远程请求。
     * 3. When there's check=false set in URL, the implementation must not throw exception but try to recover when
     * connection fails.
     *  当url中有设置check=false时，连接失败不能抛出异常，并内部自动恢复。
     * @param <T>  Service type 服务的类型
     * @param type Service class 服务的类型
     * @param url  URL address for the remote service 远程服务的URL地址
     * @return invoker service's local proxy 服务的本地代理
     * @throws RpcException when there's any error while connecting to the service provider  当连接服务提供方失败时抛出
     */
    @Adaptive
    <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException;

    /**
     * Destroy protocol: <br>
     * 1. Cancel all services this protocol exports and refers <br>
     * 2. Release all occupied resources, for example: connection, port, etc. <br>
     * 3. Protocol can continue to export and refer new service even after it's destroyed.
     *
     * 释放协议：
     * 1. 取消该协议所有已经暴露和引用的服务。
     * 2. 释放协议所占用的所有资源，比如连接和端口。
     * 3. 协议在释放后，依然能暴露和引用新的服务。
     */
    void destroy();

}