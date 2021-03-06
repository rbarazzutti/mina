/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.core.nio.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.MessageBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundByteHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.io.IOException;

import org.apache.mina.core.BenchmarkServer;

/**
 * A Netty 3 TCP Server.
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class Netty4TcpBenchmarkServer implements BenchmarkServer {

    private static enum State {
        WAIT_FOR_FIRST_BYTE_LENGTH, WAIT_FOR_SECOND_BYTE_LENGTH, WAIT_FOR_THIRD_BYTE_LENGTH, WAIT_FOR_FOURTH_BYTE_LENGTH, READING
    }

    private static final ByteBuf ACK = Unpooled.buffer(1);

    static {
        ACK.writeByte(0);
    }

    private static final AttributeKey<State> STATE_ATTRIBUTE = new AttributeKey<State>("state");

    private static final AttributeKey<Integer> LENGTH_ATTRIBUTE = new AttributeKey<Integer>("length");

    private class TestServerHandler extends ChannelInboundByteHandlerAdapter {
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            System.out.println("childChannelOpen");
            ctx.attr(STATE_ATTRIBUTE).set(State.WAIT_FOR_FIRST_BYTE_LENGTH);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            cause.printStackTrace();
        }

        @Override
        public void inboundBufferUpdated(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
            State state = ctx.attr(STATE_ATTRIBUTE).get();
            int length = 0;
            Attribute<Integer> lengthAttribute = ctx.attr(LENGTH_ATTRIBUTE);

            if (lengthAttribute != null) {
                length = lengthAttribute.get();
            }

            while (buffer.readableBytes() > 0) {
                switch (state) {
                case WAIT_FOR_FIRST_BYTE_LENGTH:
                    length = (buffer.readByte() & 255) << 24;
                    state = State.WAIT_FOR_SECOND_BYTE_LENGTH;
                    break;

                case WAIT_FOR_SECOND_BYTE_LENGTH:
                    length += (buffer.readByte() & 255) << 16;
                    state = State.WAIT_FOR_THIRD_BYTE_LENGTH;
                    break;

                case WAIT_FOR_THIRD_BYTE_LENGTH:
                    length += (buffer.readByte() & 255) << 8;
                    state = State.WAIT_FOR_FOURTH_BYTE_LENGTH;
                    break;

                case WAIT_FOR_FOURTH_BYTE_LENGTH:
                    length += (buffer.readByte() & 255);
                    state = State.READING;

                    if ((length == 0) && (buffer.readableBytes() == 0)) {
                        MessageBuf<Object> messageOut = ctx.nextOutboundMessageBuffer();
                        messageOut.add(ACK.slice());
                        ctx.flush();
                        state = State.WAIT_FOR_FIRST_BYTE_LENGTH;
                    }

                    break;

                case READING:
                    int remaining = buffer.readableBytes();

                    if (length > remaining) {
                        length -= remaining;
                        buffer.skipBytes(remaining);
                    } else {
                        buffer.skipBytes(length);
                        MessageBuf<Object> messageOut = ctx.nextOutboundMessageBuffer();
                        messageOut.add(ACK.slice());
                        ctx.flush();
                        state = State.WAIT_FOR_FIRST_BYTE_LENGTH;
                        length = 0;
                    }
                }
            }

            ctx.attr(STATE_ATTRIBUTE).set(state);
            ctx.attr(LENGTH_ATTRIBUTE).set(length);
        }
    }

    /**
     * {@inheritDoc}
     * @throws  
     */
    public void start(int port) throws IOException {
        ServerBootstrap bootstrap = null;
        ;

        try {
            bootstrap = new ServerBootstrap();
            bootstrap.option(ChannelOption.SO_RCVBUF, 128 * 1024);
            bootstrap.option(ChannelOption.TCP_NODELAY, true);
            bootstrap.group(new NioEventLoopGroup(), new NioEventLoopGroup());
            bootstrap.channel(NioServerSocketChannel.class);
            bootstrap.localAddress(port);
            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel channel) throws Exception {
                    channel.pipeline().addLast(new TestServerHandler());
                };
            });
            ChannelFuture bindFuture = bootstrap.bind();
            bindFuture.sync();
            Channel channel = bindFuture.channel();
            ChannelFuture closeFuture = channel.closeFuture();
            //closeFuture.sync();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            bootstrap.shutdown();
        }
    }

    /**
     * {@inheritedDoc}
     */
    public void stop() throws IOException {
    }
}
