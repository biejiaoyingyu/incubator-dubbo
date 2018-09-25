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
package org.apache.dubbo.config.spring.schema;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;
import org.springframework.beans.factory.xml.NamespaceHandlerSupport;

/**
 * DubboNamespaceHandler
 *
 * 有两个很重要的对象就是Invoker和Exporter，Dubbo会根据用户配置的协议调用不同协议的Invoker，
 * 再通过ReferenceConfig将Invoker的引用关联到Reference的ref属性上提供给消费端调用。
 * 当用户调用一个Service接口的一个方法后由于Dubbo使用javassist动态代理，
 * 会调用Invoker的Invoke方法从而初始化一个RPC调用访问请求访问服务端的Service返回结果
 *
 *
 * 注册解析器
 * 当spring解析xml配置文件时就会调用这些解析器生成对应的BeanDefinition交给spring管理
 *
 * 在很多情况下，我们需要为系统提供可配置化支持，简单的做法可以直接基于 Spring
 * 的标准 Bean 来配置，但配置较为复杂或者需要更多丰富控制的时候，会显得非常笨拙。一
 * 般的做法会用原生态的方式去解析定义好的 xml 文件，然后转化为配置对象，这种方式当然
 * 可以解决所有问题，但实现起来比较繁琐，特别是是在配置非常复杂的时候，解析工作是一
 * 个不得不考虑的负担。Spring 提供了可扩展 Schema 的支持，这是一个不错的折中方案，完
 * 成一个自定义配置一般需要以下步骤：
 *  设计配置属性和 JavaBean
 *  编写 XSD 文件(需要去深入了解一下)
 *  编写 NamespaceHandler 和 BeanDefinitionParser 完成解析工作
 *  编写 spring.handlers 和 spring.schemas 串联起所有部件
 *  在 Bean 文件中应用
 *
 * 下面需要完成解析xsd工作，会用到 NamespaceHandler 和 BeanDefinitionParser 这两个概念。
 * 具体说来 NamespaceHandler 会根据 schema 和节点名找到某个 BeanDefinitionParser，然后由
 * BeanDefinitionParser 完成具体的解析工作。因此需要分别完成 NamespaceHandler 和
 * BeanDefinitionParser 的实现类，Spring 提供了默认实现类 NamespaceHandlerSupport 和
 * AbstractSingleBeanDefinitionParser，简单的方式就是去继承这两个类
 *
 * @export
 */
public class DubboNamespaceHandler extends NamespaceHandlerSupport {

    static {
        Version.checkDuplicate(DubboNamespaceHandler.class);
    }

    /**
     * 也就是将之定义的xsd文件中的配置文件转化成为相应的类
     */
    @Override
    public void init() {
        //配置<dubbo:application>标签解析器
        registerBeanDefinitionParser("application", new DubboBeanDefinitionParser(ApplicationConfig.class, true));
        //配置<dubbo:module>标签解析器
        registerBeanDefinitionParser("module", new DubboBeanDefinitionParser(ModuleConfig.class, true));
        //配置<dubbo:registry>标签解析器
        registerBeanDefinitionParser("registry", new DubboBeanDefinitionParser(RegistryConfig.class, true));
        //配置<dubbo:monitor>标签解析器
        registerBeanDefinitionParser("monitor", new DubboBeanDefinitionParser(MonitorConfig.class, true));
        //配置<dubbo:provider>标签解析器
        registerBeanDefinitionParser("provider", new DubboBeanDefinitionParser(ProviderConfig.class, true));
        //配置<dubbo:consumer>标签解析器
        registerBeanDefinitionParser("consumer", new DubboBeanDefinitionParser(ConsumerConfig.class, true));
        //配置<dubbo:protocol>标签解析器
        registerBeanDefinitionParser("protocol", new DubboBeanDefinitionParser(ProtocolConfig.class, true));
        //配置<dubbo:service>标签解析器
        registerBeanDefinitionParser("service", new DubboBeanDefinitionParser(ServiceBean.class, true));
        //配置<dubbo:refenrence>标签解析器
        registerBeanDefinitionParser("reference", new DubboBeanDefinitionParser(ReferenceBean.class, false));
        //配置<dubbo:annotation>标签解析器
        registerBeanDefinitionParser("annotation", new AnnotationBeanDefinitionParser());
    }

}
