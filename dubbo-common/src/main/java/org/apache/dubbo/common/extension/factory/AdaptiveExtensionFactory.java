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
package org.apache.dubbo.common.extension.factory;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionFactory
 * 它跟Compiler接口一样设配类注解@Adaptive是打在类AdaptiveExtensionFactory上的不
 * 是通过 javassist 编译生成的
 *
 * AdaptiveExtensionFactory 持有所有 ExtensionFactory 对象的集合，dubbo 内部默认实现
 * 的对象工厂是 SpiExtensionFactory 和 SpringExtensionFactory，他们经过 TreeMap 排好序的查
 * 找顺序是优先先从 SpiExtensionFactory 获取，如果返回空在从 SpringExtensionFactory 获取。
 * 这里我们可以看如下两个文件：
 * /dubbo-common/src/main/resources/META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
 * /dubbo-config-spring/src/main/resources/META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
 *
 *
 * ObjectFactory也是基于dubbo的spi扩展机制的
 * 它跟Compiler接口一样设配类注解@Adaptive是打在类AdaptiveExtensionFactory上的不是通过javassist编译生成的。

 AdaptiveExtensionFactory持有所有ExtensionFactory对象的集合，dubbo内部默认实现的对象工厂是SpiExtensionFactory和SrpingExtensionFactory，

 他们经过TreeMap排好序的查找顺序是优先先从SpiExtensionFactory获取，如果返回空在从SpringExtensionFactory获取。

 1） SpiExtensionFactory工厂获取要被注入的对象，就是要获取dubbo spi扩展的实现，

 　　所以传入的参数类型必须是接口类型并且接口上打上了@SPI注解，返回的是一个设配类对象。

 2） SpringExtensionFactory，Dubbo利用spring的扩展机制跟spring做了很好的融合。在发布或者去
 引用一个服务的时候，会把spring的容器添加到SpringExtensionFactory工厂集合中去，
 当SpiExtensionFactory没有获取到对象的时候会遍历SpringExtensionFactory中的spring容器来获取要注入的对象
 *
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {

    private final List<ExtensionFactory> factories;

    public AdaptiveExtensionFactory() {
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();
        for (String name : loader.getSupportedExtensions()) {
            list.add(loader.getExtension(name));
        }
        factories = Collections.unmodifiableList(list);
    }

    @Override
    public <T> T getExtension(Class<T> type, String name) {
        for (ExtensionFactory factory : factories) {
            /**
             * 从这里我们看出，ObjectFactory. getExtension(Class<T> type, String name)是先从
             * SpiExtensionFactory 获得扩展点，再从 SpringExtensionFactory 获得扩展点
             */
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
