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
package org.apache.dubbo.remoting.exchange.codec;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.StreamUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBufferInputStream;
import org.apache.dubbo.remoting.buffer.ChannelBufferOutputStream;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.telnet.codec.TelnetCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec.
 *
 * 交互层编码器，其他具体协议的基础类，可以看出是业务协议的模板类
 * 包含Dubbo协议的模板实现类
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    /**
     * 协议头部长度，共16个字节
     */
    protected static final int HEADER_LENGTH = 16;
    // magic header.
    /**
     * 魔数，固定为0xdabb，2个字节。
     */
    protected static final short MAGIC = (short) 0xdabb;
    /**
     * 魔数的高8位。
     */
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    /**
     * 魔数的低8位。
     */
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    // message flag.
    /**
     * 消息请求类型为消息请求。
     */
    protected static final byte FLAG_REQUEST = (byte) 0x80;
    /**
     * 消息请求类型为心跳。
     */
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    /**
     * 消息请求类型为事件。
     */
    protected static final byte FLAG_EVENT = (byte) 0x20;
    /**
     * serialization掩码。
     */
    protected static final int SERIALIZATION_MASK = 0x1f;
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    /**
     * 编码实现原理
     * @param channel  Dubbo网络通道的抽象,底层实现有NettyChannel、MinaChannel
     * @param buffer buffer抽象类，屏蔽netty,mina等底层实现差别
     * @param msg 请求对象、响应对象或其他消息对象。
     * @throws IOException
     */
    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {

        if (msg instanceof Request) {
            /**
             * 如果msg是Request，则按照请求对象协议编码。
             */
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            /**
             * 如果是响应对象，则按照响应协议编码。
             */
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            /**
             * 如果是业务类对象（请求、响应），则使用父类默认的编码方式。
             */
            super.encode(channel, buffer, msg);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {


        int readable = buffer.readableBytes();
        /**
         * 创建一个byte数组，其长度为 头部长度和可读字节数取最小值。
         */
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        /**
         * 读取指定字节到header中。
         */
        buffer.readBytes(header);
        /**
         * 调用decode方法尝试解码。
         */
        return decode(channel, buffer, readable, header);
    }

    /**
     *
     * @param channel 网络通道
     * @param buffer 通道读缓存区
     * @param readable 可读字节数。
     * @param header 已读字节数，（尝试读取一个完整头部）
     * @return
     * @throws IOException
     */
    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        /**
         * 检查魔数，判断是否是dubbo协议，如果不是dubbo协议，则调用父类的解码方法，例如telnet协议。
         * 如果至少读取到一个字节，如果第一个字节与魔数的高位字节不相等或至少读取了两个字节，并且第二个字节与
         * 魔数的地位字节不相等，则认为不是   dubbo协议，则调用父类的解码方法,如果是其他协议的化，将剩余的可
         * 读字节从通道中读出，提交其父类解码。
         */
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            if (header.length < readable) {
                header = Bytes.copyOf(header, readable);
                buffer.readBytes(header, length, readable - length);
            }
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            return super.decode(channel, buffer, readable, header);
        }
        // check length.
        /**
         * 如果是dubbo协议，判断可读字节的长度是否大于协议头部的长度，如果可读字节小于头部字节，
         * 则跳过本次读事件处理，待读缓存区中更多的数据到达。
         */
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length.
        /**
         * 如果读取到一个完整的协议头，然后读取消息体长度，如果当前可读自己小于消息体+header的长
         * 度，返回NEED_MORE_INPUT,表示放弃本次解码，待更多数据到达缓冲区时再解码。
         */
        int len = Bytes.bytes2int(header, 12);
        checkPayload(channel, len);

        int tt = len + HEADER_LENGTH;
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.
        /**
         * 创建一个ChannelBufferInputStream，并限制最多只读取len长度的字节。
         */
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            /**
             * 调用decodeBody方法解码协议体。
             */
            return decodeBody(channel, is, header);
        } finally {
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    /**
                     * 如果本次并未读取len个字节，则跳过这些字节，保证下一个包从正确的位置开始处理。
                     * 这个其实就是典型的网络编程（自定义协议）的解码实现。由于本文只关注Dubbo协议的
                     * 解码，故decodeBody方法的实现，请看DubboCodec#decodeBody。
                     */
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }


    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {

        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        Serialization s = CodecSupport.getSerialization(channel.getUrl(), proto);
        ObjectInput in = s.deserialize(channel.getUrl(), is);
        // get request id.
        long id = Bytes.bytes2long(header, 4);


        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.

            Response res = new Response(id);

            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            if (status == Response.OK) {
                try {
                    Object data;

                    if (res.isHeartbeat()) {
                        data = decodeHeartbeatData(channel, in);
                    } else if (res.isEvent()) {
                        data = decodeEventData(channel, in);
                    } else {
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    res.setResult(data);
                } catch (Throwable t) {
                    res.setStatus(Response.CLIENT_ERROR);
                    res.setErrorMessage(StringUtils.toString(t));
                }
            } else {
                res.setErrorMessage(in.readUTF());
            }
            return res;
        } else {
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(Request.HEARTBEAT_EVENT);
            }
            try {
                Object data;
                if (req.isHeartbeat()) {
                    data = decodeHeartbeatData(channel, in);
                } else if (req.isEvent()) {
                    data = decodeEventData(channel, in);
                } else {
                    data = decodeRequestData(channel, in);
                }
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null)
            return null;
        Request req = future.getRequest();
        if (req == null)
            return null;
        return req.getData();
    }

    /**
     *
     * @param channel
     * @param buffer
     * @param req
     * @throws IOException
     */
    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        /**
         * 初始化协议头，同时填充部分字段。header[0]、header[1]、header[2]、header[4-11]，注意,header[3]未填充。
         */
        /**
         * 获取通道的序列化实现类。
         */
        Serialization serialization = getSerialization(channel);
        // header.
        /**
         * 构建请求头部,header数组，长度为16个字节。
         */
        byte[] header = new byte[HEADER_LENGTH];
        // set magic number.
        /**
         * 首先填充头部的前两个字节，协议的魔数。header[0] = 魔数的高8个字节，header[1] = 魔数的低8个字节。
         */
        Bytes.short2bytes(MAGIC, header);
        // set request and serialization flag.
        /**
         * 头部的第3个字节存储的是消息请求标识与序列化器类别，那这8位是如何存储的呢？
         *
         *
         * 首先看一下消息请求标志的定义：
               protected static final byte FLAG_REQUEST = (byte) 0x80;      // 其二进制为 1000 0000
               protected static final byte FLAG_TWOWAY = (byte) 0x40;       // 其二进制为 0100 0000
               protected static final byte FLAG_EVENT = (byte) 0x20;           // 其二进制为 0010 0000
               protected static final int SERIALIZATION_MASK = 0x1f;          // 其序列化的掩码，为什么是这样的呢？
               serialization.getContentTypeId() 返回的类型如下：
               CompactedJavaSerialization:4     二进制为0000 0010
               FastJsonSerialization:6     二进制为0000 0110
               FstSerialization:9     二进制为0000 1001
               Hessian2Serialization:2     二进制为0000  0010
               JavaSerialization:3     二进制为0000 0011
               KryoSerialization:8    二进制为0000 1000
               NativeJavaSerialization：7     二进制为0000 0111
               结合代码header[2] =  (byte) (FLAG_REQUEST | serialization.getContentTypeId())
               可以得出一个结论,header[2]为8字节标志位，前4位，表示消息请求类型，依次为：请求、twoway、event，保留位。后4为：
               序列化的类型，也就是说dubbo协议只支持16中序列化协议。
         */
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());


        if (req.isTwoWay()) header[2] |= FLAG_TWOWAY;
        if (req.isEvent()) header[2] |= FLAG_EVENT;

        // set request id.
        /**
         * head[4]- head[11] 共8个字节为请求ID。Dubbo传输使用大端字节序列，也就说在接受端，首先读到的字节是高位字节。
         */
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        /**
         * 编码请求体（body），协议的设计，一般是基于 请求头部+请求体构成。
         */
        int savedWriteIndex = buffer.writerIndex();
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
        /**
         * 对buffer做一个简单封装，返回ChannelBufferOutputStream实例。
         */
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        /**
         * 根据序列化器，将通道的URL进行序列化，变存入buffer中。
         */
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);

        /**
         * 根据请求类型，事件或请求对Request.getData()请求体进行编码，encodeEventData、encodeRequestData
         * 不同的编码器会重写该方法。
         */
        if (req.isEvent()) {
            encodeEventData(channel, out, req.getData());
        } else {
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();
        /**
         * 最后得到bos的总长度，该长度等于 (header+body)的总长度，也就是一个完整请求包的长度。
         */
        int len = bos.writtenBytes();
        checkPayload(channel, len);
        /**
         * 将包总长度写入到header的header[12-15]中。
         */
        Bytes.int2bytes(len, header, 12);

        // write
        buffer.writerIndex(savedWriteIndex);
        buffer.writeBytes(header); // write header.
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            Serialization serialization = getSerialization(channel);
            // header.
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.
            Bytes.short2bytes(MAGIC, header);
            // set request and serialization flag.
            header[2] = serialization.getContentTypeId();
            if (res.isHeartbeat()) header[2] |= FLAG_EVENT;
            // set response status.
            byte status = res.getStatus();
            header[3] = status;
            // set request id.
            Bytes.long2bytes(res.getId(), header, 4);

            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            // encode response data or error message.
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    encodeHeartbeatData(channel, out, res.getResult());
                } else {
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            } else out.writeUTF(res.getErrorMessage());
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            int len = bos.writtenBytes();
            checkPayload(channel, len);
            Bytes.int2bytes(len, header, 12);
            // write
            buffer.writerIndex(savedWriteIndex);
            buffer.writeBytes(header); // write header.
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    @Deprecated
    protected Object decodeHeartbeatData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }


}
