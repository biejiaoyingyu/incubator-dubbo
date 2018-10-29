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
 		Step1：解析id属性，如果DubboBeanDefinitionParser对象的required属性为true，如果id为空，则根据如下规则构建一个id。
 //		1）如果name属性不为空，则取name的值，如果已存在，则为 name + 序号,例如  name,name1,name2。
 //		2）如果name属性为空，如果是dubbo:protocol标签，则取protocol属性，其他的则取interface属性，如果不为空，则取该值，但如果已存在，和name处理相同，在后面追加序号。
 //		3）如果第二步还未空，则取beanClass的名称，如果已存在，则追加序号。
 //		Step2：根据不同的标签解析特殊属性。
 //		1）dubbo:protocol,添加protocol属性(BeanDefinition)。
 //		2）dubbo:service,添加ref属性。
 //		3）dubbo:provider，嵌套解析,dubbo:provider标签有两个可选的子标签,dubbo:service、dubbo:parameter,这里需要嵌套解析dubbo:service标签
 //		知识点：
 //		dubbo:provider是配置服务提供者的默认参数，在dubbo spring配置文件中可以配置多个dubbo:provider,那dubbo:service标签如何选取一个合适的dubbo:provider作为其默认参数
 //		呢？有两种办法，其一：将dubbo:service标签直接声明在dubbo:provider方法，其二，在dubbo:service中通过provider属性指定一个provider配置，如果不填，并且存在多个dubbo:provider配置，则会抛出错误。
 //		provider配置。
 //		4）dubbo:customer：解析嵌套标签，其原理与dubbo:provider解析一样。
 //		Step3：解析标签，将属性与值填充到BeanDefinition的propertyValues中。

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
