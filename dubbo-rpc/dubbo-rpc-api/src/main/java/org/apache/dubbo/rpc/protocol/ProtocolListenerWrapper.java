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
import org.apache.dubbo.rpc.ExporterListener;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.listener.ListenerExporterWrapper;
import org.apache.dubbo.rpc.listener.ListenerInvokerWrapper;

import java.util.Collections;

/**
 * 一个支持监听器特性的Protocal的包装器。支持两种监听器的功能扩展，分
 * 别是：ExporterListener是远程服务发布监听器，可以监听服务发布和取消
 * 发布两个事件点；InvokerListener是服务消费者引用调用器的监听器，可以
 * 监听引用和销毁两个事件方法。
 * -------------------------------------------------------------
 * ListenerProtocol
 * 在这里我们可以看到 export 和 refer 分别对应了不同的 Wrapper；
 * 经过debug，发现ExporterListener并没有实现类，同时通过 ebug也会发现
 * ListenerExporterWrapper 在执行过程中确实 listeners 变量是空的
 *
 * 而对于 ListenerInvokerWrapper ， 我 们 发 现 在 如 下 文 件 中 ：
 * dubbo-rpc-api/src/main/resources/META-INF/dubbo/internal/com.alibaba.dubbo.rpc.InvokerListener
 * deprecated=com.alibaba.dubbo.rpc.listener.DeprecatedInvokerListener
 * public class DeprecatedInvokerListener extends InvokerListenerAdapter
 *
 *
 * 看完了ProtocolFilterWrapper，我们再看下他的下一个ProtocolListenerWrapper。
 */
public class ProtocolListenerWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolListenerWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    /**
     * Exporter包装成ListenerExporterWrapper的实例，他是原来的exporter和从spi扩展点中获取的ExporterListener的实例组成
     * @param invoker Service invoker 服务的执行体
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        //特殊协议，跳过监听器触发
        if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);
        }
        //调用原始协议的发布方法，触发监听器链事件。
        return new ListenerExporterWrapper<T>(protocol.export(invoker),
                Collections.unmodifiableList(ExtensionLoader.getExtensionLoader(ExporterListener.class)
                        .getActivateExtension(invoker.getUrl(), Constants.EXPORTER_LISTENER_KEY)));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return protocol.refer(type, url);
        }
        return new ListenerInvokerWrapper<T>(protocol.refer(type, url),
                Collections.unmodifiableList(
                        ExtensionLoader.getExtensionLoader(InvokerListener.class)
                                .getActivateExtension(url, Constants.INVOKER_LISTENER_KEY)));
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

}
