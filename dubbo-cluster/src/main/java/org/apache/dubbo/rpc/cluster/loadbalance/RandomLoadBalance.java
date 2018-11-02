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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * random load balance.
 * 可以通过< dubbo:service loadbalance=”random” …/>或< dubbo:provider loadbalance = “random” …/>
 * 负载均衡算法：随机，如果weight（权重越大，机会越高）
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size(); // Number of invokers
        int totalWeight = 0; // The sum of weights
        // 标识每一个invoker是否有着相同的权重
        boolean sameWeight = true; // Every invoker has the same weight?
        for (int i = 0; i < length; i++) {
            // 遍历获取每一个invoker的权重，权重可以进行配置
            int weight = getWeight(invokers.get(i), invocation);
            totalWeight += weight; // Sum
            // 对比当前invoker的权重和上一个invoker的权重
            // 如果发现有权重不相等的，说明不是每一个invoker的权重都一样
            if (sameWeight && i > 0 && weight != getWeight(invokers.get(i - 1), invocation)) {
                sameWeight = false;
            }
        }
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            // 基于总权重获取随机数
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < length; i++) {
                // 遍历invoker，用获取的随机数减去当前invoker的权重，如果小于0，则选择当前invoker
                // 如果当前invoker的权重较大，这样差值就更容易小于0，这样选中的几率就越大
                // 这个随机算法稍微有点问题应该用平均权重来随机啊，而不是总权重
                offset -= getWeight(invokers.get(i), invocation);
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果所有的invoker具有相同的权重值或总权重等于0，则随机返回一个invoker
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

}
