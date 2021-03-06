/**
 * Copyright 2007-2015, Kaazing Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kaazing.mina.netty.socket;

import java.net.InetSocketAddress;

import org.apache.mina.core.service.DefaultTransportMetadata;
import org.apache.mina.core.service.TransportMetadata;
import org.apache.mina.transport.socket.DatagramConnector;
import org.apache.mina.transport.socket.DatagramSessionConfig;
import org.jboss.netty.channel.socket.DatagramChannelFactory;

import org.kaazing.mina.netty.ChannelIoConnector;
import org.kaazing.mina.netty.DefaultIoConnectorChannelHandlerFactory;
import org.kaazing.mina.netty.IoConnectorChannelHandlerFactory;
import org.kaazing.mina.netty.bootstrap.ClientBootstrapFactory;

public abstract class DatagramChannelIoConnector
    extends ChannelIoConnector<DatagramChannelIoSessionConfig, DatagramChannelFactory, InetSocketAddress>
    implements DatagramConnector {

    private static final TransportMetadata CONNECTIONLESS_TRANSPORT_METADATA = new DefaultTransportMetadata(
            "Kaazing", "DatagramChannel", true, true, InetSocketAddress.class,
            DatagramSessionConfig.class, Object.class);

    public DatagramChannelIoConnector(DatagramChannelIoSessionConfig sessionConfig,
            DatagramChannelFactory channelFactory) {
        this(sessionConfig, channelFactory, new DefaultIoConnectorChannelHandlerFactory());
    }

    protected DatagramChannelIoConnector(DatagramChannelIoSessionConfig sessionConfig,
            DatagramChannelFactory channelFactory, IoConnectorChannelHandlerFactory handlerFactory) {
        super(sessionConfig, channelFactory, handlerFactory, ClientBootstrapFactory.CONNECTIONLESS);
    }

    @Override
    public void setDefaultRemoteAddress(InetSocketAddress remoteAddress) {
        super.setDefaultRemoteAddress(remoteAddress);
    }

    @Override
    public TransportMetadata getTransportMetadata() {
        return CONNECTIONLESS_TRANSPORT_METADATA;
    }
}
