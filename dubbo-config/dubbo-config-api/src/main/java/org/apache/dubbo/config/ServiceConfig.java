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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.model.ApplicationModel;
import org.apache.dubbo.config.model.ProviderModel;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.ServiceClassHolder;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.utils.NetUtils.LOCALHOST;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
 *
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));
    private final List<URL> urls = new ArrayList<URL>();
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();
    // interface type
    //todo:com.cxf.dubbo.service.BikeService
    private String interfaceName;
    //todo:接口类型 com.cxf.dubbo.service.BikeService
    private Class<?> interfaceClass;
    // reference to interface impl
    //todo:引用的实现类
    private T ref;
    // service name
    //todo:默认为接口名===>com.cxf.dubbo.service.BikeService
    private String path;
    // method configuration
    private List<MethodConfig> methods;
    private ProviderConfig provider;
    private transient volatile boolean exported;

    private transient volatile boolean unexported;

    private volatile String generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
    }


    /**
     * 从每个<dubbo:provider/>中提取出一个<dubbo:protocol/>
     * @param providers
     * @return
     */
    @Deprecated
    private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (protocols == null || protocols.isEmpty()) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    /**
     * 分别设置属性
     * @param provider
     * @return
     */
    @Deprecated
    private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        if (RANDOM_PORT_MAP.containsKey(protocol)) {
            return RANDOM_PORT_MAP.get(protocol);
        }
        return Integer.MIN_VALUE;
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    public synchronized void export() {
        //=========================
        ////如果provider属性不为优先
        //==========================

        if (provider != null) {
            //如果exporter没有配置使用provider所关联的exporter
            if (export == null) {
                //优先使用偏离provider的么？
                export = provider.getExport();
            }
            //如果delay（延迟暴露）没有配置，获取provider的delay
            if (delay == null) {
                delay = provider.getDelay();
            }
        }
        //如果不需要暴露接口则直接返回
        /**
         * 判断是否暴露服务，由dubbo:service export=”true|false”来指定。配置手册中好像没有这个属性啊？？？
         * 默认可以配置，见xds文件。
         */
        if (export != null && !export) {
            return;
        }
        //如果延迟暴露的时间（毫秒级）是存在的，开启线程并等待delay毫秒后开始暴露接口，否则直接执行暴露接口过程
        /**
         * 如果启用了delay机制，如果delay大于0，表示延迟多少毫秒后暴露服务，使用ScheduledExecutorService延迟调度，最终调用doExport方法
         */
        if (delay != null && delay > 0) {
            delayExportExecutor.schedule(()-> doExport(), delay, TimeUnit.MILLISECONDS);
        } else {
            /**
             * 执行具体的暴露逻辑doExport，留意：delay=-1的处理逻辑（基于Spring事件机制触发）
             */
            doExport();
        }
    }

    protected synchronized void doExport() {
        //如果不需要暴露接口则抛出异常
        if (unexported) {
            throw new IllegalStateException("Already unexported!");
        }
        //如果已经暴露则不需要重复暴露
        if (exported) {
            return;
        }
        exported = true;
        //如果interfaceName没配置（这样dubbo就无法找到需要暴露的service对象）则抛出异常
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }
        /**
         * 如果dubbo:service标签也就是ServiceBean的provider属性为空，调用appendProperties方法，填充默认属性，其具体加载顺序：
         * 1）从系统属性加载对应参数值，参数键：dubbo.provider.属性名，System.getProperty。
         * 2）加载属性配置文件的值。属性配置文件，可通过系统属性：dubbo.properties.file，如果该值未配置，则默认取dubbo.properties属性配置文件。
         */
        checkDefault();
        //=========================================================================================================
        //provider已经配置的情况下，如果application、module、registries、monitor、protocol中有未配置的均可以从provider获取
        //=========================================================================================================
        //provider优先
        if (provider != null) {
            if (application == null) {
                application = provider.getApplication();
            }
            if (module == null) {
                module = provider.getModule();
            }
            if (registries == null) {
                registries = provider.getRegistries();
            }
            if (monitor == null) {
                monitor = provider.getMonitor();
            }
            if (protocols == null) {
                protocols = provider.getProtocols();
            }
        }
        //从module中获取注册中心和monitor次之
        if (module != null) {
            if (registries == null ) {
                registries =  module.getRegistries();
            }

            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        //从application中获取注册中心和monitor再次之
        if (application != null) {
            if (registries == null) {
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }
        /**
         * 校验ref与interface属性。如果ref是GenericService，则为dubbo的泛化实现，然后验证interface接口与ref引用的类型是否一致。
         * 什么是泛化实现
         * 泛化调用就是服务消费者端因为某种原因并没有该服务接口，这个原因有很多，比如是跨语言的，一个PHP工程师想调用某个java接口，
         * 他并不能按照你约定，去写一个个的接口，Dubbo并不是跨语言的RPC框架，但并不是不能解决这个问题，这个PHP程序员搭建了一个简单的java web项目，
         * 引入了dubbo的jar包，使用dubbo的泛化调用，然后利用web返回json，这样也能完成跨语言的调用。泛化调用的好处之一就是这个了。
         * 为啥要在服务端验证？？？？===>泛化实现在消费端啊===>文档上确实有这种用法
         *
         * <bean id="genericService" class="com.foo.MyGenericService" />
         * <dubbo:service interface="com.foo.BarService" ref="genericService" />
         */
        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                //寻找interface对应的接口
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //验证这个interfaceClass是不是接口和对应配置的方法是不是存在
            checkInterfaceAndMethods(interfaceClass, methods);
            //验证ref引用的类是interfaceClass的实现
            checkRef();
            generic = Boolean.FALSE.toString();
        }
        //如果是本地服务
        /**
         * dubbo:service local机制，已经废弃，被stub属性所替换。
         */
        if (local != null) {
            if ("true".equals(local)) {
                //如果是本地服务在interfaceName属性后面加上Local
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                //加载service
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //如果是远程服务
        /**
         * 处理本地存根Stub
         *   //stub设为true，表示使用缺省代理类名，即：接口名 + Local后缀，服务接口客户端本地代理类名，用于在客户端执行本地逻辑，如本地缓存等，
         *   该本地代理类的构造函数必须允许传入远程代理对象，构造函数如：public XxxServiceLocal(XxxService xxxService)
         *
         *   远程服务后，客户端通常只剩下接口，而实现全在服务器端，但提供方有些时候想在客户端也执行部分逻辑，那么就在服务消费者这
         *   一端提供了一个Stub类，然后当消费者调用provider方提供的dubbo服务时，客户端生成 Proxy 实例，这个Proxy实例就是我们
         *   正常调用dubbo远程服务要生成的代理实例，然后消费者这方会把 Proxy 通过构造函数传给 消费者方的Stub ，然后把 Stub 暴
         *   露给用户，Stub 可以决定要不要去调 Proxy。会通过代理类去完成这个调用，这样在Stub类中，就可以做一些额外的事，来对服
         *   务的调用过程进行优化或者容错的处理。
         *
         *   按道理说本地存根应该在客户端，但是操作守则上说的是在服务端，而且我们实验的时候在客户端是可以的
         */
        if (stub != null) {
            if ("true".equals(stub)) {
                //这里需要断点看一下interfaceName是什么===>应该是interface属性
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                //加载service
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //判断interfaceClass是stubClass的同类和父类返回true
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }

        /**
         * 校验ServiceBean的application、registry、protocol是否为空，并从系统属性（优先）、资源文件中填充其属性。
         * 系统属性、资源文件属性的配置如下：
         * application       dubbo.application.属性名，例如    dubbo.application.name
         * registry          dubbo.registry.属性名，例如    dubbo.registry.address
         * protocol          dubbo.protocol.属性名，例如 dubbo.protocol.port
         * service           dubbo.service.属性名，例如 dubbo.service.stub
         */
        //检查application
        checkApplication();
        //检查registries
        checkRegistry();
        //检查protocol
        checkProtocol();
        //将所有这些对象的属性关联到provider
        appendProperties(this);
        //检查 local,stub是否存在，是否实现interfaceClass接口，
        //是否有指定的构造函数
        /*并检查是否mock,有两种方式，
          1 return xxx;
          2 指定 mock 类
         */
        checkStubAndMock(interfaceClass);
        if (path == null || path.length() == 0) {
            path = interfaceName;
        }
        //暴露地址
        /**
         * 执行doExportUrls()方法暴露服务，接下来会重点分析该方法。
         */
        doExportUrls();
        /**
         * 将服务提供者信息注册到ApplicationModel实例中。
         */
        ProviderModel providerModel = new ProviderModel(getUniqueServiceName(), this, ref);
        ApplicationModel.initProviderModel(getUniqueServiceName(), providerModel);
    }

    private void checkRef() {
        // reference should not be null, and is the implementation of the given interface
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("unexpected err when unexport" + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * 先遍历ServiceBean的List registries（所有注册中心的配置信息），然后将地址封装成URL对象，
     * 关于注册中心的所有配置属性，最终转换成url的属性(?属性名=属性值)，一个注册中心URL示例：
     * registry://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?
     * application=demo-provider&
     * dubbo=2.0.0&
     * pid=7072&
     * qos.port=22222&
     * registry=zookeeper&
     * timestamp=1527308268041
     */
    private void doExportUrls() {
        //将注册的所有url匹配上对应的协议在服务端暴露出来
        /**
         * loadRegistries(true)，
         * 参数的意思：true，代表服务提供者，
         * false：代表服务消费者，如果是服务提供者，则检测注册中心的配置，
         * 如果配置了register=”false”，则忽略该地址，
         * 如果是服务消费者，并配置了subscribe=”false”
         * 则表示不从该注册中心订阅服务，故也不返回
         *
         * registry://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?
         * application=dubbo-provider&
         * dubbo=2.5.6&
         * file=/data/dubbo/cache/dubbo-provider&
         * pid=21448&
         * registry=zookeeper&
         * timestamp=1524134852031
         */

        //registry://192.168.120.18:2181/com.alibaba.dubbo.registry.RegistryService?
        // application=dubboProvider&
        // client=zkclient&
        // dubbo=2.6.0&
        // pid=4640&
        // registry=zookeeper&
        // timestamp=1541149035777
        List<URL> registryURLs = loadRegistries(true);
        /**
         * 然后遍历配置的所有协议，根据每个协议，向注册中心暴露服务，接下来重点分析doExportUrlsFor1Protocol方法的实现细节。
         * 将服务发布到多种协议的url上，并且携带注册中心列表的参数，从这里我们可以看出dubbo是支持同时将一个服务发布成为多种协
         * 议的，这个需求也是很正常的，客户端也需要支持多协议，根据不同的场景选择合适的协议。
         * 那么dobbo支持哪些协议呢？？？
         */
        for (ProtocolConfig protocolConfig : protocols) {
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        //如果没配置protocol则默认使用dubbo协议，获取协议名称
        String name = protocolConfig.getName();
        if (name == null || name.length() == 0) {
            name = "dubbo";
        }
        //获取application、module、provider、protocol、exporter、registries、monitor所有属性
        /**
         * 用Map存储该协议的所有配置参数，包括协议名称、dubbo版本、当前系统时间戳、进程ID、
         * application配置、module配置、默认服务提供者参数(ProviderConfig)、协议配置、
         * 服务提供Dubbo:service的属性。
         */
        Map<String, String> map = new HashMap<String, String>();
        //服务端还是客户端
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);
        //版本
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        //时间戳
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            //线程id
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        // 为几个配置追加参数
        //应用名
        appendParameters(map, application);
        //模型名
        appendParameters(map, module);

        appendParameters(map, provider, Constants.DEFAULT_KEY);
        appendParameters(map, protocolConfig);
        appendParameters(map, this);
        /**
         * 如果dubbo:service有dubbo:method子标签，则dubbo:method以及其子标签的配置属性，
         * 都存入到Map中，属性名称加上对应的方法名作为前缀。dubbo:method的子标签dubbo:argument,其键为方法名.参数序号。
         */
        // method配置
        if (methods != null && !methods.isEmpty()) {
            for (MethodConfig method : methods) {
                // 追加参数
                appendParameters(map, method, method.getName());
                String retryKey = method.getName() + ".retry";
                // retry设置
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                List<ArgumentConfig> arguments = method.getArguments();
                // 转换参数类型
                if (arguments != null && !arguments.isEmpty()) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        //类型自动转换.
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            //遍历所有方法
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    //匹配方法名称，获取方法签名.
                                    // 目标方法和签名
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        //一个方法中单个callback
                                        // 参数索引-1表示未设置
                                        if (argument.getIndex() != -1) {
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                // 追加参数
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            //一个方法中多个callback
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        /**
         * 添加methods键值对，存放dubbo:service的所有方法名，多个方法名用,隔开，如果是泛化实现，填充genric=true,methods为”*”；
         */
        // 判断服务是否是GenericService类型
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(Constants.GENERIC_KEY, generic);
            map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
        } else {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }
            // 使用javaassist生成接口的包装类，获取方法名称
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
            } else {
                map.put(Constants.METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        /**
         * 根据是否开启令牌机制，如果开启，设置token键，值为静态值或uuid。
         */
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put(Constants.TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(Constants.TOKEN_KEY, token);
            }
        }

        /**
         * 如果协议为本地协议(injvm)，则设置protocolConfig#register属性为false，表示不向注册中心注册服务，
         * 在map中存储键为notify,值为false,表示当注册中心监听到服务提供者发送变化（服务提供者增加、服务提供者
         * 减少等事件时不通知。
         */
        if (Constants.LOCAL_PROTOCOL.equals(protocolConfig.getName())) {
            protocolConfig.setRegister(false);
            map.put("notify", "false");
        }
        // export service
        // 导出服务

        // 服务发布 map 中的一些关键配置信息
            /*
            map = {HashMap@6276}  size = 17
             0 = {HashMap$Node@6516} "side" -> "provider"
             1 = {HashMap$Node@6517} "default.version" -> "1.0"
             2 = {HashMap$Node@6518} "methods" -> "sayHello"
             3 = {HashMap$Node@6519} "dubbo" -> "2.5.6"
             4 = {HashMap$Node@6520} "threads" -> "500"
             5 = {HashMap$Node@6521} "pid" -> "21448"
             6 = {HashMap$Node@6522} "interface" -> "io.ymq.dubbo.api.DemoService"
             7 = {HashMap$Node@6523} "threadpool" -> "fixed"
             8 = {HashMap$Node@6524} "generic" -> "false"
             9 = {HashMap$Node@6525} "default.retries" -> "0"
             10 = {HashMap$Node@6526} "delay" -> "-1"
             11 = {HashMap$Node@6527} "application" -> "dubbo-provider"
             12 = {HashMap$Node@6528} "default.connections" -> "5"
             13 = {HashMap$Node@6529} "default.delay" -> "-1"
             14 = {HashMap$Node@6530} "default.timeout" -> "10000"
             15 = {HashMap$Node@6531} "anyhost" -> "true"
             16 = {HashMap$Node@6532} "timestamp" -> "1524135271940"

             https://blog.csdn.net/yanpenglei/article/details/80261762
            */
        /**
         * 设置协议的contextPath,如果未配置，默认为/interfacename
         */
        String contextPath = protocolConfig.getContextpath();
        if ((contextPath == null || contextPath.length() == 0) && provider != null) {
            contextPath = provider.getContextpath();
        }
        /**
         * 解析服务提供者的IP地址与端口。
         * 服务IP地址解析顺序：（序号越小越优先）
         * 1)系统环境变量，变量名：DUBBO_DUBBO_IP_TO_BIND
         * 2)系统属性,变量名：DUBBO_DUBBO_IP_TO_BIND
         * 3)系统环境变量，变量名：DUBBO_IP_TO_BIND
         * 4)系统属性，变量名：DUBBO_IP_TO_BIND
         * 5)dubbo:protocol 标签的host属性  –> dubbo:provider 标签的host属性
         * 6)默认网卡IP地址，通过InetAddress.getLocalHost().getHostAddress()获取，如果IP地址不符合要求，继续下一个匹配。
         * 判断IP地址是否符合要求的标准是：
         */
        //获取主机地址
        /* 注册并绑定服务提供者的IP地址，可以单独配置 */
        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        //获取协议接口号
        /**
         *  服务提供者端口解析顺序：（序号越小越优先）
         * 1)系统环境变量，变量名：DUBBO_DUBBO_PORT_TO_BIND
         * 2)系统属性，变量名：DUBBO_DUBBO_PORT_TO_BIND
         * 3)系统环境变量，变量名：DUBBO_PORT_TO_BIND
         * 4)系统属性，变量名DUBBO_PORT_TO_BIND
         * 5)dubbo:protocol标签port属性、dubbo:provider标签的port属性。
         * 6)随机选择一个端口。
         */
        /* 为提供者注册端口和绑定端口，可以单独配置 */
        Integer port = this.findConfigedPorts(protocolConfig, name, map);
        //创建服务所在url
        /**
         * 根据协议名称、协议host、协议端口、contextPath、相关配置属性（application、module
         * provider、protocolConfig、service及其子标签）构建服务提供者URI。
         *
         * --------------------------------------------------------------------
         * 以dubbo协议为例，展示最终服务提供者的URL信息如下：
         * dubbo://192.168.56.1:20880/com.alibaba.dubbo.demo.DemoService?
         * anyhost=true&
         * application=demo-provider&
         * bind.ip=192.168.56.1&
         * bind.port=20880&
         * dubbo=2.0.0&
         * generic=false&
         * interface=com.alibaba.dubbo.demo.DemoService&
         * methods=sayHello&
         * pid=5916&
         * qos.port=22222&
         * side=provider&
         * timestamp=1527168070857
         */

        /*
            dubbo://10.4.81.95:20880/io.ymq.dubbo.api.DemoService?
            anyhost=true&
            application=dubboprovider&
            default.connections=5&
            default.delay=-1&
            default.retries=0&
            default.timeout=10000&
            default.version=1.0&
            delay=-1&
            dubbo=2.5.6&
            generic=false&
            interface=io.ymq.dubbo.api.DemoService&
            methods=sayHello&
            pid=21448&
            side=provider&
            threadpool=fixed&
            threads=500&
            timestamp=1524135271940
            */
        URL url = new URL(name, host, port, (contextPath == null || contextPath.length() == 0 ? "" : contextPath + "/") + path, map);


        /**
         * 为什么要去获取扩展点的？
         */
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).hasExtension(url.getProtocol())) {
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        /**
         * 接口暴露实现逻辑
         * 获取dubbo:service标签的scope属性，其可选值为none(不暴露)、local(本地)、remote(远程)，如果配置为none，则不暴露。默认为local。
         */
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // don't export when none is configured
        //配置为none不暴露
        /**
         * 根据scope来暴露服务，如果scope不配置，则默认本地与远程都会暴露，如果配置成local或remote，那就只能是二选一。
         */
        if (!Constants.SCOPE_NONE.equalsIgnoreCase(scope)) {
            // export to local if the config is not remote (export to remote only when config is remote)
            //配置不是remote的情况下做本地暴露 (配置为remote，则表示只暴露远程服务)
            /**
             * 如果scope不为remote，则先在本地暴露(injvm):，具体暴露服务的具体实现，将在remote 模式中详细分析
             */
            if (!Constants.SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                //暴露的地址是localhost所以远端无法访问
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            //如果配置不是local则暴露为远程服务.(配置为local，则表示只暴露本地服务)
            /**
             * 如果scope不为local,则将服务暴露在远程。
             */
            if (!Constants.SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                /**
                 * remote方式，检测当前配置的所有注册中心，如果注册中心不为空，则遍历注册中心，将服务依次在不同的注册中心进行注册
                 */
                if (registryURLs != null && !registryURLs.isEmpty()) {
                    for (URL registryURL : registryURLs) {
                        /**
                         * 如果dubbo:service的dynamic属性未配置，尝试取dubbo:registry的dynamic属性，该属性的作用是否启用动态注册，如果设置为false，
                         * 服务注册后，其状态显示为disable，需要人工启用，当服务不可用时，也不会自动移除，同样需要人工处理，此属性不要在生产环境上配置。
                         * 默认为true
                         */
                        url = url.addParameterIfAbsent(Constants.DYNAMIC_KEY, registryURL.getParameter(Constants.DYNAMIC_KEY));
                        /**
                         *  根据注册中心url(注册中心url)，构建监控中心的URL，如果监控中心URL不为空，则在服务提供者URL上追加monitor，其值为监控中心url(已编码)。
                         *  1）如果dubbo spring xml配置文件中没有配置监控中心(dubbo:monitor),
                         *  从系统属性-Ddubbo.monitor.address，-Ddubbo.monitor.protocol构建MonitorConfig对象（只有这两个属性），
                         *  否则从dubbo的properties（这个配置文件在哪里？？？）配置文件中寻找这个两个参数，如果没有配置，则返回null。
                         *  2）如果有配置，则追加相关参数，dubbo:monitor标签只有两个属性：address、protocol，其次会追加interface(MonitorService)、协议等。
                         */
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }

                        // For providers, this is used to enable custom proxy to generate invoker

                        String proxy = url.getParameter(Constants.PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(Constants.PROXY_KEY, proxy);
                        }
                        //获取invoker
                        /**
                         * 通过动态代理机制创建Invoker，dubbo的远程调用实现类。
                         *
                         * 根据 首先获取ref的代理对象，真正的服务实现类proxy，然后通过proxyFactory【JavassistProxyFactory、JdkProxyFactory】
                         * 创建最原始的Invoker，即AbstractProxyInvoker，使用的是匿名实现类，即提供反射方式进行方法的调用。
                         * 从abstract Object doInvoker(T proxy, String methodName, Class< ? >[] parameterTypes, Object[] arguments)
                         * 可以最终是通过对象发射方式进行方法调用。
                         *
                         */
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));
                        /**
                         * Dubbo远程调用器如何构建，这里不详细深入，重点关注WrapperInvoker的url为:
                         * registry://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?
                         * application=demo-provider&
                         * dubbo=2.0.0&
                         * export=dubbo%3A%2F%2F192.168.56.1%3A20880%2Fcom.alibaba.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bind.ip%3D192.168.56.1%26bind.port%3D20880%26dubbo%3D2.0.0%26generic%3Dfalse%26interface%3Dcom.alibaba.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D6328%26qos.port%3D22222%26side%3Dprovider%26timestamp%3D1527255510215&
                         * pid=6328&
                         * qos.port=22222&
                         * registry=zookeeper&
                         * timestamp=152725551020
                         * ----------------------------------------------
                         * 1）path属性：com.alibaba.dubbo.registry.RegistryService，注册中心也类似于服务提供者。
                         * 2）export属性：值为服务提供者的URL，为什么需要关注这个URL呢？
                         * 请看代码@7，protocol属性为Protocol$Adaptive，Dubbo在加载组件实现类时采用SPI
                         * (插件机制，有关于插件机制，在该专题后续文章将重点分析)，在这里我们只需要知道，根据URL冒号之前的协议名将会调用相应的方法。
                         * ----------------------------------------------
                         * 其映射关系(列出与服务启动相关协议实现类)：
                         * dubbo=com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol
                         * //文件位于dubbo-rpc-dubbo/src/main/resources/META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Protocol
                         * registry=com.alibaba.dubbo.registry.integration.RegistryProtocol
                         * //文件位于dubbo-registry-api/src/main/resources/META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Protocol
                         * 代码@7：根据代码@6的分析，将调用RegistryProtocol#export方法。
                         *
                         *
                         * 首先使用DelegateProviderMetaDataInvoker对AbstractProxyInvoker进行包装，主要是将ServerConfig对象与Invoker一起保存。
                         */

                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
                        //根据协议将invoker暴露成exporter，具体过程是创建一个ExchangeServer，它会绑定一个ServerSocket到配置端口
                        /**
                         * 根据具体协议对服务端Invoker进行导出（继续包装）。
                         * registry://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?
                         * application=demo-provider&
                         * dubbo=2.0.0&
                         * export=dubbo%3A%2F%2F192.168.56.1%3A20880%2Fcom.alibaba.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bind.ip%3D192.168.56.1%26bind.port%3D20880%26dubbo%3D2.0.0%26generic%3Dfalse%26interface%3Dcom.alibaba.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D14360%26qos.port%3D22222%26side%3Dprovider%26stub%3Dcom.alibaba.dubbo.demo.provider.DemoServiceStub%26timestamp%3D1533944510702&
                         * pid=14360&
                         * qos.port=22222&
                         * registry=zookeeper&
                         * timestamp=1533944510687
                         *
                         * 协议前缀:registry，故根据SPI机制，具体的协议为RegistryProtocol。
                         * registry=zookeeper ：代表注册中心使用zookeeper，在连接注册中心时根据该值进行策略选择。
                         * export= dubbo://…   :  根据export，在服务端按照协议启动对应的服务端程序，该协议注意指定请求包的二进制协议，例如协议头和协议体。
                         * 按照registry协议，应该会直接调用RegistryProtocol#export，但我们忽略了Dubbo的另一机制，该部分也是在服务提供者启动流程中被遗漏。
                         * Dubbo为了对服务调用进行包装，采用了过滤器Filter 链模式，在AbstractProxyInvoker调用之前，先执行一系列的过滤器Filter，
                         * Dubbo协议默认的协议层面的过滤器代理实现为：com.alibaba.dubbo.rpc.protocol.ProtocolListenerWrapper,SPI定义文件见：
                         * dubbo-rpc-api/src/main/resources/METAINF/dubbo/internal/com.alibaba.dubbo.rpc.Protocol：
                         */

                        // export属性：值为服务提供者的URL，为什么需要关注这个URL呢？
                        // protocol属性为Protocol$Adaptive，Dubbo在加载组件实现类时采用SPI
                        // (插件机制，有关于插件机制，在该专题后续文章将重点分析)，在这里我们只需要知道，
                        // 根据URL冒号之前的协议名将会调用相应的方法。
                        // 这里是registry:// ======>这个很重要，所以自适应为RegistryProtocol(深刻理解自适应)
                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        //将创建的exporter放进链表便于管理
                        exporters.add(exporter);
                    }
                } else {
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    exporters.add(exporter);
                }
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            URL local = URL.valueOf(url.toFullString())
                    .setProtocol(Constants.LOCAL_PROTOCOL)
                    .setHost(LOCALHOST)
                    .setPort(0);
            ServiceClassHolder.getInstance().pushServiceClass(getServiceClass(ref));
            Exporter<?> exporter = protocol.export(
                    proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;
        // 从系统属性中获取dubbo ip绑定配置，如果是非法ip（空、localhost、0.0.0.0、127.*.*.*都属于非法），抛出异常
        String hostToBind = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_BIND);
        /**
         * 判断ip是否符合标准,如果从环境中没有获取到配置，继续寻找
         */
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + Constants.DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (hostToBind == null || hostToBind.length() == 0) {
            hostToBind = protocolConfig.getHost();
            if (provider != null && (hostToBind == null || hostToBind.length() == 0)) {
                // 到这里没有找到则再次尝试从provider配置中获取
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    // 如果到这里获取的ip为空或者非法，则尝试赋值为本地主机地址
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {
                    if (registryURLs != null && !registryURLs.isEmpty()) {
                        for (URL registryURL : registryURLs) {
                            if (Constants.MULTICAST.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            try {
                                //创建socket，连接到注册中心
                                /**
                                 * 选择第一个可用网卡，其实现方式是建立socket，连接注册中心，获取socket的IP地址。其代码：
                                 */
                                Socket socket = new Socket();
                                try {
                                    SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                    socket.connect(addr, 1000);
                                    //获取服务所在主机地址
                                    hostToBind = socket.getLocalAddress().getHostAddress();
                                    break;
                                } finally {
                                    try {
                                        socket.close();
                                    } catch (Throwable e) {
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    if (isInvalidLocalHost(hostToBind)) {
                        // 如果到这里获取的ip还是为空或者非法，则尝试从本地网卡查找第一个有效的IP，如果没有获取到，则赋值为127.0.0.1
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        map.put(Constants.BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        // 尝试从系统属性中获取注册中心ip配置
        String hostToRegistry = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (hostToRegistry == null || hostToRegistry.length() == 0) {
            // bind ip is used as registry ip by default
            // 没有从系统环境中获取到则赋值为绑定的ip
            hostToRegistry = hostToBind;
        }

        map.put(Constants.ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }

    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        // 从系统环境中解析绑定的端口
        String port = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_BIND);
        // 将端口转换成int类型并判断是否合法（在0-65535范围之内）
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        // 如果没有找到绑定端口的配置，继续寻找
        if (portToBind == null) {
            // 从protocol配置中寻找
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                // 从provider配置中寻找
                portToBind = provider.getPort();
            }
            // 默认protocol配置中寻找
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind == null || portToBind <= 0) {
                // 获取随机端口，从缓存中获取，缓存中没有会返回Integer.MIN
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    // 获取可用端口
                    portToBind = getAvailablePort(defaultPort);
                    // 放入缓存
                    putRandomPort(name, portToBind);
                }
                logger.warn("Use random available port(" + portToBind + ") for protocol " + name);
            }
        }

        // save bind port, used as url's key later
        map.put(Constants.BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        // 注册中心端口，默认情况下不用作绑定端口
        String portToRegistryStr = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (port == null || port.length() == 0) {
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    /**
     * 如果dubbo:service标签也就是ServiceBean的provider属性为空，调用appendProperties方法，填充默认属性，其具体加载顺序：
     * 1）从系统属性加载对应参数值，参数键：dubbo.provider.属性名，System.getProperty。
     * 2）加载属性配置文件的值。属性配置文件，可通过系统属性：dubbo.properties.file，如果该值未配置，则默认取dubbo.properties属性配置文件。
     */
    private void checkDefault() {
        if (provider == null) {
            provider = new ProviderConfig();
        }
        appendProperties(provider);
    }

    private void checkProtocol() {
        if ((protocols == null || protocols.isEmpty()) && provider != null) {
            setProtocols(provider.getProtocols());
        }
        // backward compatibility

        if (protocols == null || protocols.isEmpty()) {
            setProtocol(new ProtocolConfig());
        }
        for (ProtocolConfig protocolConfig : protocols) {
            if (StringUtils.isEmpty(protocolConfig.getName())) {
                protocolConfig.setName(Constants.DUBBO_VERSION_KEY);
            }
            // 追加配置
            appendProperties(protocolConfig);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
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
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName(Constants.PATH_KEY, path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        this.provider = provider;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
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
