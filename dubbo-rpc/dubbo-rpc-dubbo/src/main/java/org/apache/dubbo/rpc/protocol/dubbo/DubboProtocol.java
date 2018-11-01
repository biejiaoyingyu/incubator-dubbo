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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.AsyncContextImpl;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * dubbo protocol support.
 * 该类是抽象协议实现类AbstractProtocol的具体的dubbo协议的实现，从该类开始着手分析。
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static DubboProtocol INSTANCE;
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<String, ExchangeServer>(); // <host:port,Exchanger>
    private final Map<String, ReferenceCountExchangeClient> referenceClientMap = new ConcurrentHashMap<String, ReferenceCountExchangeClient>(); // <host:port,Exchanger>
    private final ConcurrentMap<String, LazyConnectExchangeClient> ghostClientMap = new ConcurrentHashMap<String, LazyConnectExchangeClient>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private final Set<String> optimizers = new ConcurrentHashSet<String>();
    //consumer side export a stub service for dispatching event
    //servicekey-stubmethods
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<String, String>();
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                /**
                 * 获取服务调用信息，例如调用服务类名(interface)、服务方法名、参数类型，参数值。
                 *
                 */
                Invocation inv = (Invocation) message;
                /**
                 * 获取调用者Invoker。匹配Invoker
                 */
                Invoker<?> invoker = getInvoker(channel, inv);
                // need to consider backward-compatibility if it's a callback
                // 如果它是回调，则需要考虑向后兼容性
                if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                    String methodsStr = invoker.getUrl().getParameters().get("methods");
                    boolean hasMethod = false;
                    // 搜索是否有相关方法的存在
                    if (methodsStr == null || !methodsStr.contains(",")) {
                        hasMethod = inv.getMethodName().equals(methodsStr);
                    } else {
                        String[] methods = methodsStr.split(",");
                        for (String method : methods) {
                            if (inv.getMethodName().equals(method)) {
                                hasMethod = true;
                                break;
                            }
                        }
                    }
                    if (!hasMethod) {
                        // 没有搜索到相关方法直接返回null
                        logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                                + " not found in callback service interface ,invoke will be ignored."
                                + " please update the api interface. url is:"
                                + invoker.getUrl()) + " ,invocation is :" + inv);
                        return null;
                    }
                }
                RpcContext rpcContext = RpcContext.getContext();
                boolean supportServerAsync = invoker.getUrl().getMethodParameter(inv.getMethodName(), Constants.ASYNC_KEY, false);
                if (supportServerAsync) {
                    CompletableFuture<Object> future = new CompletableFuture<>();
                    rpcContext.setAsyncContext(new AsyncContextImpl(future));
                }
                rpcContext.setRemoteAddress(channel.getRemoteAddress());
                /**
                 * 调用Invoker，执行具体的方法调用。
                 */
                Result result = invoker.invoke(inv);

                if (result instanceof AsyncRpcResult) {
                    return ((AsyncRpcResult) result).getResultFuture().thenApply(r -> (Object) r);
                } else {
                    return CompletableFuture.completedFuture(result);
                }
            }
            throw new RemotingException(channel, "Unsupported request: "
                    + (message == null ? null : (message.getClass().getName() + ": " + message))
                    + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
        }


        /**
         * 如果是服务调用，就进入到reply方法中，否则调用父类进行请求响应。
         * @param channel
         * @param message
         * @throws RemotingException
         */
        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);
            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, Constants.ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isInfoEnabled()) {
                logger.info("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, Constants.ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }
            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            invocation.setAttachment(Constants.PATH_KEY, url.getPath());
            invocation.setAttachment(Constants.GROUP_KEY, url.getParameter(Constants.GROUP_KEY));
            invocation.setAttachment(Constants.INTERFACE_KEY, url.getParameter(Constants.INTERFACE_KEY));
            invocation.setAttachment(Constants.VERSION_KEY, url.getParameter(Constants.VERSION_KEY));
            if (url.getParameter(Constants.STUB_EVENT_KEY, false)) {
                invocation.setAttachment(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString());
            }
            return invocation;
        }
    };

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME); // load
        }
        return INSTANCE;
    }

    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort();
        String path = inv.getAttachments().get(Constants.PATH_KEY);
        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(Constants.STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            port = channel.getRemoteAddress().getPort();
        }
        //callback
        // 回调
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path = inv.getAttachments().get(Constants.PATH_KEY) + "." + inv.getAttachments().get(Constants.CALLBACK_SERVICE_KEY);
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }
        // 格式（分组/接口全称:服务版本:端口），接口和端口是一定存在的，分组和服务版本不一定
        String serviceKey = serviceKey(port, path, inv.getAttachments().get(Constants.VERSION_KEY), inv.getAttachments().get(Constants.GROUP_KEY));
        // 获取Exporter
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null)
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);
        // 返回Invoker
        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 该方法实现了dubbo协议的服务发布，显示构造一个DubboExporter实现类的Exporter，用于返回。最核心的是调用
     * 内部方法openServer(url);将该url发布到dubbo服务器上。
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        /**
         * 获取服务提供者URL，以协议名称，这里是dubbo://开头。
         */
        URL url = invoker.getUrl();

        // export service.
        // export service. 通过url获得该服务的key。格式如：{serviceGroup}/{serviceName}:{serviceVersion}:{port}
        /**
         * 从服务提供者URL中获取服务名，key: interface:port，例如：com.alibaba.dubbo.demo.DemoService:20880
         */
        String key = serviceKey(url);
        //Dubbo协议实现的服务发布器。
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);

        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        //参数STUB_EVENT_KEY和IS_CALLBACK_SERVICE的含义不太清楚，需要后续深究。
        /**
         * 是否将转发事件导出成stub。
         */
        Boolean isStubSupportEvent = url.getParameter(Constants.STUB_EVENT_KEY, Constants.DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(Constants.IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(Constants.STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(Constants.INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }
            } else {
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }
        //调用打开服务器绑定url的方法，这个地方是核心，需要进入深究。
        /**
         * 根据url打开服务，下面将详细分析其实现。
         */
        openServer(url);
        /**
         * 根据url优化器序列化方式。
         */
        optimizeSerialization(url);
        return exporter;
    }

    /**
     * 该方法是获得url的地址，通过地址找到对应的server，若已经有相同的地址则无需构造新的server，只需要直接使
     * 用，只就起到了缓存server的作用，避免重复构建server。若已经找到了该地址，则会调用server.reset(url)重置一
     * 下。url中的参数Constants.IS_SERVER_KEY参数可以禁止发布远程服务，只能本地调用。具体意义不是十分清楚。
     */
    private void openServer(URL url) {
        // find server.
        /**
         * 根据url获取网络地址：ip:port，例如：192.168.56.1:20880，服务提供者IP与暴露服务端口号。
         */
        String key = url.getAddress();
        //client can export a service which's only for server to invoke
        //client 也可以暴露一个只有server可以调用的服务。

        boolean isServer = url.getParameter(Constants.IS_SERVER_KEY, true);
        if (isServer) {
            /**
             * 根据key从服务器缓存中获取，如果存在，则执行代码@4，如果不存在，则执行代码@3.
             */
            //服务器实现类
            ExchangeServer server = serverMap.get(key);
            if (server == null) {
                synchronized (this) {
                    server = serverMap.get(key);
                    if (server == null) {
                        /**
                         * 根据URL创建一服务器，Dubbo服务提供者服务器实现类为ExchangeServer。
                         * 见详细
                         */
                        serverMap.put(key, createServer(url));
                    }
                }
            } else {
                // server supports reset, use together with override
                //server支持reset,配合override功能使用
                /**
                 * 如果服务器已经存在，用当前URL重置服务器，这个不难理解，因为一个Dubbo服务中，
                 * 会存在多个dubbo:service标签，这些标签都会在服务台提供者的同一个IP地址、端口号上暴露服务。
                 */
                server.reset(url);
            }
        }
    }

    private ExchangeServer createServer(URL url) {
        // send readonly event when server closes, it's enabled by default
        //默认开启server关闭时发送readonly事件
        /**
         * 为服务提供者url增加channel.readonly.sent属性，默认为true，表示在发送请求时，是否等待将字节写入socket后再返回，默认为true。
         * 是等待将字节写入socket后再返回
         */
        url = url.addParameterIfAbsent(Constants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString());
        // enable heartbeat by default
        //默认开启heartbeat
        /**
         * 为服务提供者url增加heartbeat属性，表示心跳间隔时间，默认为60*1000，表示60s。
         */
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));
        /**
         * 为服务提供者url增加server属性，可选值为netty,mina等等，默认为netty。
         */
        String str = url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_SERVER);

        /**
         * 根据SPI机制，判断server属性是否支持
         */
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str))
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);

        /**
         * 为服务提供者url增加codec属性，默认值为dubbo，协议编码方式。
         */
        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);
        ExchangeServer server;
        try {
            /**
             * 根据服务提供者URI,服务提供者命令请求处理器requestHandler构建ExchangeServer实例。
             * requestHandler的实现具体在以后详细分析Dubbo服务调用时再详细分析。
             */
            //首先根据url获取Exchanger实例，然后调用bind方法构建ExchangeServer
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }
        /**
         * 验证客户端类型是否可用。
         */
        str = url.getParameter(Constants.CLIENT_KEY);
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        return server;
    }

    private void optimizeSerialization(URL url) throws RpcException {
        String className = url.getParameter(Constants.OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);
        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);
        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    /**
     * todo:来看consumer如何关联provider
     * @param serviceType
     * @param url  URL address for the remote service 远程服务的URL地址
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        optimizeSerialization(url);
        // create rpc invoker.
        /* 获取client，构建rpc invoker */
        //todo:enter
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);
        return invoker;
    }

    /**
     * 在创建Invoker时，通过getClient方法，开始Client（连接的）创建过程
     * @param url 参数URL,服务提供者URL。
     * @return
     */
    private ExchangeClient[] getClients(URL url) {
        // whether to share connection
        // 是否共享连接
        boolean service_share_connect = false;
        /**
         * 获取< dubbo:reference connections = “” />，默认0表示客户端对同一个服务提供者的所有服务，
         * 使用共享一个连接，如果该值有设置，则使用非共享的客户端，所谓的共享客户端，以Netty为例，也即客
         * 户端对同一服务提供者发起的不同服务，使用同一个客户端(NettyClient)进行请求的发送与接收。
         */
        int connections = url.getParameter(Constants.CONNECTIONS_KEY, 0);
        // if not configured, connection is shared, otherwise, one connection for one service
        if (connections == 0) {
            service_share_connect = true;
            connections = 1;
        }
        /**
         * 根据connections，创建ExchangeClients数组。
         */
        // 如果未配置，则共享连接，否则一个服务一个连接
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            if (service_share_connect) {
                /**
                 * 如果使用共享连接，则调用getSharedClient获取共享连接，如果客户端未建立，则创建客户端。
                 */
                clients[i] = getSharedClient(url);
            } else {
                /**
                 * 如果不使用共享连接，调用initClient创建客户端，
                 */
                clients[i] = initClient(url);
            }
        }
        return clients;
    }

    /**
     * Get shared connection
     */
    private ExchangeClient getSharedClient(URL url) {
        String key = url.getAddress();
        ReferenceCountExchangeClient client = referenceClientMap.get(key);
        if (client != null) {
            if (!client.isClosed()) {
                // 缓存不为空并且client未关闭，增加引用计数
                client.incrementAndGetCount();
                return client;
            } else {
                // 关闭的client从缓存中移除
                referenceClientMap.remove(key);
            }
        }

        locks.putIfAbsent(key, new Object());
        synchronized (locks.get(key)) {
            if (referenceClientMap.containsKey(key)) {
                return referenceClientMap.get(key);
            }
            /* 初始化client */
            //todo:init
            ExchangeClient exchangeClient = initClient(url);
            // 包装client
            client = new ReferenceCountExchangeClient(exchangeClient, ghostClientMap);
            referenceClientMap.put(key, client);
            ghostClientMap.remove(key);
            locks.remove(key);
            return client;
        }
    }

    /**
     * Create new connection
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.
        // client 类型，默认netty
        String str = url.getParameter(Constants.CLIENT_KEY, url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_CLIENT));

        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default
        // 默认启动心跳检测
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        // BIO是不允许的，因为它有严重的性能问题
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            if (url.getParameter(Constants.LAZY_CONNECT_KEY, false)) {
                client = new LazyConnectExchangeClient(url, requestHandler);
            } else {
                /* 建立连接 */
                client = Exchangers.connect(url, requestHandler);
            }
        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }
        return client;
    }

    @Override
    public void destroy() {
        for (String key : new ArrayList<String>(serverMap.keySet())) {
            ExchangeServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo server: " + server.getLocalAddress());
                    }
                    server.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        for (String key : new ArrayList<String>(referenceClientMap.keySet())) {
            ExchangeClient client = referenceClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        for (String key : new ArrayList<String>(ghostClientMap.keySet())) {
            ExchangeClient client = ghostClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close(ConfigUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        stubServiceMethodsMap.clear();
        super.destroy();
    }
}
