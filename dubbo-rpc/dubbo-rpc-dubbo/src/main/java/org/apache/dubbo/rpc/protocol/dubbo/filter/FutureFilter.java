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
package org.apache.dubbo.rpc.protocol.dubbo.filter;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.PostProcessFilter;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.StaticContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * EventFilter
 * 引入特定的过滤器FutureFilter来处理异步调用相关的逻辑，group=CONSUMER说明该过滤器属于消费端过滤器。
 */
@Activate(group = Constants.CONSUMER)
public class FutureFilter implements PostProcessFilter {

    protected static final Logger logger = LoggerFactory.getLogger(FutureFilter.class);

    @Override
    public Result invoke(final Invoker<?> invoker, final Invocation invocation) throws RpcException {
        /**
         * 同步调用oninvoke事件,执行invoke方法之前的事件。
         */
        fireInvokeCallback(invoker, invocation);
        // need to configure if there's return value before the invocation in order to help invoker to judge if it's
        // necessary to return future.
        /**
         * invoker.invoke(invocation)
         * 继续沿着调用链调用,最终会到具体的协议Invoker，例如DubboInvoker，发生具体的服务调用，跟踪一下同步、异步调用的实现细节。
         *
         * 以DubboInvoke为例
         */
        return postProcessResult(invoker.invoke(invocation), invoker, invocation);
    }

    @Override
    public Result postProcessResult(Result result, Invoker<?> invoker, Invocation invocation) {

        if (result instanceof AsyncRpcResult) {
            /**
             * 如果调用方式是异步模式，则异步调用onreturn或onthrow事件。
             */
            AsyncRpcResult asyncResult = (AsyncRpcResult) result;
            asyncResult.thenApplyWithContext(r -> {
                asyncCallback(invoker, invocation, r);
                return r;
            });
            return asyncResult;
        } else {
            /**
             * 如果调用方式是同步模式，则同步调用onreturn或onthrow事件。
             */
            syncCallback(invoker, invocation, result);
            return result;
        }
    }

    private void syncCallback(final Invoker<?> invoker, final Invocation invocation, final Result result) {
        if (result.hasException()) {
            fireThrowCallback(invoker, invocation, result.getException());
        } else {
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    private void asyncCallback(final Invoker<?> invoker, final Invocation invocation, Result result) {
        if (result.hasException()) {
            fireThrowCallback(invoker, invocation, result.getException());
        } else {
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    private void fireInvokeCallback(final Invoker<?> invoker, final Invocation invocation) {
        /**
         * StaticContext.getSystemContext()中根据key:serviceKey + “.” + method + “.” + “oninvoke.method”
         * 获取配置的oninvoke.method方法名。
         * 其中serviceKey为[group]/interface:[version]，其中group与version可能为空，忽略。
         */
        final Method onInvokeMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_INVOKE_METHOD_KEY));
        /**
         * 同样根据key:serviceKey + “.” + method + “.” + “oninvoke.instance” 从StaticContext.getSystemContext()
         * 获取oninvoke.method方法所在的实例名对象，
         * 也就是说该调用哪个对象的oninvoke.method指定的方法。这里就有一个疑问，这些数据是在什么时候存入StaticContext中的呢？
         */
        final Object onInvokeInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_INVOKE_INSTANCE_KEY));

        /**
         * 主要检测< dubbo:method oninvoke=”“/>配置的正确性，其正确的配置方式如下：“实例名.方法名”，例如：
         */
        if (onInvokeMethod == null && onInvokeInst == null) {
            return;
        }
        if (onInvokeMethod == null || onInvokeInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onInvokeMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onInvokeMethod.isAccessible()) {
            onInvokeMethod.setAccessible(true);
        }

        Object[] params = invocation.getArguments();
        try {
            /**
             * 根据发射机制，调用oninvoke中指定的实例的指定方法，注意，这里传入的参数为调用远程RPC服务的参数。
             */
            onInvokeMethod.invoke(onInvokeInst, params);

            /**
             * 如果在执行调用前方法(oninvoke)事件方法失败，则会同步调用onthrow中定义的方法（如有定义）。
             */
        } catch (InvocationTargetException e) {
            /**
             *  异步回调与同步回调的区别就是调用onreturn(fireReturnCallback)和onthrow(fireThrowCallback)
             *  调用的地方不同，如果是同步调用，也就是在完成RPC服务调用后，立即调用相关的回调方法，如果是异步调用
             *  的话，RPC服务完成后，通过Future模式异步执行。其实关于onreturn、onthrow属性的解析，执行与oninvoker
             *  属性的解析完全一样，再这里也就不重复介绍了。
             */
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    private void fireReturnCallback(final Invoker<?> invoker, final Invocation invocation, final Object result) {
        final Method onReturnMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_RETURN_METHOD_KEY));
        final Object onReturnInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_RETURN_INSTANCE_KEY));

        //not set onreturn callback
        if (onReturnMethod == null && onReturnInst == null) {
            return;
        }

        if (onReturnMethod == null || onReturnInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onReturnMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onReturnMethod.isAccessible()) {
            onReturnMethod.setAccessible(true);
        }

        Object[] args = invocation.getArguments();
        Object[] params;
        Class<?>[] rParaTypes = onReturnMethod.getParameterTypes();
        if (rParaTypes.length > 1) {
            if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                params = new Object[2];
                params[0] = result;
                params[1] = args;
            } else {
                params = new Object[args.length + 1];
                params[0] = result;
                System.arraycopy(args, 0, params, 1, args.length);
            }
        } else {
            params = new Object[]{result};
        }
        try {
            onReturnMethod.invoke(onReturnInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    private void fireThrowCallback(final Invoker<?> invoker, final Invocation invocation, final Throwable exception) {
        final Method onthrowMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_THROW_METHOD_KEY));
        final Object onthrowInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_THROW_INSTANCE_KEY));

        //onthrow callback not configured
        if (onthrowMethod == null && onthrowInst == null) {
            return;
        }
        if (onthrowMethod == null || onthrowInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onthrow callback config , but no such " + (onthrowMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onthrowMethod.isAccessible()) {
            onthrowMethod.setAccessible(true);
        }
        Class<?>[] rParaTypes = onthrowMethod.getParameterTypes();
        if (rParaTypes[0].isAssignableFrom(exception.getClass())) {
            try {
                Object[] args = invocation.getArguments();
                Object[] params;

                if (rParaTypes.length > 1) {
                    if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                        params = new Object[2];
                        params[0] = exception;
                        params[1] = args;
                    } else {
                        params = new Object[args.length + 1];
                        params[0] = exception;
                        System.arraycopy(args, 0, params, 1, args.length);
                    }
                } else {
                    params = new Object[]{exception};
                }
                onthrowMethod.invoke(onthrowInst, params);
            } catch (Throwable e) {
                logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), e);
            }
        } else {
            logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), exception);
        }
    }
}
