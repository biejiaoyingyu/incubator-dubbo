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
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.config.AsyncFor;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.model.ApplicationModel;
import org.apache.dubbo.config.model.ConsumerModel;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.StaticContext;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.AvailableCluster;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;

/**
 * ReferenceConfig
 *
 * @export
 */
public class ReferenceConfig<T> extends AbstractReferenceConfig {

    private static final long serialVersionUID = -5864351140409987595L;

    private static final Protocol refprotocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    private final List<URL> urls = new ArrayList<URL>();
    // interface name
    private String interfaceName;
    private Class<?> interfaceClass;
    private Class<?> asyncInterfaceClass;
    // client type
    private String client;
    // url for peer-to-peer invocation
    private String url;
    // method configs
    private List<MethodConfig> methods;
    // default config
    private ConsumerConfig consumer;
    private String protocol;
    // interface proxy reference
    private transient volatile T ref;
    private transient volatile Invoker<?> invoker;
    private transient volatile boolean initialized;
    private transient volatile boolean destroyed;
    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        @Override
        protected void finalize() throws Throwable {
            super.finalize();

            if (!ReferenceConfig.this.destroyed) {
                logger.warn("ReferenceConfig(" + url + ") is not DESTROYED when FINALIZE");

                /* don't destroy for now
                try {
                    ReferenceConfig.this.destroy();
                } catch (Throwable t) {
                        logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ") in finalize method!", t);
                }
                */
            }
        }
    };

    public ReferenceConfig() {
    }

    public ReferenceConfig(Reference reference) {
        appendAnnotation(Reference.class, reference);
    }

    private static void checkAndConvertImplicitConfig(MethodConfig method, Map<String, String> map, Map<Object, Object> attributes) {
        //check config conflict
        if (Boolean.FALSE.equals(method.isReturn()) && (method.getOnreturn() != null || method.getOnthrow() != null)) {
            throw new IllegalStateException("method config error : return attribute must be set true when onreturn or onthrow has been setted.");
        }
        //convert onreturn methodName to Method
        String onReturnMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_RETURN_METHOD_KEY);
        Object onReturnMethod = attributes.get(onReturnMethodKey);
        if (onReturnMethod instanceof String) {
            attributes.put(onReturnMethodKey, getMethodByName(method.getOnreturn().getClass(), onReturnMethod.toString()));
        }
        //convert onthrow methodName to Method
        String onThrowMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_THROW_METHOD_KEY);
        Object onThrowMethod = attributes.get(onThrowMethodKey);
        if (onThrowMethod instanceof String) {
            attributes.put(onThrowMethodKey, getMethodByName(method.getOnthrow().getClass(), onThrowMethod.toString()));
        }
        //convert oninvoke methodName to Method
        String onInvokeMethodKey = StaticContext.getKey(map, method.getName(), Constants.ON_INVOKE_METHOD_KEY);
        Object onInvokeMethod = attributes.get(onInvokeMethodKey);
        if (onInvokeMethod instanceof String) {
            attributes.put(onInvokeMethodKey, getMethodByName(method.getOninvoke().getClass(), onInvokeMethod.toString()));
        }
    }

    private static Method getMethodByName(Class<?> clazz, String methodName) {
        try {
            return ReflectUtils.findMethodByMethodName(clazz, methodName);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    public synchronized T get() {
        /**
         * 如果已经初始化，直接返回，如果interfaceName为空，则抛出异常。
         */
        if (destroyed) {
            throw new IllegalStateException("Already destroyed!");
        }
        if (ref == null) {
            init();
        }
        return ref;
    }

    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;
    }

    /**
     *
     *   这步其实是Reference确认生成Invoker所需要的组件是否已经准备好，
     *   都准备好后我们进入生成Invoker的部分。这里的getObject会调用父类
     *   ReferenceConfig的init方法完成组装：
     *
     */
    private void init() {
        //避免重复初始化
        if (initialized) {
            return;
        }
        //置为已经初始化
        initialized = true;
        //如果interfaceName不存在
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        // get consumer's global configuration
        // 获取消费者

        /**
         * 如果dubbo:reference标签也就是ReferenceBean的consumer属性为空，调用appendProperties方法，填充默认属性，其具体加载顺序：
         * 1）从系统属性加载对应参数值，参数键：dubbo.consumer.属性名，从系统属性中获取属性值的方法为：System.getProperty(key)。
         * 2）加载属性配置文件的值。属性配置文件，可通过系统属性：dubbo.properties.file，如果该值未配置，则默认取dubbo.properties
         * 属性配置文件。
         */

        checkDefault();
        /**
         *调用appendProperties方法，填充ReferenceBean的属性，属性值来源与上面一样，当然只填充ReferenceBean中属性为空的属性。
         */
        appendProperties(this);
        //如果未使用泛接口并且consumer已经准备好的情况下，reference使用和consumer一样的泛接口
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        //如果是泛接口那么interface的类型是GenericService
        /**
         * 如果使用返回引用，将interface值替换为GenericService全路径名，如果不是，则加载interfacename，
         * 并检验dubbo:reference子标签dubbo:method引用的方法是否在interface指定的接口中存在。
         */
        if (ProtocolUtils.isGeneric(getGeneric())) {
            interfaceClass = GenericService.class;
        } else {
            //如果不是泛接口使用interfaceName指定的泛接口
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //检查接口以及接口中的方法都是否配置齐全
            checkInterfaceAndMethods(interfaceClass, methods);
        }
        //如果服务比较多可以指定dubbo-resolve.properties文件配置service（service集中配置文件）
        /**
         * 处理dubbo服务消费端resolve机制，也就是说消息消费者只连服务提供者，绕过注册中心。
         */
        /**
         * 从系统属性中获取该接口的直连服务提供者，如果存在 -Dinterface=dubbo://127.0.0.1:20880,
         * 其中interface为dubbo:reference interface属性的值。
         */
        String resolve = System.getProperty(interfaceName);
        String resolveFile = null;
        /**
         * 如果未指定-D属性，尝试从resolve配置文件中查找，从这里看出-D的优先级更高。
         */
        if (resolve == null || resolve.length() == 0) {
            /**
             * 首先尝试获取resolve配置文件的路径，其来源可以通过-Ddubbo.resolve.file=文件路径名来指定，
             * 如果未配置该系统参数，则默认从${user.home}/dubbo-resolve.properties,如果过文件存在，则
             * 设置resolveFile的值，否则resolveFile为null。
             */
            resolveFile = System.getProperty("dubbo.resolve.file");
            if (resolveFile == null || resolveFile.length() == 0) {
                File userResolveFile = new File(new File(System.getProperty("user.home")), "dubbo-resolve.properties");
                if (userResolveFile.exists()) {
                    resolveFile = userResolveFile.getAbsolutePath();
                }
            }
            /**
             * 如果resolveFile不为空，则加载resolveFile文件中内容，然后通过interface获取其配置的直连服务提供者URL。
             */
            if (resolveFile != null && resolveFile.length() > 0) {
                Properties properties = new Properties();
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(new File(resolveFile));
                    properties.load(fis);
                } catch (IOException e) {
                    throw new IllegalStateException("Unload " + resolveFile + ", cause: " + e.getMessage(), e);
                } finally {
                    try {
                        if (null != fis) fis.close();
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
                resolve = properties.getProperty(interfaceName);
            }
        }
        /**
         * 如果resolve不为空，则填充ReferenceBean的url属性为resolve(点对点服务提供者URL)，打印日志，
         * 点对点URL的来源（系统属性、resolve配置文件）
         */
        if (resolve != null && resolve.length() > 0) {
            url = resolve;
            if (logger.isWarnEnabled()) {
                if (resolveFile != null) {
                    logger.warn("Using default dubbo resolve file " + resolveFile + " replace " + interfaceName + "" + resolve + " to p2p invoke remote service.");
                } else {
                    logger.warn("Using -D" + interfaceName + "=" + resolve + " to p2p invoke remote service.");
                }
            }
        }
        //如果application、module、registries、monitor未配置则使用consumer的
        if (consumer != null) {
            if (application == null) {
                application = consumer.getApplication();
            }
            if (module == null) {
                module = consumer.getModule();
            }
            if (registries == null) {
                registries = consumer.getRegistries();
            }
            if (monitor == null) {
                monitor = consumer.getMonitor();
            }
        }
        //如果module已关联则关联module的registries和monitor
        if (module != null) {
            if (registries == null) {
                registries = module.getRegistries();
            }
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        //如果application已关联则关联application的registries和monitor
        if (application != null) {
            if (registries == null) {
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }
        //检查application
        /**
         * 校验ReferenceBean的application是否为空,如果为空，new 一个application，并尝试从系统属性（优先）、资源文件中填充其属性；
         * 同时校验stub、mock实现类与interface的兼容性。系统属性、资源文件属性的配置如下：
         * dubbo.application.属性名，例如  dubbo.application.name
         */
        checkApplication();
        //检查远端和本地服务接口真实存在（是否可load）
        checkStubAndMock(interfaceClass);
        Map<String, String> map = new HashMap<String, String>();
        resolveAsyncInterface(interfaceClass, map);
        //配置dubbo的端属性（是consumer还是provider）、版本属性、创建时间、进程号
        /**
         * 构建Map,封装服务消费者引用服务提供者URL的属性，这里主要填充side:consume（消费端)、dubbo：2.0.0(版本)、timestamp、pid:进程ID。
         */
        Map<Object, Object> attributes = new HashMap<Object, Object>();
        map.put(Constants.SIDE_KEY, Constants.CONSUMER_SIDE);
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        /**
         * 如果不是泛化引用，增加methods:interface的所有方法名，多个用逗号隔开。
         */
        if (!isGeneric()) {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }

            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put("methods", Constants.ANY_VALUE);
            } else {
                map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        /**
         * 用Map存储application配置、module配置、默认消费者参数(ConsumerConfig)、服务消费者dubbo:reference的属性。
         */
        map.put(Constants.INTERFACE_KEY, interfaceName);
        //调用application、module、consumer的get方法将属性设置到map中
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, consumer, Constants.DEFAULT_KEY);
        appendParameters(map, this);
        /**
         * 获取服务键值 /{group}/interface:版本，如果group为空，则为interface:版本,其值存为prifex，
         * 然后将dubbo:method的属性名称也填入map中，键前缀为dubbo.method.methodname.属性名。
         * dubbo:method的子标签dubbo:argument标签的属性也追加到attributes map中，键为 prifex + methodname.属性名。
         */
        String prefix = StringUtils.getServiceKey(map);
        if (methods != null && !methods.isEmpty()) {
            for (MethodConfig method : methods) {
                appendParameters(map, method, method.getName());
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                appendAttributes(attributes, method, prefix + "." + method.getName());
                checkAndConvertImplicitConfig(method, map, attributes);
            }
        }

        /**
         * 填充register.ip属性，该属性是消息消费者连接注册中心的IP，并不是注册中心自身的IP。
         */
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry == null || hostToRegistry.length() == 0) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);

        //attributes are stored by system context.
        //attributes通过系统context进行存储.
        StaticContext.getSystemContext().putAll(attributes);
        //在map装载了application、module、consumer、reference的所有属性信息后创建代理
        /**
         * 调用createProxy方法创建消息消费者代理->观察细节
         */
        ref = createProxy(map);
        /**
         * 将消息消费者缓存在ApplicationModel中
         */
        // 获取唯一服务名称（由group分组、接口名称和版本拼接而成），创建consumer模型
        ConsumerModel consumerModel = new ConsumerModel(getUniqueServiceName(), this, ref, interfaceClass.getMethods());
        // 添加唯一服务名称和consumer模型的映射
        //https://blog.csdn.net/heroqiang/article/details/82882073
        ApplicationModel.initConsumerModel(getUniqueServiceName(), consumerModel);
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    /**
     * 判断该消费者是否是引用本(JVM)内提供的服务。
     * 如果dubbo:reference标签的injvm(已过期，被local属性替换)如果不为空，则直接取该值，如果该值未配置，
     * 则判断ReferenceConfig的url属性是否为空，如果不为空，则isJvmRefer =false，表明该服务消费者将直
     * 连该URL的服务提供者；如果url属性为空，则判断该协议是否是isInjvm，其实现逻辑：
     * 获取dubbo:reference的scop属性，根据其值判断：
     * 1）如果为空，isJvmRefer为false。
     * 2）如果协议为injvm，就是表示为本地协议，既然提供了本地协议的实现，则无需配置isJvmRefer该标签为true，故，isJvmRerfer=false。
     * 3）如果scope=local或injvm=true，isJvmRefer=true。
     * 3）如果scope=remote，isJvmRefer设置为false。
     * 4）如果是泛化引用，isJvmRefer设置为false。
     * 5）其他默认情况，isJvmRefer设置为true。
     */
    private T createProxy(Map<String, String> map) {
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        final boolean isJvmRefer;
        if (isInjvm() == null) {
            if (url != null && url.length() > 0) { // if a url is specified, don't do local reference//指定URL的情况下，不做本地引用
                isJvmRefer = false;
            } else {
                //默认情况下如果本地有服务暴露，则引用本地服务.
                // by default, reference local service if there is
                isJvmRefer = InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl);
            }
        } else {
            isJvmRefer = isInjvm();
        }
        /**
         * 如果消费者引用本地JVM中的服务，则利用InjvmProtocol创建Invoker，dubbo中的invoker主要负责服务调用的功能，
         * 是其核心实现,在这里我们需要知道，会创建于协议相关的Invoker即可。
         */
        if (isJvmRefer) {
            URL url = new URL(Constants.LOCAL_PROTOCOL, NetUtils.LOCALHOST, 0, interfaceClass.getName()).addParameters(map);
            invoker = refprotocol.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {
            /**
             * 处理直连情况
             */
            if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
                // 用户指定URL，指定的URL可能是对点对直连地址，也可能是注册中心URL
                /**
                 * 对直连URL进行分割，多个直连URL用分号隔开，如果URL中不包含path属性，则为URL设置path属性为interfaceName。
                 */
                String[] us = Constants.SEMICOLON_SPLIT_PATTERN.split(url);
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (url.getPath() == null || url.getPath().length() == 0) {
                            url = url.setPath(interfaceName);
                        }
                        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                            /**
                             * 如果直连提供者的协议为registry，则对url增加refer属性，其值为消息消费者所有的属性。(表示从注册中心发现服务提供者)
                             */
                            urls.add(url.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                        } else {
                            /**
                             * 如果是其他协议提供者，则合并服务提供者与消息消费者的属性，并移除服务提供者默认属性。以default开头的属性。
                             */
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }
            } else { // assemble URL from register center's configuration
                // 通过注册中心配置拼装URL
                /**
                 * 普通消息消费者，从注册中心订阅服务
                 */
                /**
                 * 获取所有注册中心URL，其中参数false表示消费端，需要排除dubbo:registry subscribe=false的注册中心，其值为false表示不接受订阅。
                 */
                List<URL> us = loadRegistries(false);
                if (us != null && !us.isEmpty()) {
                    for (URL u : us) {
                        /**
                         * 根据注册中心URL，构建监控中心URL。
                         */
                        URL monitorUrl = loadMonitor(u);
                        /**
                         * 如果监控中心不为空，在注册中心URL后增加属性monitor。
                         */
                        if (monitorUrl != null) {
                            map.put(Constants.MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                        }
                        /**
                         * 在注册中心URL中，追加属性refer，其值为消费端的所有配置组成的URL。
                         */
                        urls.add(u.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                    }
                }
                if (urls.isEmpty()) {
                    throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                }
            }

            /**
             * 根据URL获取对应协议的Invoker。
             */
            if (urls.size() == 1) {
                //此处举例说明如果是Dubbo协议则调用DubboProtocol的refer方法生成invoker，
                // 当用户调用service接口实际调用的是invoker的invoke方法

                /**
                 * 如果只有一个服务提供者URL,则直接根据协议构建Invoker，
                 */
                invoker = refprotocol.refer(interfaceClass, urls.get(0));
            } else {
                //多个service生成多个invoker
                /**
                 * 如果有多个服务提供者，则众多服务提供者构成一个集群。
                 * 首先根据协议构建服务Invoker，默认Dubbo基于服务注册于发现，在服务消费端不会指定url属性，
                 * 从注册中心获取服务提供者列表，此时的URL：registry://开头，url中会包含register属性，其
                 * 值为注册中心的类型，例如zookeeper，将使用RedisProtocol构建Invoker，该方法将自动发现注
                 * 册在注册中心的服务提供者，后续文章将会zookeeper注册中心为例，详细分析其实现原理。
                 */
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                for (URL url : urls) {
                    invokers.add(refprotocol.refer(interfaceClass, url));
                    if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                        // 用了最后一个registry url
                        registryURL = url; // use last registry url
                    }
                }
                if (registryURL != null) { // registry url is available// 有 注册中心协议的URL
                    // use AvailableCluster only when register's cluster is available
                    URL u = registryURL.addParameter(Constants.CLUSTER_KEY, AvailableCluster.NAME);
                    /**
                     * 返回集群模式实现的Invoker
                     * 集群模式的Invoker和单个协议Invoker一样实现Invoker接口，
                     * 然后在集群Invoker中利用Directory保证一个一个协议的调用器，十分的巧妙
                     */
                    invoker = cluster.join(new StaticDirectory(u, invokers));
                } else { // not a registry url
                   // 不是注册中心的URL
                    invoker = cluster.join(new StaticDirectory(invokers));
                }
            }
        }
        /**
         * 如果dubbo:referecnce的check=true或默认为空，则需要判断服务提供者是否存在
         */
        Boolean c = check;
        if (c == null && consumer != null) {
            c = consumer.isCheck();
        }
        if (c == null) {
            c = true; // default true
        }
        if (c && !invoker.isAvailable()) {
            // make it possible for consumer to retry later if provider is temporarily unavailable
            initialized = false;
            throw new IllegalStateException("Failed to check the status of the service " + interfaceName + ". No provider available for the service " + (group == null ? "" : group + "/") + interfaceName + (version == null ? "" : ":" + version) + " from the url " + invoker.getUrl() + " to the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }
        // create service proxy
        // 创建服务代理
        return (T) proxyFactory.getProxy(invoker);
        /**
         * 至此Reference在关联了所有application、module、consumer、registry、monitor、service、protocol
         * 后调用对应Protocol类的refer方法生成InvokerProxy。
         * 当用户调用service时dubbo会通过InvokerProxy调用
         * Invoker的invoke的方法向服务端发起请求。客户端就这样完成了自己的初始化。
         */
    }

    private void checkDefault() {
        if (consumer == null) {
            consumer = new ConsumerConfig();
        }
        appendProperties(consumer);
    }

    private void resolveAsyncInterface(Class<?> interfaceClass, Map<String, String> map) {
        AsyncFor annotation = interfaceClass.getAnnotation(AsyncFor.class);
        if (annotation == null) return;
        Class<?> target = annotation.value();
        if (!target.isAssignableFrom(interfaceClass)) return;
        this.asyncInterfaceClass = interfaceClass;
        this.interfaceClass = target;
        setInterface(this.interfaceClass.getName());
        map.put(Constants.INTERFACES, interfaceClass.getName());
    }


    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (isGeneric()
                || (getConsumer() != null && getConsumer().isGeneric())) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    @Deprecated
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        checkName("client", client);
        this.client = client;
    }

    @Parameter(excluded = true)
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ConsumerConfig getConsumer() {
        return consumer;
    }

    public void setConsumer(ConsumerConfig consumer) {
        this.consumer = consumer;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }

    @Parameter(excluded = true)
    public String getUniqueServiceName() {
        StringBuilder buf = new StringBuilder();
        if (group != null && group.length() > 0) {
            buf.append(group).append("/");
        }
        buf.append(interfaceName);
        if (version != null && version.length() > 0) {
            buf.append(":").append(version);
        }
        return buf.toString();
    }

}
