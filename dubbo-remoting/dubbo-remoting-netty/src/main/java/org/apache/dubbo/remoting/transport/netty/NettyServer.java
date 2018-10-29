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
package org.apache.dubbo.remoting.transport.netty;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Server;
import org.apache.dubbo.remoting.transport.AbstractServer;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *  NettyServer
 *  一条TCP连接进行传输，在网络层至少要考虑如下问题：
 *  1、服务端，客户端网络通讯模型（线程模型）
 *  2、传输（编码解码、序列化）。
 *  3、服务端转发策略等。
 */
public class NettyServer extends AbstractServer implements Server {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    /**
     * < ip:port, channel> 所有通道
     */
    private Map<String, Channel> channels; // <ip:port, channel>

    /**
     * netty 服务端启动器
     */
    private ServerBootstrap bootstrap;


    /**
     * 服务端监听通道。
     */
    private org.jboss.netty.channel.Channel channel;

    /**
     *
     * @param url  服务提供者URL
     * @param handler  ChannelHandler网络事件处理器,也就是当相应网络事件触发时，执行的事件处理器。
     * @throws RemotingException
     */
    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        /**
         * 调用ChannelHandlers.wrap对原生Handler进行包装，然后调用其父类的构造方法，
         * 首先，设置Dubbo服务端线程池中线程的名称，可以通过参数threadname来指定线程池中线程的前缀，
         * 默认为：DubboServerHandler + dubbo服务端IP与接口号。
         * 我比较好奇的是这里为什么需要对ChannelHandler进行包装呢？
         * 是增加了些什么逻辑呢？----------->事件派发机制。
         */
        super(url, ChannelHandlers.wrap(handler, ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME)));
    }

    @Override
    protected void doOpen() throws Throwable {
        NettyHelper.setNettyLoggerFactory();
        ExecutorService boss = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerBoss", true));
        ExecutorService worker = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerWorker", true));
        ChannelFactory channelFactory = new NioServerSocketChannelFactory(boss, worker, getUrl().getPositiveParameter(Constants.IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS));
        bootstrap = new ServerBootstrap(channelFactory);

        /**
         * 本方法@1@2引起了我的注意，首先创建NettyServer必须传入一个服务提供者URL，但从DubboProtocol#createServer中可以看出，
         * Server是基于网络套接字（ip:port）缓存的，一个JVM应用中，必然会存在多个dubbo:server标签，就会有多个URL，这里为什么可以
         * 这样做呢？从DubboProtocol#createServer中可以看出，在解析第二个dubbo:service标签时并不会调用createServer,而是会调
         * 用Server#reset方法，是不是这个方法有什么魔法，在reset方法时能将URL也注册到Server上，那接下来分析NettyServer#reset
         * 方法是如何实现的
         */
        final NettyHandler nettyHandler = new NettyHandler(getUrl(), this);//@1
        channels = nettyHandler.getChannels();
        // https://issues.jboss.org/browse/NETTY-365
        // https://issues.jboss.org/browse/NETTY-379
        // final Timer timer = new HashedWheelTimer(new NamedThreadFactory("NettyIdleTimer", true));
        bootstrap.setOption("child.tcpNoDelay", true);
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() {
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                ChannelPipeline pipeline = Channels.pipeline();
                /*int idleTimeout = getIdleTimeout();
                if (idleTimeout > 10000) {
                    pipeline.addLast("timer", new IdleStateHandler(timer, idleTimeout / 1000, 0, 0));
                }*/

                /**
                 * 可以看出，传入Netty框架的事件处理Handler主要是3个：1、解码器；2、编码器；3、业务类NettyHandler。
                 * 也就是说当服务端(Server)的读事件就绪后，进行网络读写后，会将二进制流传入解码器(Decoder)，解码出一个一
                 * 个的RPC请求，然后针对每一个RPC请求，交给NettyHandler相关事件处理方法去处理，在这里传入NettyHandler的
                 * ChannelHandler为NettyServer,以网络读命令为例，最终将调用NettyServer的父类AbstractPeer的received方法
                 */

                pipeline.addLast("decoder", adapter.getDecoder());
                pipeline.addLast("encoder", adapter.getEncoder());
                pipeline.addLast("handler", nettyHandler);//@2
                return pipeline;
            }
        });
        // bind
        channel = bootstrap.bind(getBindAddress());
    }

    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            Collection<org.apache.dubbo.remoting.Channel> channels = getChannels();
            if (channels != null && !channels.isEmpty()) {
                for (org.apache.dubbo.remoting.Channel channel : channels) {
                    try {
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (bootstrap != null) {
                // release external resource.
                bootstrap.releaseExternalResources();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (channels != null) {
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new HashSet<Channel>();
        for (Channel channel : this.channels.values()) {
            if (channel.isConnected()) {
                chs.add(channel);
            } else {
                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
            }
        }
        return chs;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public boolean isBound() {
        return channel.isBound();
    }

}
