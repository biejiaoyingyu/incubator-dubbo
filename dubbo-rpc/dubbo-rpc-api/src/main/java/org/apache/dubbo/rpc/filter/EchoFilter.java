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
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcResult;

/**
 * EchoInvokerFilter
 * 实现com.alibaba.dubbo.rpc.Filter接口。
 *  除了支持默认的过滤器外，Dubbo还支持自定义Filter，可以通过service.filter指定过滤器，多个用英文逗号隔开，其配置方法为：
 *   < dubbo:service ……>
 *      < dubbo:parameter key = “service.filter” value = “filter1,filer2,…”/>
 *   < /dubbo:service>
 *   当然，可以为所有服务提供者设置共用过滤器，其指定方法为：
 *   < dubbo:provider …>
 *      < dubbo:parameter key = “service.filter” value = “filter1,filer2,…”/>
 *   < /dubbo:provider>
 *   消费端自定义过滤器的key为reference.filter，其使用方法在< dubbo:reference/>标签或< dubbo:consumer/>标签下定义属性。
 *   关于自定义Filter的解析代码如下：
 *   ExtensionLoader#getActivateExtension
 */
@Activate(group = Constants.PROVIDER, order = -110000)
public class EchoFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        if (inv.getMethodName().equals(Constants.$ECHO) && inv.getArguments() != null && inv.getArguments().length == 1)
            return new RpcResult(inv.getArguments()[0]);
        return invoker.invoke(inv);
    }

}
