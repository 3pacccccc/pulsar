/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.proxy.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.pulsar.common.api.proto.BaseCommand;
import org.apache.pulsar.common.api.raw.MessageParser;
import org.apache.pulsar.common.api.raw.RawMessage;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.proxy.stats.TopicStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ParserProxyHandler extends ChannelInboundHandlerAdapter {


    //inbound
    protected static final String FRONTEND_CONN = "frontendconn";
    //outbound
    protected static final String BACKEND_CONN = "backendconn";

    private final String connType;

    private final int maxMessageSize;
    private final ChannelId peerChannelId;
    private final ProxyService service;


    /**
     * producerid + channelid as key.
     */
    private static final Map<String, String> producerHashMap = new ConcurrentHashMap<>();

    /**
     * consumerid + channelid as key.
     */
    private static final Map<String, String> consumerHashMap = new ConcurrentHashMap<>();

    public ParserProxyHandler(ProxyService service, String type, int maxMessageSize,
                              ChannelId peerChannelId) {
        this.service = service;
        this.connType = type;
        this.maxMessageSize = maxMessageSize;
        this.peerChannelId = peerChannelId;
    }

    private void logging(Channel conn, BaseCommand.Type cmdtype, String info, List<RawMessage> messages) {

        if (messages != null) {
            // lag
            StringBuilder infoBuilder = new StringBuilder(info);
            for (RawMessage message : messages) {
                infoBuilder.append("[").append(System.currentTimeMillis() - message.getPublishTime()).append("] ")
                        .append(new String(ByteBufUtil.getBytes(message.getData()), StandardCharsets.UTF_8));
            }
            info = infoBuilder.toString();
        }
        // log conn format is like from source to target
        switch (this.connType) {
            case ParserProxyHandler.FRONTEND_CONN:
                log.info(ParserProxyHandler.FRONTEND_CONN + ":{} cmd:{} msg:{}", "[" + conn.remoteAddress()
                        + conn.localAddress() + "]", cmdtype, info);
                break;
            case ParserProxyHandler.BACKEND_CONN:
                log.info(ParserProxyHandler.BACKEND_CONN + ":{} cmd:{} msg:{}", "[" + conn.localAddress()
                        + conn.remoteAddress() + "]", cmdtype, info);
                break;
        }
    }

    private final BaseCommand cmd = new BaseCommand();

    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        String key;
        String topicName;
        List<RawMessage> messages = new ArrayList<>();
        ByteBuf buffer = (ByteBuf) (msg);

        try {
            buffer.markReaderIndex();
            buffer.markWriterIndex();

            int cmdSize = (int) buffer.readUnsignedInt();
            cmd.parseFrom(buffer,  cmdSize);

            switch (cmd.getType()) {
                case PRODUCER:
                    ParserProxyHandler.producerHashMap.put(cmd.getProducer().getProducerId() + "," + ctx.channel().id(),
                            cmd.getProducer().getTopic());

                    String producerName = "";
                    if (cmd.getProducer().hasProducerName()){
                        producerName = cmd.getProducer().getProducerName();
                    }
                    logging(ctx.channel(), cmd.getType(), "{producer:" + producerName
                            + ",topic:" + cmd.getProducer().getTopic() + "}", null);
                    break;

                case SEND:
                    if (service.getProxyLogLevel() != 2) {
                        logging(ctx.channel(), cmd.getType(), "", null);
                        break;
                    }
                    topicName = TopicName.toFullTopicName(ParserProxyHandler.producerHashMap.get(
                            cmd.getSend().getProducerId() + "," + ctx.channel().id()));
                    MutableLong msgBytes = new MutableLong(0);
                    MessageParser.parseMessage(topicName, -1L,
                            -1L, buffer, (message) -> {
                                messages.add(message);
                                msgBytes.add(message.getData().readableBytes());
                            }, maxMessageSize);
                    // update topic stats
                    TopicStats topicStats = this.service.getTopicStats().computeIfAbsent(topicName,
                        topic -> new TopicStats());
                    topicStats.getMsgInRate().recordMultipleEvents(messages.size(), msgBytes.longValue());
                    logging(ctx.channel(), cmd.getType(), "", messages);
                    break;

                case SUBSCRIBE:
                    ParserProxyHandler.consumerHashMap.put(cmd.getSubscribe().getConsumerId() + ","
                                    + ctx.channel().id(), cmd.getSubscribe().getTopic());

                    logging(ctx.channel(), cmd.getType(), "{consumer:" + cmd.getSubscribe().getConsumerName()
                            + ",topic:" + cmd.getSubscribe().getTopic() + "}", null);
                    break;

                case MESSAGE:
                    if (service.getProxyLogLevel() != 2) {
                        logging(ctx.channel(), cmd.getType(), "", null);
                        break;
                    }
                    topicName = TopicName.toFullTopicName(ParserProxyHandler.consumerHashMap.get(
                            cmd.getMessage().getConsumerId() + "," + peerChannelId));

                    msgBytes = new MutableLong(0);
                    MessageParser.parseMessage(topicName, -1L,
                            -1L, buffer, (message) -> {
                                messages.add(message);
                                msgBytes.add(message.getData().readableBytes());
                            }, maxMessageSize);
                    // update topic stats
                    topicStats = this.service.getTopicStats().computeIfAbsent(topicName.toString(),
                            topic -> new TopicStats());
                    topicStats.getMsgOutRate().recordMultipleEvents(messages.size(), msgBytes.longValue());
                    logging(ctx.channel(), cmd.getType(), "", messages);
                    break;

                 default:
                    logging(ctx.channel(), cmd.getType(), "", null);
                    break;
            }
        } catch (Exception e){
            log.error("channelRead error ", e);
        } finally {
            buffer.resetReaderIndex();
            buffer.resetWriterIndex();

            // add totalSize to buffer Head
            ByteBuf totalSizeBuf = Unpooled.buffer(4);
            totalSizeBuf.writeInt(buffer.readableBytes());
            CompositeByteBuf compBuf = Unpooled.compositeBuffer();
            compBuf.addComponents(totalSizeBuf, buffer);
            compBuf.writerIndex(totalSizeBuf.capacity() + buffer.capacity());

            // Release mssages
            messages.forEach(RawMessage::release);
            //next handler
            ctx.fireChannelRead(compBuf);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ParserProxyHandler.class);
}
