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
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.spring.extension.SpringExtensionFactory;
import org.apache.dubbo.config.support.Parameter;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *  Spring在初始化IOC容器时会利用这里注册的BeanDefinitionParser的parse方法获取对应的ReferenceBean的BeanDefinition实例，
 *  由于ReferenceBean实现了InitializingBean接口，在设置了bean的所有属性后会调用afterPropertiesSet方法
 *  ReferenceFactoryBean
 *
 *  利用工厂FactoryBean创建，延迟的
 */
public class ReferenceBean<T> extends ReferenceConfig<T> implements FactoryBean, ApplicationContextAware, InitializingBean, DisposableBean {

    private static final long serialVersionUID = 213195494150089726L;

    private transient ApplicationContext applicationContext;

    public ReferenceBean() {
        super();
    }

    public ReferenceBean(Reference reference) {
        super(reference);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        SpringExtensionFactory.addApplicationContext(applicationContext);
    }

    @Override
    public Object getObject() {
        return get();
    }

    @Override
    public Class<?> getObjectType() {
        return getInterfaceClass();
    }

    @Override
    @Parameter(excluded = true)
    public boolean isSingleton() {
        return true;
    }

    /**
     * 初始化后调用这个方法
     * @throws Exception
     */
    @Override
    @SuppressWarnings({"unchecked"})
    public void afterPropertiesSet() throws Exception {
        //如果Consumer还未注册
        /**
         * 如果consumer为空，说明dubbo:reference标签未设置consumer属性，如果只有一个dubbo:consumer标签，
         * 则取该实例，如果存在多个dubbo:consumer 配置，则consumer属性必须设置，否则会抛出异常：”Duplicate consumer configs”。
         */
        if (getConsumer() == null) {
            //获取applicationContext这个IOC容器实例中的所有ConsumerConfig
            Map<String, ConsumerConfig> consumerConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ConsumerConfig.class, false, false);
            //如果IOC容器中存在这样的ConsumerConfig(注意Map的验空方式)
            if (consumerConfigMap != null && consumerConfigMap.size() > 0) {
                //遍历这些ConsumerConfig
                ConsumerConfig consumerConfig = null;
                for (ConsumerConfig config : consumerConfigMap.values()) {
                    //如果用户没配置Consumer系统会生成一个默认Consumer，且它的isDefault返回ture
                    //这里是说要么是Consumer是默认的要么是用户配置的Consumer并且没设置isDefault属性
                    if (config.isDefault() == null || config.isDefault()) {
                        //防止存在两个默认Consumer
                        if (consumerConfig != null) {
                            throw new IllegalStateException("Duplicate consumer configs: " + consumerConfig + " and " + config);
                        }
                        //获取默认Consumer
                        consumerConfig = config;
                    }
                }
                //设置默认Consumer
                if (consumerConfig != null) {
                    setConsumer(consumerConfig);
                }
            }
        }
        /**
         * 如果application为空,则尝试从BeanFactory中查询dubbo:application实例，
         * 如果存在多个dubbo:application配置，则抛出异常：”Duplicate application configs”。
         */
        //如果reference未绑定application且（reference未绑定consumer或referenc绑定的consumer没绑定application ）
        if (getApplication() == null && (getConsumer() == null || getConsumer().getApplication() == null)) {
            //获取IOC中所有application的实例
            Map<String, ApplicationConfig> applicationConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ApplicationConfig.class, false, false);
            if (applicationConfigMap != null && applicationConfigMap.size() > 0) {
                //如果IOC中存在application
                ApplicationConfig applicationConfig = null;
                //遍历这些application
                for (ApplicationConfig config : applicationConfigMap.values()) {
                    //如果application是默认创建或者被指定成默认
                    if (config.isDefault() == null || config.isDefault()) {
                        if (applicationConfig != null) {
                            throw new IllegalStateException("Duplicate application configs: " + applicationConfig + " and " + config);
                        }
                        //获取application
                        applicationConfig = config;
                    }
                }
                //关联到reference
                if (applicationConfig != null) {
                    setApplication(applicationConfig);
                }
            }
        }
        /**
         * 如果ServiceBean的module为空，则尝试从BeanFactory中查询dubbo:module实例，
         * 如果存在多个dubbo:module，则抛出异常：”Duplicate module configs: “。
         */
        //如果reference未绑定module且（reference未绑定consumer或referenc绑定的consumer没绑定module
        if (getModule() == null
                && (getConsumer() == null || getConsumer().getModule() == null)) {
            //获取IOC中所有module的实例
            Map<String, ModuleConfig> moduleConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ModuleConfig.class, false, false);
            if (moduleConfigMap != null && moduleConfigMap.size() > 0) {
                ModuleConfig moduleConfig = null;
                //遍历这些module
                for (ModuleConfig config : moduleConfigMap.values()) {
                    //如果module是默认创建或者被指定成默认
                    if (config.isDefault() == null || config.isDefault()) {
                        if (moduleConfig != null) {
                            throw new IllegalStateException("Duplicate module configs: " + moduleConfig + " and " + config);
                        }
                        //获取module
                        moduleConfig = config;
                    }
                }
                //关联到reference
                if (moduleConfig != null) {
                    setModule(moduleConfig);
                }
            }
        }
        /**
         * 尝试从BeanFactory中加载所有的注册中心，注意ServiceBean的List< RegistryConfig> registries属性，为注册中心集合。
         */
        //如果reference未绑定注册中心（Register）且（reference未绑定consumer或referenc绑定的consumer没绑定注册中心（Register）
        if ((getRegistries() == null || getRegistries().isEmpty())
                && (getConsumer() == null || getConsumer().getRegistries() == null || getConsumer().getRegistries().isEmpty())
                && (getApplication() == null || getApplication().getRegistries() == null || getApplication().getRegistries().isEmpty())) {
            //获取IOC中所有的注册中心（Register）实例
            Map<String, RegistryConfig> registryConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, RegistryConfig.class, false, false);
            if (registryConfigMap != null && registryConfigMap.size() > 0) {
                List<RegistryConfig> registryConfigs = new ArrayList<>();
                //遍历这些registry
                for (RegistryConfig config : registryConfigMap.values()) {
                    //如果registry是默认创建或者被指定成默认
                    if (config.isDefault() == null || config.isDefault()) {
                        registryConfigs.add(config);
                    }
                }
                if (!registryConfigs.isEmpty()) {
                    //关联到reference，此处可以看出一个consumer可以绑定多个registry（注册中心）
                    super.setRegistries(registryConfigs);
                }
            }
        }
        /**
         * 尝试从BeanFacotry中加载一个监控中心，填充ServiceBean的MonitorConfig monitor属性，
         * 如果存在多个dubbo:monitor配置，则抛出”Duplicate monitor configs:
         */
        //如果reference未绑定监控中心（Monitor）且（reference未绑定consumer或reference绑定的consumer没绑定监控中心（Monitor）
        if (getMonitor() == null
                && (getConsumer() == null || getConsumer().getMonitor() == null)
                && (getApplication() == null || getApplication().getMonitor() == null)) {
            //获取IOC中所有的监控中心（Monitor）实例
            Map<String, MonitorConfig> monitorConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MonitorConfig.class, false, false);
            if (monitorConfigMap != null && monitorConfigMap.size() > 0) {
                MonitorConfig monitorConfig = null;
                //遍历这些监控中心（Monitor）
                for (MonitorConfig config : monitorConfigMap.values()) {
                    //如果monitor是默认创建或者被指定成默认
                    if (config.isDefault() == null || config.isDefault()) {
                        if (monitorConfig != null) {
                            throw new IllegalStateException("Duplicate monitor configs: " + monitorConfig + " and " + config);
                        }
                        monitorConfig = config;
                    }
                }
                //关联到reference,一个consumer绑定到一个监控中心（monitor）
                if (monitorConfig != null) {
                    setMonitor(monitorConfig);
                }
            }
        }
        /**
         * 判断是否初始化，如果为初始化，则调用getObject()方法，该方法也是FactoryBean定义的方法，
         * ReferenceBean是dubbo:reference所真实引用的类(interface)的实例工程，getObject发返回
         * 的是interface的实例，而不是ReferenceBean实例。
         */
        Boolean b = isInit();
        if (b == null && getConsumer() != null) {
            b = getConsumer().isInit();
        }
        if (b != null && b) {
            //如果consumer已经被关联则组装Reference
            /**
             * 方法直接调用其父类的get方法,get方法内部调用init()方法进行初始化
             */
            getObject();
        }
    }

    @Override
    public void destroy() {
        // do nothing
    }
}
