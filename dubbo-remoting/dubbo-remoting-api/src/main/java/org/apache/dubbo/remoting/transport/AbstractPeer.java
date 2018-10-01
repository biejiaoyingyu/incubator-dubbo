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
package org.apache.dubbo.remoting.transport;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Endpoint;
import org.apache.dubbo.remoting.RemotingException;

/**
 * AbstractPeer
 */
public abstract class AbstractPeer implements Endpoint, ChannelHandler {

    /**
     *  事件处理Handler
     *
     *  ???----------->
     *  那AbstractPeer中的ChannelHandler又是“何许人也”，
     *  是通过调用NettyServer(URL url, ChannelHandler handler)中传入的，结合上图中NettyServer的构建流程，可以追溯其流程如下：
     *
     * 1. DubboProtocol#createServer
     *    server = Exchangers.bind(url, requestHandler);       // @1, requestHandler，为最原始的ChannelHandler，接下来整个过程都是对该handler的包装。
     * 2. HeaderExchanger#bind
     *    return new HeaderExchangeServer(Transporters.bind(url, newDecodeHandler(new HeaderExchangeHandler(handler))));
     *    其包装顺序为 DecodeHandler -->HeaderExchangeHandler -->(DubboProtocol#requestHandler)
     * 3. NettyTransporter#bind
     * 4. NettyServer构造函数
     *    super(url, ChannelHandlers.wrap(handler, ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME)));
     *    这里主要包装的是事件的派发Handler，例如AllChannelHandler、ExecutionChannelHandler【Dispatch】业务Handler最终的包装顺序为：
     *    事件派发模型handler[AllChannelHandler] -->DecodeHandler-->HeaderExchangeHandler--> DubboProtocol#requestHandler(最终的业务Handler)。结合网络Netty的处理Handler，服务端事件Handler的处理为：DubboCodec2(解码器)
     *    --> 事件派发模型handler[AllChannelHandler]-->DecodeHandler--> HeaderExchangeHandler--> DubboProtocol#requestHandler(最终的业务Handler)。
     */
    private final ChannelHandler handler;

    /**
     * 该协议的第一个服务提供者的URL,Server只需要用到URL中的参数，与具体某一个服务没什么关系。
     */
    private volatile URL url;

    // closing closed means the process is being closed and close is finished
    private volatile boolean closing;

    private volatile boolean closed;

    public AbstractPeer(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    @Override
    public void send(Object message) throws RemotingException {
        send(message, url.getParameter(Constants.SENT_KEY, false));
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void close(int timeout) {
        close();
    }

    @Override
    public void startClose() {
        if (isClosed()) {
            return;
        }
        closing = true;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        this.url = url;
    }

    @Override
    public ChannelHandler getChannelHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }

    /**
     * @return ChannelHandler
     */
    @Deprecated
    public ChannelHandler getHandler() {
        return getDelegateHandler();
    }

    /**
     * Return the final handler (which may have been wrapped). This method should be distinguished with getChannelHandler() method
     *
     * @return ChannelHandler
     */
    public ChannelHandler getDelegateHandler() {
        return handler;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public boolean isClosing() {
        return closing && !closed;
    }

    @Override
    public void connected(Channel ch) throws RemotingException {
        if (closed) {
            return;
        }
        handler.connected(ch);
    }

    @Override
    public void disconnected(Channel ch) throws RemotingException {
        handler.disconnected(ch);
    }

    @Override
    public void sent(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        handler.sent(ch, msg);
    }

    @Override
    public void received(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        handler.received(ch, msg);
    }

    @Override
    public void caught(Channel ch, Throwable ex) throws RemotingException {
        handler.caught(ch, ex);
    }
}
