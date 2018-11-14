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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ==============================

    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();

    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();

    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<String, Object>();
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    private volatile Class<?> cachedAdaptiveClass = null;
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    /**
     * 这里我们看到 getExtensionLoader 会是一个死循环，因为会不断调用；但是实际上在
     * getAdaptiveExtension 中会直接返回被 Adaptive 注解的类，因此避免了死循环；？？？？
     *
     * 在 getExtensionClasses 函数中，在读取文件加载类的过程过程中，会判断该类是否带
     * 有 Adaptive 注解，如果是，则直接赋值
     * ========================================https://www.cnblogs.com/heart-king/p/5632524.html(spi很重要)
     * 1）如果有打在接口方法上，调ExtensionLoader.getAdaptiveExtension()获取设配类，会先通过前面的过程生成java的源代码，

     * 在通过编译器编译成class加载。但是Compiler的实现策略选择也是通过ExtensionLoader.getAdaptiveExtension()，

     * 如果也通过编译器编译成class文件那岂不是要死循环下去了吗？

     * ExtensionLoader.getAdaptiveExtension()，对于有实现类上去打了注解@Adaptive的dubbo spi扩展机制，它获取设配类
     * 不在通过前面过程生成设配类java源代码，而是在读取扩展文件的时候遇到实现类打了注解@Adaptive就把这个类作为设配类缓存
     * 在ExtensionLoader中，调用是直接返回
     *
     * 静态方法的调用不会导致类的初始化
     * 不同类型会创建一个ExtensionLoader对象
     * ExtensionFactory.class也是标注了@SPI注解的
     *
     * @param type
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        objectFactory = (
                type == ExtensionFactory.class ?
                        null :
                        ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension()
        );
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    //Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();
    /**
     * 1.
     * 每个定义的spi的接口都会构建一个ExtensionLoader实例
     * 静态的对象ConcurrentMap<Class<?>,ExtensionLoader<?>> EXTENSION_LOADERS 这个map对象中
     * 获取ExtensionLoader--->根据类型先从缓存中获取--->获取不到创建
     */
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        // type必须是接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        // 必须注解@SPI
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }
        //缓存中获取ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS
        //是所有自适应类型和对应的ExtensionLoader的map用于缓存
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            // 缓存中没有则新建ExtensionLoader<?>对象放入缓存------>第一次进来（见私有构造器）
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            //先放进去，再取出来
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    private static ClassLoader findClassLoader() {
        return ClassHelper.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url  服务提供者或服务消费者url。
     * @param key   url parameter key which used to get extension point names  过滤器属性key，服务提供者固定为:service.filter，服务消费者固定为reference.filter。
     * @param group group  服务提供者或服务消费者。
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        /**
         * 从url中获取配置的自定义filter。
         */
        String value = url.getParameter(key);
        /**
         * 如果value不为空，则将字符串调用split转换为数组，然后调用getActivateExtension方法，获取符合条件的过滤器。
         */
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        /**
         * 如果配置的service.filter或referecnce.filter包含了-default，表示禁用系统默认提供的一系列过滤器。
         */
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            /**
             * 如果不禁用系统默认过滤器链，则首先加载所有默认过滤器。
             */
            getExtensionClasses();
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;

                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                /**
                 * 根据group刷选出适配的过滤器。
                 */
                if (isMatchGroup(group, activateGroup)) {
                    T ext = getExtension(name);
                    /**
                     * 也可以对单个filter进行禁用，其方法是-过滤器名称的方式。例如如想禁用AccessLogFilter，则可以通过-accesslog方式禁用。
                     * -key,key为/dubbo-rpc-api/src/main/resources/META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Filter中定义的key。
                     * --------------------------------------------------------------------------------------------------
                     * 判断过滤器是否激活，其逻辑是如果Filter上的@Activate注解value值不为空，则需要判断url中是否包含键为value的属性对，
                     * 存在则启用，不存在则不启用。
                     */
                    if (!names.contains(name) && !names.contains(Constants.REMOVE_VALUE_PREFIX + name) && isActive(activateValue, url)) {
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<T>();
        /**
         * 加载用户自定义的Filter，也即是service.filter或reference.filter指定的过滤器。
         *
         * ====>如果需要自定过滤器，需要在自定的工程中META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Filter文件中注册。
         */
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (group == null || group.length() == 0) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * a. ====>上面1.2.3......流程是创建适配类，这里是创建实际的类
     * get（）by name
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     *
     *  返回指定名字的扩展。如果指定名字的扩展不存在，则抛异常 {@link IllegalStateException}
     *
     *  @Adaptive 注解：该注解打在接口方法上；调 ExtensionLoader.getAdaptiveExtension()获
     * 取设配类，会先通过前面的过程生成 java 的源代码，在通过编译器编译成 class 加载。
     * 但是 Compiler 的实现策略选择也是通过 ExtensionLoader.getAdaptiveExtension()，如果也
     * 通过编译器编译成 class 文件那岂不是要死循环下去了吗？
     * 此时分析 ExtensionLoader.getAdaptiveExtension()函数，对于有实现类上去打了注解
     * @Adaptive 的 dubbo spi 扩展机制，它获取设配类不在通过前面过程生成设配类 java 源代码，
     * 而是在读取扩展文件的时候遇到实现类打了注解@Adaptive 就把这个类作为设配类缓存在
     * ExtensionLoader 中，调用是直接返回
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");

        //注意这里的默认行为
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        //name可能有多个
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    /**
                     * b.
                     */
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        try {
            this.getExtensionClass(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * 2.
     * 获取AdaptiveExtension
     * 获得spi扩展点的实例？？--->先从缓存中获取--->获取不到创建自适应扩展点
     *
     * ExtensionLoader<T> 中有一个扩扩展对象的缓存Holder<Object> cachedAdaptiveInstance
     */
    public T getAdaptiveExtension() {
        // 首先尝试从缓存中获取(当前ExtensionLoader会缓存)
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    // 二次检查
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            /* 缓存中没有则创建自适应扩展点 */
                            instance = createAdaptiveExtension();
                            //缓存
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            // 记录创建自适应扩展点错误信息
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    /**
     *b.
     */
    private T createExtension(String name) {
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            /**
             * Dubbo是如何自动的给扩展点wrap上装饰对象的呢？

             * 1）在ExtensionLoader.loadFile加载扩展点配置文件的时候
             *
             * 对扩展点类有接口类型为参数的构造器就是包转对象，缓存到集合中去
             * 2）在调ExtensionLoader的createExtension(name)根据扩展点key创建扩展的时候， 先实例化扩展点的实现，
             * 在判断时候有此扩展时候有包装类缓存，有的话利用包转器增强这个扩展点实现的功能。如下图是实现流程
             */
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            injectExtension(instance);
            /**
             * 下面這一段很重要
             * 此时可以发现这里对 instance 加了装饰类；对于 Protocol 来说加了两个装饰类
             * ProtocolFilterWrapper 和 ProtocolListenerWrapper；
             * 也就/injectExtension 实例化包装类，并注入接口的适配器， 注意这个地方返回的是最后一
             * 个包装类
             * 在生成 Protocol 的 invoker 时，实际上使用了
             * 装饰模式，第一个是 filter，第二个是 listener；
             */
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 而这里 injectExtension 类，则是为生成的 instance 注入变量；
     * 其目标是搜索所有 set 开头，同时只有一个入参的函数，执行该函数，对变量进行注入；
     *
     * 内部实现了个简单的ioc机制来实现对扩展实现所依赖的参数的注入，dubbo对扩展实现中公有的set方法且入参个数为一个的方法，
     * 尝试从对象工厂ObjectFactory获取值注入到扩展点实现中去。
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {
                for (Method method : instance.getClass().getMethods()) {
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {
                        Class<?> pt = method.getParameterTypes()[0];
                        try {
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }

    /**
     * 5
     * @return
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // 同样的首先尝试从缓存（当前类型）中获取
        //Holder<Map<String, Class<?>>> cachedClasses===>该类型的所有的自适应点实现类的缓存===>如果加载过就不用加载了===>也就是只用加载一次
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                // double check，二次检查缓存===>如果还没有加载从文件中加载
                classes = cachedClasses.get();
                if (classes == null) {
                     /* 加载扩展点class =====>从文件中获取 */
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * 6.
     * @return
     */
    // synchronized in getExtensionClasses
    private Map<String, Class<?>> loadExtensionClasses() {
        // 获取@SPI注解信息
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            // 获取默认值
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                // 默认扩展点只能有一个
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                // 如果默认扩展点名称唯一，缓存默认扩展点名称
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }

        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        /* 加载META-INF/dubbo/internal/、META-INF/dubbo/、META-INF/services/ 三个目录下的文件 */
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        return extensionClasses;
    }

    /**
     * 7。当前类型的
     * @param extensionClasses
     * @param dir
     * @param type
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        // 目录+接口名
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            // 加载文件资源
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * 8.
     * @param extensionClasses
     * @param classLoader
     * @param resourceURL
     */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));
            try {
                String line;
                // 读取一行
                while ((line = reader.readLine()) != null) {
                    // #号代表注释，要忽略掉
                    final int ci = line.indexOf('#');
                    if (ci >= 0) line = line.substring(0, ci);
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            //“=”为第一个字符会报错
                            int i = line.indexOf('=');
                            if (i > 0) {
                                // =号前为key
                                name = line.substring(0, i).trim();
                                // =号后为value
                                line = line.substring(i + 1).trim();
                            }
                            //Class.forName(line, true, classLoader)获取值line的类型Class<?>，这里name可以为空
                            if (line.length() > 0) {
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            //将异常保存进入下一个循环
                            exceptions.put(line, e);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }


    /**
     * 9.
     * 分析：这里实际上是如果该类带有 Adaptive 注解，则认为是 cachedAdaptiveClass；若该
     * 类没有 Adaptive 注解，则判断该类是否带有参数是 type 类型的构造函数，若有，则认为是
     * wrapper 类
     * @param extensionClasses
     * @param resourceURL
     * @param clazz
     * @param name
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        // 如果配置的class不是给定接口的实现类，抛出异常,外面会捕获异常，然后加载下一行
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error when load extension class(interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + "is not subtype of interface.");
        }

        /**
         * 在 getExtensionClasses 函数中，在读取文件加载类的过程过程中，会判断该类是否带
         * 有 Adaptive 注解，如果是，则直接赋值
         * 这里同时可以确认，对于一个 spi 接口，有且只有一个类带有 adaptive 注解，否则会出错；
         */
        // 注解@Adaptive的class为自适应扩展点

        /**
         *
         * 这一句很重要===>防止无线循环
         * 判断类实现（如：DubboProtocol）上有么有打上@Adaptive注解，如果打上了注解，将此类作为Protocol协议
         * 的设配类缓存起来，读取下一行；否则适配类通过javasisit修改字节码生成，关于设配类功能作用后续介绍
         */
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            if (cachedAdaptiveClass == null) {
                // 缓存自适应扩展点类型
                cachedAdaptiveClass = clazz;
            } else if (!cachedAdaptiveClass.equals(clazz)) {
                // 给定接口的自适应扩展点只能有一个
                throw new IllegalStateException("More than 1 adaptive class found: "
                        + cachedAdaptiveClass.getClass().getName()
                        + ", " + clazz.getClass().getName());
            }


            /*private boolean isWrapperClass(Class<?> clazz) {
                try {
                    // 尝试获取以给定接口为参数的构造方法
                    clazz.getConstructor(type);
                    return true;
                } catch (NoSuchMethodException e) {
                    return false;
                }
            }*/
        } else if (isWrapperClass(clazz)) {
            /**
             * 如果类实现没有打上@Adaptive， 判断实现类是否存在入参为接口的构造器（就是DubbboProtocol类是否还有入参为Protocol的构造器），
             * 有的话作为包装类缓存到此ExtensionLoader的Set<Class<?>>集合中，这个其实是个装饰模式
             */
            Set<Class<?>> wrappers = cachedWrapperClasses;
            if (wrappers == null) {
                cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                wrappers = cachedWrapperClasses;
            }
            // 添加为wrapper包装类
            wrappers.add(clazz);
        } else {
            // 没有以给定接口为参数的构造方法则尝试获取默认无参构造方法
            /**
             * 如果既不是设配对象也不是wrapped的对象，那就是扩展点的具体实现对象　　
             * 查找实现类上有没有打上@Activate注解，有缓存到变量cachedActivates
             * 的map中将实现类缓存到cachedClasses中，以便于使用时获取
             */
            clazz.getConstructor();
            if (name == null || name.length() == 0) {
                /* 如果配置的key为空，则尝试获取class注解中配置的name 也即在配置中没有指定相应的key值，可以在类上面用@Extension("NAME1")指定*/
                name = findAnnotationName(clazz);

                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            //分割name
            String[] names = NAME_SEPARATOR.split(name);
            if (names != null && names.length > 0) {
                // 注解@Activate的class为自动激活扩展点====>注意和@Adaptive的区别
                Activate activate = clazz.getAnnotation(Activate.class);
                if (activate != null) {
                    // 将第一个名字name和@Activate注解信息添加到映射缓存
                    cachedActivates.put(names[0], activate);
                } else {
                    // support com.alibaba.dubbo.common.extension.Activate
                    // 兼容旧版本
                    com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
                    if (oldActivate != null) {
                        //缓存对应name和注解
                        cachedActivates.put(names[0], oldActivate);
                    }
                }
                for (String n : names) {
                    if (!cachedNames.containsKey(clazz)) {
                        // 添加配置的扩展点class和name的映射缓存===>一个class对应多个name
                        cachedNames.put(clazz, n);
                    }
                    Class<?> c = extensionClasses.get(n);
                    if (c == null) {
                        // 添加到参数给定的集合中
                        extensionClasses.put(n, clazz);
                    } else if (c != clazz) {
                        throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                    }
                }
            }
        }
    }

    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        // 获取class上的@Extension注解信息
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                // 这里的截取方式与上文中介绍的方式相同
                // 如果注解中无法获取到name则判断配置的class的名称是否大
                // 于给定接口的名称并且以给定接口名称为结尾，例如XxxProtocol类和Protocol接口就会满足这个条件
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            // 同样转换为小写返回
            return name.toLowerCase();
        }
        //注解不为null则直接用注解的value作为name
        return extension.value();
    }

    @SuppressWarnings("unchecked")
    /**
     * 3.
     * 创建spi的自适应实例
     */
    private T createAdaptiveExtension() {
        try {
             /**
                1.获取自适应点类型
                2.获取自适应扩展点实例
                3.并进行注入
              */
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 4.
     * 而 cachedAdaptiveInstance 类则是若有 cachedAdaptiveClass 对象，则直接返回，否则通过生成类文件，然后 complier 出来
     * 只有标注了@Adaptive 注释的函数会在运行时动态的决定扩展点实现？？？？（这一句好像有问题）
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {
         //获取扩展点class（在配置文件中加载这些类） ===>如果有@Adaptive注解存在缓存中 Class<?>  cachedAdaptiveClass（只能有一个）  ===>尝试从缓存中获取
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            // 如果缓存的自适应扩展点class不为null，直接返回===>说明有且仅有一个实现类打了@Adaptive, 实例化这个对象返回==>相当于默认实现?
            return cachedAdaptiveClass;
        }
         // 否者会创建自适应扩展点class并返回 如果没有有@Adaptive注解创建智适应点===>创建设配类字节码
        /**
         * 10.
         */
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 11.
     * 为什么要创建设配类，一个接口多种实现，SPI机制也是如此，这是策略模式，但是我们在代码执行过程中选择哪种具体的策略呢。
     * Dubbo采用统一数据模式com.alibaba.dubbo.common.URL(它是dubbo定义的数据模型不是jdk的类)，它会穿插于系统的整个执
     * 行过程，URL中定义的协议类型字段protocol，会根据具体业务设置不同的协议。url.getProtocol()值可以是dubbo也是可以
     * webservice， 可以是zookeeper也可以是redis。
     * 设配类的作用是根据url.getProtocol()的值extName，去ExtensionLoader. getExtension( extName)选取具体的扩展点实现。
     * 所以能够利用javasist生成设配类的条件
     * 1）接口方法中必须至少有一个方法打上了@Adaptive注解
     * 2）打上了@Adaptive注解的方法参数必须有URL类型参数或者有参数中存在getURL()方法
     * @return
     */

    private Class<?> createAdaptiveExtensionClass() {
        // 拼接自适应扩展点class的字符串
        String code = createAdaptiveExtensionClassCode();
        ClassLoader classLoader = findClassLoader();
        // 获取编译器=====>编译器自适应点=====>有@Adaptive注解
        // 得到编译器，防止无限循环，所以需要org.apache.dubbo.common.compiler.Compiler.class有一个实现类有@Adaptive注解===>这样能保证直接缓存@Adaptive注解的compiler而不用去无限循环编译
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        // 编译字符串为class
        return compiler.compile(code, classLoader);
    }
    //===================
   /* package <扩展点接口所在包>;

    public class <扩展点接口名>$Adpative implements <扩展点接口> {
        public <有@Adaptive注解的接口方法>(<方法参数>) {
            if(是否有URL类型方法参数?) 使用该URL参数
            else if(是否有方法类型上有URL属性) 使用该URL属性
        # <else 在加载扩展点生成自适应扩展点类时抛异常，即加载扩展点失败！>

            if(获取的URL == null) {
                throw new IllegalArgumentException("url == null");
            }

            根据@Adaptive注解上声明的Key的顺序，从URL获致Value，作为实际扩展点名。
            如URL没有Value，则使用缺省扩展点实现。如没有扩展点， throw new IllegalStateException("Fail to get extension");

            在扩展点实现调用该方法，并返回结果。
        }

        public <有@Adaptive注解的接口方法>(<方法参数>) {
            throw new UnsupportedOperationException("is not adaptive method!");
        }
    }*/
//====================================
            /**package com.alibaba.dubbo.demo.rayhong.test;

               import com.alibaba.dubbo.common.extension.ExtensionLoader;

               public class Protocol$Adpative implements com.alibaba.dubbo.rpc.Protocol {

                  // 没有打上@Adaptive的方法如果被调到抛异常
                  public void destroy() {
                         throw new UnsupportedOperationException(
                                         "method public abstract void com.alibaba.dubbo.rpc.Protocol.destroy() "
                                         + "of interface com.alibaba.dubbo.rpc.Protocol is not adaptive method!");

                    }

                 // 没有打上@Adaptive的方法如果被调到抛异常
                 public int getDefaultPort() {
                         throw new UnsupportedOperationException(
                                         "method public abstractint com.alibaba.dubbo.rpc.Protocol.getDefaultPort() "
                                         + "of interfacecom.alibaba.dubbo.rpc.Protocol is not adaptive method!");
                     }

                // 接口中export方法打上@Adaptive注册
                 public com.alibaba.dubbo.rpc.Exporter export(com.alibaba.dubbo.rpc.Invoker arg0) {
                         if (arg0 == null)
                                 throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invokerargument == null");

                        // 参数类中要有URL属性
                        if (arg0.getUrl() == null)
                               throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invokerargument getUrl() == null");

                         // 从入参获取统一数据模型URL
                         com.alibaba.dubbo.common.URL url = arg0.getUrl();
                        String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());

                        // 从统一数据模型URL获取协议，协议名就是spi扩展点实现类的key
                         if (extName == null)
                                 throw new IllegalStateException("Fail to getextension(com.alibaba.dubbo.rpc.Protocol) "
                                            + "name from url(" + url.toString() + ") usekeys([protocol])");

                         //===========================================
                         //这里就是getExtension(extName)，一切就串联了起来
                         //===========================================

                         // 利用dubbo服务查找机制根据名称找到具体的扩展点实现==============>**就是为了实现这个功能**
                         //=====>即通过url中的字符串来路由(因为在dubbo中都是url传递信息)，有点动态代理的意思，这样可以实现动态配置和无侵入编程
                         com.alibaba.dubbo.rpc.Protocol extension = (com.alibaba.dubbo.rpc.Protocol)
                                         ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.Protocol.class).getExtension(extName);
                        // 调具体扩展点的方法
                        return extension.export(arg0);
                     }

                 // 接口中refer方法打上@Adaptive注册
                 public com.alibaba.dubbo.rpc.Invoker refer(java.lang.Class arg0, com.alibaba.dubbo.common.URL arg1) {
                         // 统一数据模型URL不能为空
                         if (arg1 == null)
                                 throw new IllegalArgumentException("url == null");
                        com.alibaba.dubbo.common.URL url = arg1;
                         // 从统一数据模型URL获取协议，协议名就是spi扩展点实现类的key
                         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
                         if (extName == null)
                                 throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.Protocol) "
                                             + "name from url(" + url.toString() + ") use keys([protocol])");
                         // 利用dubbo服务查找机制根据名称找到具体的扩展点实现
                         com.alibaba.dubbo.rpc.Protocol extension = (com.alibaba.dubbo.rpc.Protocol)
                                         ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.Protocol.class).getExtension(extName);
                         // 调具体扩展点的方法
                         return extension.refer(arg0, arg1);

                    }

             }
     */
    //================================================

    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuilder = new StringBuilder();
        Method[] methods = type.getMethods();
        boolean hasAdaptiveAnnotation = false;
        for (Method m : methods) {
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveAnnotation)
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");

        codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
        codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
        codeBuilder.append("\npublic class ").append(type.getSimpleName()).append("$Adaptive").append(" implements ").append(type.getCanonicalName()).append(" {");

        codeBuilder.append("\nprivate static final org.apache.dubbo.common.logger.Logger logger = org.apache.dubbo.common.logger.LoggerFactory.getLogger(ExtensionLoader.class);");
        codeBuilder.append("\nprivate java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);\n");

        for (Method method : methods) {
            Class<?> rt = method.getReturnType();
            Class<?>[] pts = method.getParameterTypes();
            Class<?>[] ets = method.getExceptionTypes();

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            StringBuilder code = new StringBuilder(512);
            if (adaptiveAnnotation == null) {
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
                int urlTypeIndex = -1;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // found parameter in URL type
                if (urlTypeIndex != -1) {
                    // Null Point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);

                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // did not find parameter in URL type
                else {
                    String attribMethod = null;

                    // find URL getter method
                    LBL_PTS:
                    for (int i = 0; i < pts.length; ++i) {
                        Method[] ms = pts[i].getMethods();
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                String[] value = adaptiveAnnotation.value();
                // value is not set, use the value generated from class name as the key
                if (value.length == 0) {
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    for (int i = 0; i < charArray.length; i++) {
                        if (Character.isUpperCase(charArray[i])) {
                            if (i != 0) {
                                sb.append(".");
                            }
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()};
                }

                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].getName().equals("org.apache.dubbo.rpc.Invocation")) {
                        // Null Point check
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                String defaultExtName = cachedDefaultName;
                String getNameCode = null;
                for (int i = value.length - 1; i >= 0; --i) {
                    if (i == value.length - 1) {
                        if (null != defaultExtName) {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        } else {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                                getNameCode = "url.getProtocol()";
                        }
                    } else {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                        else
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);

                code.append(String.format("\n%s extension = null;\n try {\nextension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);\n}catch(Exception e){\n",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName()));
                code.append(String.format("if (count.incrementAndGet() == 1) {\nlogger.warn(\"Failed to find extension named \" + extName + \" for type %s, will use default extension %s instead.\", e);\n}\n",
                        type.getName(), defaultExtName));
                code.append(String.format("extension = (%s)%s.getExtensionLoader(%s.class).getExtension(\"%s\");\n}",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName(), defaultExtName));

                // return statement
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
            }

            codeBuilder.append("\npublic ").append(rt.getCanonicalName()).append(" ").append(method.getName()).append("(");
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(pts[i].getCanonicalName());
                codeBuilder.append(" ");
                codeBuilder.append("arg").append(i);
            }
            codeBuilder.append(")");
            if (ets.length > 0) {
                codeBuilder.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuilder.append(", ");
                    }
                    codeBuilder.append(ets[i].getCanonicalName());
                }
            }
            codeBuilder.append(" {");
            codeBuilder.append(code.toString());
            codeBuilder.append("\n}");
        }
        codeBuilder.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuilder.toString());
        }
        return codeBuilder.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
