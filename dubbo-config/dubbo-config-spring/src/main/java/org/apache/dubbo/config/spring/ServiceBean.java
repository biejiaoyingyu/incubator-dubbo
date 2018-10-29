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
package org.apache.dubbo.config.spring;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.spring.extension.SpringExtensionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.AbstractApplicationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ServiceFactoryBean
 *
 * @export
 */
public class ServiceBean<T> extends ServiceConfig<T> implements InitializingBean, DisposableBean, ApplicationContextAware, ApplicationListener<ContextRefreshedEvent>, BeanNameAware {

    private static final long serialVersionUID = 213195494150089726L;

    private final transient Service service;

    private transient ApplicationContext applicationContext;

    private transient String beanName;

    private transient boolean supportedApplicationListener;

    public ServiceBean() {
        super();
        this.service = null;
    }

    public ServiceBean(Service service) {
        super(service);
        this.service = service;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        SpringExtensionFactory.addApplicationContext(applicationContext);
        try {
            Method method = applicationContext.getClass().getMethod("addApplicationListener", ApplicationListener.class); // backward compatibility to spring 2.0.1
            method.invoke(applicationContext, this);
            supportedApplicationListener = true;
        } catch (Throwable t) {
            if (applicationContext instanceof AbstractApplicationContext) {
                try {
                    Method method = AbstractApplicationContext.class.getDeclaredMethod("addListener", ApplicationListener.class); // backward compatibility to spring 2.0.1
                    if (!method.isAccessible()) {
                        method.setAccessible(true);
                    }
                    method.invoke(applicationContext, this);
                    supportedApplicationListener = true;
                } catch (Throwable t2) {
                }
            }
        }
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    /**
     * Gets associated {@link Service}
     *
     * @return associated {@link Service}
     */
    public Service getService() {
        return service;
    }


    /**
     * export 过程（暴露服务）是在如下过程触发
     * onApplicationEvent 函数应该是系统调用的；
     * org.springframework.context.support.AbstractApplicationContext 类中如下函数
     * public void publishEvent(ApplicationEvent event) {
     *      Assert.notNull(event, "Event must not be null");
     *      if (logger.isTraceEnabled()) {
     *      logger.trace("Publishing event in context [" + getId() + "]:
     *      " + event);
     * }
     *      getApplicationEventMulticaster().multicastEvent(event);
     *      if (this.parent != null) {
     *      this.parent.publishEvent(event);
     *  }
     *  }
     *  实际上以上函数是springioc调用如下函数的 finishRefresh 中调用的
     * @param event
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (isDelay() && !isExported() && !isUnexported()) {
            if (logger.isInfoEnabled()) {
                logger.info("The service ready on spring started. service: " + getInterface());
            }
            export();
        }
    }

    /**
     * 如果有设置dubbo:service或dubbo:provider的属性delay，
     * 或配置delay为-1,都表示启用延迟机制，单位为毫秒，设置为-1，表示等到Spring容器初始化后再暴露服务。
     */
    private boolean isDelay() {
        Integer delay = getDelay();
        ProviderConfig provider = getProvider();
        if (delay == null && provider != null) {
            delay = provider.getDelay();
        }
        return supportedApplicationListener && (delay == null || delay == -1);
    }

    /**
     * 在Spring实例化这个bean后会调用接口方法afterPropertiesSet
     * @throws Exception
     */
    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public void afterPropertiesSet() throws Exception {
        //如果没有配置provider
        // <!-- 定义服务提供者默认属性值 -->
        //<dubbo:provider timeout="5000" threadpool="fixed"  threads="100" accepts="1000" token="true"/>

        /**

         *  如果provider为空，说明dubbo:service标签未设置provider属性，如果有一个dubbo:provider标签，
         *  则取该实例，如果存在多个dubbo:provider配置则provider属性不能为空，（那么说明一个服务可以配置多个provider标签么，但是dubbo:service的provider属性需指定一个）

         *  如果provider为空，说明dubbo:service标签未设置provider属性，如果只有一个dubbo:provider标签，
         *  则取该实例，如果存在多个dubbo:provider配置则provider属性不能为空，（开发者手册说明多个取第一个的么？）

         *  否则抛出异常：”Duplicate provider configs”。
         *
         *  https://blog.csdn.net/heroqiang/article/details/79171681
         */
        if (getProvider() == null) {
            //============================================================================================
            //到这里说明<dubbo:service/>没有配置provider属性（缺省的话使用第一个<dubbo:provider>配置：参考官方文档）
            //=============================================================================================
            //获取IOC容器里的所有<dubbo:provider/>(也即ProviderConfig类么？)
            Map<String, ProviderConfig> providerConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProviderConfig.class, false, false);
            if (providerConfigMap != null && providerConfigMap.size() > 0) {
                //获取protocol配置
                Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);

                //=============================================================
                //兼容旧版本====>没有配置<dubbo:protocol>并且有多个<dubbo:provider>
                //=============================================================
                //如果没有protocol配置，但是有provider配置，不可能没有protocol配置？？？
                //存在<dubbo:provider/>但不存在<dubbo:protocol/>配置的情况,也就是说旧版本的protocol配置需要从provider中提取
                if ((protocolConfigMap == null || protocolConfigMap.size() == 0) && providerConfigMap.size() > 1) {

                    // backward compatibility // 兼容旧版本
                    List<ProviderConfig> providerConfigs = new ArrayList<ProviderConfig>();
                    for (ProviderConfig config : providerConfigMap.values()) {
                        // 当<dubbo:provider  default="true"/>时，providerConfigs才会加入 ===>会不会有多个detault="true"(默认用这个)
                        if (config.isDefault() != null && config.isDefault()) {
                            providerConfigs.add(config);
                        }
                    }
                    //关联所有providers
                    //在配置provider的同时，本质是从<dubbo:provider/>中提取<dubbo:protocol/>(可能会有多个)的配置
                    if (!providerConfigs.isEmpty()) {
                        setProviders(providerConfigs);
                    }
                } else {
                    //==================================================================================================
                    // 正常版本==>1.只配配置了<dubbo:protocol/>没有配置<dubbo:provider/>===>不用管<dubbo:provider>
                    //           2.只配置了一个<dubbo:provider/>没有配置<dubbo:protocol/>===>如果default为缺省或者true直接关联
                    //           3.配置了<dubbo:protocol/>并且配置了<dubbo:provider/>(可以为多个)===><dubbo:provider/>的
                    //              default属性只能有一个为缺省值或则true不然会报错
                    //==================================================================================================
                    //到这里由于service标签中provider没有配置属性，那么需要从provider标签中获取一个，这里显示如果有多个就会报错
                    //如果某个provider配置包含子node（ServiceBean），且没有明确指定default，也会被当成默认配置么？应该不会
                    //这个疑问请参看：com\alibaba\dubbo\config\spring\schema\DubboBeanDefinitionParser.java中330行注解
                    ProviderConfig providerConfig = null;
                    for (ProviderConfig config : providerConfigMap.values()) {
                        //已存在<dubbo:protocol/>配置,则找出默认的<dubbo:provider/>配置
                        //在这里补充一下什么是默认的<dubbo:provider/>，是指它的default属性为true或者没有配（没有配为false啊？）。这个默认配置只能一个。
                        if (config.isDefault() == null || config.isDefault()) {
                            //是否为默认的provider
                            if (providerConfig != null) {
                                throw new IllegalStateException("Duplicate provider configs: " + providerConfig + " and " + config);
                            }
                            providerConfig = config;
                        }
                    }
                    //provider属性可以为空或者一个
                    if (providerConfig != null) {
                        setProvider(providerConfig);
                    }
                }
            }
        }
        //如果没有配置application，且没有配置provider
        /**
         * 如果application为空,则尝试从BeanFactory中查询dubbo:application实例，
         * 如果存在多个dubbo:application配置，则抛出异常：”Duplicate application configs”。
         */
        if (getApplication() == null && (getProvider() == null || getProvider().getApplication() == null)) {
            //获取所有applications
            Map<String, ApplicationConfig> applicationConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ApplicationConfig.class, false, false);
            if (applicationConfigMap != null && applicationConfigMap.size() > 0) {
                ApplicationConfig applicationConfig = null;
                for (ApplicationConfig config : applicationConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        if (applicationConfig != null) {
                            throw new IllegalStateException("Duplicate application configs: " + applicationConfig + " and " + config);
                        }
                        applicationConfig = config;
                    }
                }
                //关联application
                if (applicationConfig != null) {
                    setApplication(applicationConfig);
                }
            }
        }
        //如果没有配置module，且没有配置provider
        /**
         * 如果ServiceBean的module为空，则尝试从BeanFactory中查询dubbo:module实例，如果存在多个dubbo:module，
         * 则抛出异常：”Duplicate module configs
         */
        if (getModule() == null && (getProvider() == null || getProvider().getModule() == null)) {
            Map<String, ModuleConfig> moduleConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ModuleConfig.class, false, false);
            if (moduleConfigMap != null && moduleConfigMap.size() > 0) {
                ModuleConfig moduleConfig = null;
                for (ModuleConfig config : moduleConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        if (moduleConfig != null) {
                            throw new IllegalStateException("Duplicate module configs: " + moduleConfig + " and " + config);
                        }
                        moduleConfig = config;
                    }
                }
                //关联module
                if (moduleConfig != null) {
                    setModule(moduleConfig);
                }
            }
        }
        //如果没有配置registries，且没有配置provider或者provider中没有
        /**
         * 尝试从BeanFactory中加载所有的注册中心，注意ServiceBean的List registries属性，为注册中心集合。
         * 1.application标签中有registry属性么？虽然有也不会这样做（但是类里面有相应的属性，用于封装关系）
         * 2.下面是3个条件同时满足才会去ioc中寻找，那么说底下3个条件在解析的时候都会解析registry属性
         * 3.向指定（多个）注册中心注册，那么属性registry=“id1,id2,id3”用逗号隔开，如果不用任何注册中心，可用registry=“N/A‘
         *
         */
        if ((getRegistries() == null || getRegistries().isEmpty())
                && (getProvider() == null || getProvider().getRegistries() == null || getProvider().getRegistries().isEmpty())
               && (getApplication() == null || getApplication().getRegistries() == null || getApplication().getRegistries().isEmpty())
                ) {
            Map<String, RegistryConfig> registryConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, RegistryConfig.class, false, false);
            if (registryConfigMap != null && registryConfigMap.size() > 0) {
                List<RegistryConfig> registryConfigs = new ArrayList<RegistryConfig>();
                for (RegistryConfig config : registryConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        registryConfigs.add(config);
                    }
                }
                //关联registries
                if (!registryConfigs.isEmpty()) {
                    super.setRegistries(registryConfigs);
                }
            }
        }
        //如果没有配置monitor，且没有配置provider
        /**
         * 尝试从BeanFacotry中加载一个监控中心，填充ServiceBean的MonitorConfig monitor属性，
         * 如果存在多个dubbo:monitor配置，则抛出”Duplicate monitor configs
         */
        if (getMonitor() == null
                && (getProvider() == null || getProvider().getMonitor() == null)
                && (getApplication() == null || getApplication().getMonitor() == null)) {
            Map<String, MonitorConfig> monitorConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MonitorConfig.class, false, false);
            if (monitorConfigMap != null && monitorConfigMap.size() > 0) {
                MonitorConfig monitorConfig = null;
                for (MonitorConfig config : monitorConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        if (monitorConfig != null) {
                            throw new IllegalStateException("Duplicate monitor configs: " + monitorConfig + " and " + config);
                        }
                        monitorConfig = config;
                    }
                }
                //关联monitor
                if (monitorConfig != null) {
                    setMonitor(monitorConfig);
                }
            }
        }
        //如果没有配置protocols属性，且没有配置provider
        /**
         * 尝试从BeanFactory中加载所有的协议，注意：ServiceBean的List protocols是一个集合，也即一个服务可以通过多种协议暴露给消费者
         */
        if ((getProtocols() == null || getProtocols().isEmpty())
                && (getProvider() == null || getProvider().getProtocols() == null || getProvider().getProtocols().isEmpty())) {
            Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);
            if (protocolConfigMap != null && protocolConfigMap.size() > 0) {
                List<ProtocolConfig> protocolConfigs = new ArrayList<ProtocolConfig>();
                for (ProtocolConfig config : protocolConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        protocolConfigs.add(config);
                    }
                }
                //关联protocol====>如果没有怎么办?
                if (!protocolConfigs.isEmpty()) {
                    super.setProtocols(protocolConfigs);
                }
            }
        }
        //如果没有配置path ==>缺省为接口名===>服务路径 (注意：1.0不支持自定义路径，总是使用接口名，如果有1.0调2.0，配置服务路径可能不兼容)
        /**
         * 缺省为接口名（服务路径）？？
         * 这里id默认为接口名？？？
         * 设置ServiceBean的path属性，path属性存放的是dubbo:service的beanName（dubbo:service id)。
         * <dubbo:service interface="com.yingjun.dubbox.api.UserService" ref="userService" />
         */
        if (getPath() == null || getPath().length() == 0) {
            if (beanName != null &&
                    beanName.length() > 0 &&
                    getInterface() != null &&
                    getInterface().length() > 0 &&
                    beanName.startsWith(getInterface())) {
                setPath(beanName);
            }
        }
        //暴露provider
        /**
         * 如果为启用延迟暴露机制，则调用export暴露服务。首先看一下isDelay的实现，
         * 然后重点分析export的实现原理（服务暴露的整个实现原理）。
         */
        if (!isDelay()) {
            export();
        }
    }

    @Override
    public void destroy() throws Exception {
        // This will only be called for singleton scope bean, and expected to be called by spring shutdown hook when BeanFactory/ApplicationContext destroys.
        // We will guarantee dubbo related resources being released with dubbo shutdown hook.
        //unexport();
    }

    // merged from dubbox
    @Override
    protected Class getServiceClass(T ref) {
        if (AopUtils.isAopProxy(ref)) {
            return AopUtils.getTargetClass(ref);
        }
        return super.getServiceClass(ref);
    }
}
