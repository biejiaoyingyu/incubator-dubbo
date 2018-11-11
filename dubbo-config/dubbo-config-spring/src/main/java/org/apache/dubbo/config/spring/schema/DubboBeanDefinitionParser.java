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

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ArgumentConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.rpc.Protocol;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AbstractBeanDefinitionParser
 *
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    private final Class<?> beanClass;
    private final boolean required;

    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    @SuppressWarnings("unchecked")
    /**
     * 返回的是bean的定义信息
     * https://blog.csdn.net/heroqiang/article/details/82766196
     */
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        //设置懒加载为false，表示立即加载，spring启动时，立刻进行实例化
        //如果设置为true，那么要第一次向容器通过getBean索取bean时实例化，在spring bean的配置里可以配置
        //这里会设置懒加载为false其实还可以得到一个推断就是:dubbo标签创建的bean就是单例bean(singleton bean)
        //因为lazy-init的设置只对singleton bean有效，对原型bean(prototype无效)
        beanDefinition.setLazyInit(false);
        //=====================
        //下面步骤是获取bean的id
        //=====================
        String id = element.getAttribute("id");

        //1.如果没有设置bean的id(一般dubbo标签都不会配置id属性和requied属性),required默认为==>传进来的方法参数true
        //<dubbo:reference id = "必填"，interface="必填" 无name属性>

        if ((id == null || id.length() == 0) && required) {
            //1.1.获取标签的name的名字
            String generatedBeanName = element.getAttribute("name");
            //1.1.1如果没有name属性

            //  <dubbo:application name = "工程名(这个是必填的)" version = "1.0.0" 没有id属性>,
            //  <dubbo:modul name = "必填" 无id属性>
            //  <dubbo:protocol id = “可选（缺省值为dubbo）” name= "dubbo （必填？缺省值为dubbo ）" port = "20880" serialization = "kryo">,<dubbo:protocol name= "rest" port = "8888">一般会配置name属性，其他的不一定
            if (generatedBeanName == null || generatedBeanName.length() == 0) {

                if (ProtocolConfig.class.equals(beanClass)) {
                    //1.1.1.1.如果是ProtocolConfig类型，bean name默认为 dubbo，
                    generatedBeanName = "dubbo";
                } else {
                    //1.1.1.2其他的为配置的interface属性的值 包括<dubbo:service interface = “暴露接口名（必填）”， ref = “引用实体类（必填）” 没有name属性，没有id属性>
                    generatedBeanName = element.getAttribute("interface");
                }
            }
            //1.1.2如果name还为null 那么取 beanClass 的名字，beanClass 其实就是要解析的类型如：
            // com.alibaba.dubbo.config.RegistryConfig <dubbo: registry id = "可选" 没有name属性>
            // com.alibaba.dubbo.config.MonitorConfig  <dubbo:monitor 只有 protocol = “可选” address = “可选”>
            // com.alibaba.dubbo.config.ProviderConfig  <dubbo:provider id = "可选" 没有name属性  >
            // com.alibaba.dubbo.config.ConsumerConfig <dubbo:consumer 没有id，name，interface>
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                generatedBeanName = beanClass.getName();//如=====>com.alibaba.dubbo.config.RegistryConfig所以多个注册中心需要为每个注册中心取id
            }
            //如果id没有设置那么 id=generatedBeanName,如果是ProtocolConfig类型的话自然就是 协议名默认dubbo
            id = generatedBeanName;
            int counter = 2;
                /*
                 * 由于spring的bean id不能重复，但有些标签可能会配置多个如：<dubbo:registry> <dubbo:protocol>
                 * 所以 id 在后面加数字 2、3、4 区分
                 * 一直循环，然后看看前面有没有累积id
                 */
            while (parserContext.getRegistry().containsBeanDefinition(id)) {
                id = generatedBeanName + (counter++);
            }
        }
        //2.到这里，说明标签的id,name,interface,className解析完毕为id ，id不为空
        if (id != null && id.length() > 0) {
            // 如果到这里判断如果当前Spring上下文中包含当前bean id，则抛出bean id冲突的异常
            if (parserContext.getRegistry().containsBeanDefinition(id)) {
                throw new IllegalStateException("Duplicate spring bean id " + id);
            }
                /*
                 * 注册 bean 定义
                 * org.springframework.beans.factory.support.DefaultListableBeanFactory#registerBeanDefinition
                 * 会按照 id 即beanName做一些检查,判断是否重载已加载过的bean等等
                 * 跟到代码里其实 bean 的注册也是放到 ConcurrentHashMap 里
                 * beanName也就是这里的 id 会放到 list 里
                 */

            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);
            //给bean添加属性值，给当前的标签添加id值
            beanDefinition.getPropertyValues().addPropertyValue("id", id);
        }


        //=========================
        //2.下面步骤对每个标签做特殊处理
        //=========================
        //如果是<dubbo:protocol>标签
        if (ProtocolConfig.class.equals(beanClass)) {
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
                //获取所有的注册信息
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
                //获取属性为protocol的PropertyValue
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
                //前面有标签有protocol属性===>放的是ProtocolConfig还是String
                if (property != null) {
                    Object value = property.getValue();
                    //获取协议的名称.getName()=====><dubbo:protobol>注册的时候会将name属性作为id注册BeanDefinition
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        //表明可以有多个

                        /**
                         * 在Spring中，Bean的解析阶段，会把xml配制中的<bean>标签解析成Spring中的BeanDefinition对像，
                         * 我们知道一个bean可能需要依赖其他的bean，在XML配置中可以表现为
                         *  <bean class="foo.bar.xxx">
                         *     <property name="referBeanName" ref="otherBeanName" />
                         *  </bean>
                         *  在Spring的解析段，其实容器中是没有依赖的Bean的实例的因此，那么这是这个被依赖的Bean如何在BeanDefinition中表示呢？
                         *  答案就是RuntimeBeanReference，在解析到依赖的Bean的时侯，解析器会依据依赖bean的name创建一个RuntimeBeanReference
                         *  对像，将这个对像放入BeanDefinition的MutablePropertyValues中。
                         *
                         *  reference = new RuntimeBeanReference("otherBeanName");
                         *  xxxBeanDefinition.getPropertyValues().addPropertyValue("referBeanName", reference);
                         *
                         *
                         */
                        //给这定义信息添加依赖bean====>后面的标签有protocol属性怎么办？
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
                    }
                }
            }
        } else if (ServiceBean.class.equals(beanClass)) { //解析<dubbo:service>
            String className = element.getAttribute("class"); //获取类全名？？？？===>不是interface么？
            if (className != null && className.length() > 0) {
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                //通过反射获取类
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                  /*
                    解析子节点，有些配置可能是：
                    <dubbo:service interface="com.alihealth.dubbo.api.drugInfo.service.DemoService" executes="10" >
                        <property  ref="demoService" name="ref"></property>
                        <property  value="1.0.0" name="version"></property>
                     </dubbo:service>
                   */

                parseProperties(element.getChildNodes(), classDefinition);
                  /*
                    ref直接设置成了 接口名 + Impl 的bean ？
                    如：com.alihealth.dubbo.api.drugInfo.service.DemoService  + Impl 的bean为啥？====>引用的默认实现？
                    那<dubbo:service ref是必填的>里定义的 ref 属性有啥用？？
                    相当于有两个ref属性对应的对象
                   */
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));
            }
        } else if (ProviderConfig.class.equals(beanClass)) {
        /**
         *  <dubbo:provider 为缺省配置 ，所以在解析的时候，如果<dubbo:service>有些值没配置，那么会用<dubbo:provider></dubbo:provider>值进行填充
         */
         /* 解析嵌套的元素 */
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {
            /*
             * 同上
             */
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }
        //存储对象的所有属性
        Set<String> props = new HashSet<String>();
        ManagedMap parameters = null;
        // 遍历beanClass的public方法(这种做法包括继承的方法)

        for (Method setter : beanClass.getMethods()) {
            String name = setter.getName();
            //给model注入值时,如ServiceConfig,// 判断是否是public的有参数的setter方法
            //=========================
            //下面是利用反射截取对象的属性
            //=========================
            if (name.length() > 3 && name.startsWith("set") && Modifier.isPublic(setter.getModifiers()) && setter.getParameterTypes().length == 1) {
                //方法参数类型，因为参数只能是1，所以直接取[0]
                Class<?> type = setter.getParameterTypes()[0];
                // 将setter驼峰命名去掉set后转成-连接的命名，如setApplicationContext --> application-context
                String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4), "-");


                props.add(property);
                Method getter = null;
                try {
                    // 获取对应属性的getter方法
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        // boolean类型的属性的getter方法可能以is开头，但是set方法没有影响
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                    }
                }
                // 如果没有getter方法或者getter方法不是public修饰符或者setter方法的参数类型与getter方法的返回值类型不同，直接忽略
                if (getter == null || !Modifier.isPublic(getter.getModifiers()) || !type.equals(getter.getReturnType())) {
                    continue;
                }
                 /*
                 * 如果属性为 parameters,如ProtocolConfig里的setParameters(Map<String, String> parameters)
                 * 那么去子节点获取 <dubbo:parameter
                 * <dubbo:protocol name="dubbo" host="127.0.0.1" port="9998" accepts="1000"  >
                     <dubbo:parameter key="adsf" value="adf" />
                     <dubbo:parameter key="errer" value="aerdf" />
                 </dubbo:protocol>
                 */
                if ("parameters".equals(property)) {
                      /* parameters属性解析 */
                    parameters = parseParameters(element.getChildNodes(), beanDefinition);
                } else if ("methods".equals(property)) {
                 /*
                   解析 <dubbo:method 并设置 methods 值 --serviceConfig中
                 */
                   /* methods属性解析 */
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
                } else if ("arguments".equals(property)) {
                /*
                    同上 ,解析<dubbo:argument --- MethodConfig中
                 */
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
                } else {
                    // 获取元素中的对应属性值
                    String value = element.getAttribute(property);
                    if (value != null) {
                        value = value.trim();
                        if (value.length() > 0) {
                            //不发布到任何注册中心时 registry = "N/A"
                            if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                                RegistryConfig registryConfig = new RegistryConfig();
                                registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                                beanDefinition.getPropertyValues().addPropertyValue(property, registryConfig);
                            } else if ("registry".equals(property) && value.indexOf(',') != -1) {
                                //多注册中心用 , 号分隔
                                parseMultiRef("registries", value, beanDefinition, parserContext);
                            } else if ("provider".equals(property) && value.indexOf(',') != -1) {
                                parseMultiRef("providers", value, beanDefinition, parserContext);
                            } else if ("protocol".equals(property) && value.indexOf(',') != -1) {
                                //同上 多协议暴露
                                parseMultiRef("protocols", value, beanDefinition, parserContext);
                            } else {
                                Object reference;
                                // 判断方法的参数是否是基本类型，包括包装类型和String
                                if (isPrimitive(type)) {//如果参数类型为 java 的基本类型
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {
                                        // backward compatibility for the default value in old version's xsd
                                /*
                                    兼容旧版本xsd中的default值,以上配置的值在xsd中有配置defalt值
                                    <xsd:attribute name="version" type="xsd:string" use="optional" default="0.0.0">
                                  */
                                        // 向后兼容旧版本的xsd中的默认值
                                        value = null;
                                    }
                                    //如果是基本类型，直接给属性赋值
                                    reference = value;
                                    // protocol属性
                                } else if ("protocol".equals(property)
                                        //如果属性为 protocol 那么要判断protocol对应的拓展点配置有没有
                                        && ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(value)
                                        //检查当前使用的协议是否已经解析过可能在这里被解析过<dubbo:protocol name="dubbo"
                                        && (!parserContext.getRegistry().containsBeanDefinition(value)
                                        || !ProtocolConfig.class.getName().equals(parserContext.getRegistry().getBeanDefinition(value).getBeanClassName()))) {
                                    if ("dubbo:provider".equals(element.getTagName())) {
                                        logger.warn("Recommended replace <dubbo:provider protocol=\"" + value + "\" ... /> to <dubbo:protocol name=\"" + value + "\" ... />");
                                    }
                                    // backward compatibility
                                    // 兼容旧版本配置
                                    ProtocolConfig protocol = new ProtocolConfig();
                                    protocol.setName(value);

                                    reference = protocol;
                                } else if ("onreturn".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    //同上
                                    String returnRef = value.substring(0, index);
                                    String returnMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(returnRef);
                                    // 添加onreturnMethod属性值
                                    beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);
                                } else if ("onthrow".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String throwRef = value.substring(0, index);
                                    String throwMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(throwRef);
                                    // 添加onthrowMethod属性值
                                    beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);
                                } else if ("oninvoke".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String invokeRef = value.substring(0, index);
                                    String invokeRefMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(invokeRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("oninvokeMethod", invokeRefMethod);
                                } else {
                                    /*
                                        必须是单例bean(singleton),原型bean（prototype）不行,sevice初始化一次,在spring容器里也只有一个 实例
                                        是不是和dubbo的幂等有关，如果为原型bean，那么服务就变成有状态的了
                                     */
                                    // 校验ref属性依赖的bean必须是单例的
                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        if (!refBean.isSingleton()) {
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                        }
                                    }

                                    reference = new RuntimeBeanReference(value);
                                }
                                // 为相关属性添加依赖
                                //如果是基本类型直接给属性赋值
                                beanDefinition.getPropertyValues().addPropertyValue(property, reference);
                            }
                        }
                    }
                }
            }
        }
        // 排除掉上面解析过的，剩余的属性添加到parameters属性中
        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!props.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        return beanDefinition;
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }

    @SuppressWarnings("unchecked")
    private static void parseMultiRef(String property, String value, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        // ,号分割value
        String[] values = value.split("\\s*[,]+\\s*");
        ManagedList list = null;
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            if (v != null && v.length() > 0) {
                if (list == null) {
                    list = new ManagedList();
                }
                list.add(new RuntimeBeanReference(v));
            }
        }
        // 添加对应属性的依赖
        beanDefinition.getPropertyValues().addPropertyValue(property, list);
    }

    /**
     * 从缺省配置中综合自己的配置（这是对于子标签来说的）
     * @param element 当前标签
     * @param parserContext
     * @param beanClass 目标标签的类
     * @param required
     * @param tag 目标标签的类型
     * @param property  目标标签要综合要综合的标签的类型
     * @param ref  目标标签要综合要综合的标签的引用（用于确定要综合哪个标签）
     * @param beanDefinition 当前的标签的定义信息
     *  parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
     *  parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
     */
    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        // 如果子节点不为null，遍历子节点
        if (nodeList != null && nodeList.getLength() > 0) {
            boolean first = true;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // 判断节点名称是否与标签名称相同
                    if (tag.equals(node.getNodeName()) || tag.equals(node.getLocalName())) {
                        if (first) {
                            first = false;
                            String isDefault = element.getAttribute("default");
                            if (isDefault == null || isDefault.length() == 0) {
                                // 如果第一个子节点default属性为null，则设置为false
                                beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                            }
                        }
                        // 递归解析嵌套的子节点
                        BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
                        if (subDefinition != null && ref != null && ref.length() > 0) {
                            // 添加属性依赖
                            subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析子标签
     *  <property  ref="demoService" name="ref"></property>
     *  <property  value="1.0.0" name="version"></property>
     * @param nodeList
     * @param beanDefinition
     */
    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
        // 如果子节点不为null，遍历子节点
        if (nodeList != null && nodeList.getLength() > 0) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // <property/>子标签
                    if ("property".equals(node.getNodeName()) || "property".equals(node.getLocalName())) {
                        //获取name属性
                        String name = ((Element) node).getAttribute("name");
                        if (name != null && name.length() > 0) {
                            // 提取value属性
                            String value = ((Element) node).getAttribute("value");
                            // 提取ref属性
                            String ref = ((Element) node).getAttribute("ref");
                            if (value != null && value.length() > 0) {
                                // value不为null，添加对应属性值
                                beanDefinition.getPropertyValues().addPropertyValue(name, value);
                            } else if (ref != null && ref.length() > 0) {
                                // ref不为null，添加对应属性依赖
                                beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                            } else {
                                throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
        // 如果子节点不为null，遍历子节点
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedMap parameters = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // 判断子节点名称是否是parameter
                    if ("parameter".equals(node.getNodeName())
                            || "parameter".equals(node.getLocalName())) {
                        if (parameters == null) {
                            parameters = new ManagedMap();
                        }
                        // 提取key属性值
                        String key = ((Element) node).getAttribute("key");
                        // 提取value属性值
                        String value = ((Element) node).getAttribute("value");
                        // 判断是否设置hide为true
                        boolean hide = "true".equals(((Element) node).getAttribute("hide"));
                        if (hide) {
                            // 如果设置了hide为true，则为key增加.前缀
                            key = Constants.HIDE_KEY_PREFIX + key;
                        }
                        parameters.put(key, new TypedStringValue(value, String.class));
                    }
                }
            }
            return parameters;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList methods = null;
            // 如果子节点不为null，遍历子节点
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    // 判断子节点名称是否是method
                    if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {
                        // 提取name属性值
                        String methodName = element.getAttribute("name");
                        // name属性为null抛出异常
                        if (methodName == null || methodName.length() == 0) {
                            throw new IllegalStateException("<dubbo:method> name attribute == null");
                        }
                        if (methods == null) {
                            methods = new ManagedList();
                        }
                        // 递归解析method子节点
                        BeanDefinition methodBeanDefinition = parse(((Element) node), parserContext, MethodConfig.class, false);
                        // 拼接name
                        String name = id + "." + methodName;
                        // 构造BeanDefinitionHolder
                        BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(methodBeanDefinition, name);
                        methods.add(methodBeanDefinitionHolder);
                    }
                }
            }
            if (methods != null) {
                // 如果不为null，添加对应属性的依赖
                beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        // 如果子节点不为null，遍历子节点
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList arguments = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) {
                        // 提取index属性值
                        String argumentIndex = element.getAttribute("index");
                        if (arguments == null) {
                            arguments = new ManagedList();
                        }
                        // 递归解析argument子节点
                        BeanDefinition argumentBeanDefinition = parse(((Element) node), parserContext, ArgumentConfig.class, false);
                        // 拼接name
                        String name = id + "." + argumentIndex;
                        // 构造BeanDefinitionHolder
                        BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(argumentBeanDefinition, name);
                        arguments.add(argumentBeanDefinitionHolder);
                    }
                }
            }
            if (arguments != null) {
                // 如果不为null，添加对应属性的依赖
                beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
            }
        }
    }

    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        return parse(element, parserContext, beanClass, required);
    }

}
