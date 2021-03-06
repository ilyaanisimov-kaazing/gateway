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
package org.kaazing.gateway.transport.nio.internal;

import java.net.InetAddress;
import java.util.Properties;

import javax.annotation.Resource;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.session.IoSessionInitializer;
import org.kaazing.gateway.resource.address.ResourceAddress;
import org.kaazing.gateway.resource.address.ResourceAddressFactory;
import org.kaazing.gateway.resource.address.uri.URIUtils;
import org.kaazing.gateway.transport.BridgeServiceFactory;
import org.kaazing.gateway.transport.bio.MulticastConnector;
import org.kaazing.mina.core.service.IoConnectorEx;
import org.slf4j.LoggerFactory;

public class NioDatagramConnector extends AbstractNioConnector {

    private static final String LOGGER_NAME = String.format("transport.%s.connect", NioProtocol.UDP.name().toLowerCase());

    private BridgeServiceFactory bridgeServiceFactory;
    private ResourceAddressFactory resourceAddressFactory;


    @Resource(name = "bridgeServiceFactory")
    public void setBridgeServiceFactory(BridgeServiceFactory bridgeServiceFactory) {
        this.bridgeServiceFactory = bridgeServiceFactory;
    }

    @Resource(name = "resourceAddressFactory")
    public void setResourceAddressFactory(ResourceAddressFactory factory) {
        this.resourceAddressFactory = factory;
    }

    @Override
    protected ResourceAddressFactory initResourceAddressFactory() {
        return resourceAddressFactory;
    }

    @Override
    protected BridgeServiceFactory initBridgeServiceFactory() {
        return bridgeServiceFactory;
    }

	public NioDatagramConnector(Properties configuration) {
        super(configuration, LoggerFactory.getLogger(LOGGER_NAME));
    }

    @Override
    protected IoConnectorEx initConnector() {
        org.apache.mina.transport.socket.nio.NioDatagramConnectorEx connector = new org.apache.mina.transport.socket.nio.NioDatagramConnectorEx();

        String readBufferSize = configuration.getProperty("org.kaazing.gateway.transport.udp.READ_BUFFER_SIZE");
        String minimumReadBufferSize = configuration.getProperty("org.kaazing.gateway.transport.udp.MINIMUM_READ_BUFFER_SIZE");
        String maximumReadBufferSize = configuration.getProperty("org.kaazing.gateway.transport.udp.MAXIMUM_READ_BUFFER_SIZE");
        
        if (readBufferSize != null) {
            connector.getSessionConfig().setReadBufferSize(Integer.parseInt(readBufferSize));
            logger.debug("READ_BUFFER_SIZE setting for UDP connector: {}", readBufferSize);
        }
        
        if (minimumReadBufferSize != null) {
            connector.getSessionConfig().setMinReadBufferSize(Integer.parseInt(minimumReadBufferSize));
            logger.debug("MINIMUM_READ_BUFFER_SIZE setting for UDP connector: {}", minimumReadBufferSize);
        }
        
        if (maximumReadBufferSize != null) {
            connector.getSessionConfig().setMaxReadBufferSize(Integer.parseInt(maximumReadBufferSize));
            logger.debug("MAXIMUM_READ_BUFFER_SIZE setting for UDP connector: {}", maximumReadBufferSize);
        }

        return connector;
    }

    @Override
    protected String getTransportName() {
        return "udp";
    }

    @Override
    public ConnectFuture connect(ResourceAddress address,
                                 IoHandler handler,
                                 IoSessionInitializer<? extends ConnectFuture> initializer) {
        boolean useMCP = false;
        try {
            String uri = address.getExternalURI();
            InetAddress inet = InetAddress.getByName(URIUtils.getHost(uri));
            if (inet.isMulticastAddress()) {
                useMCP = true;
            }
        } catch (Exception e) {
            // do nothing
        }
        if (useMCP) {
            MulticastConnector connector = new MulticastConnector();
            connector.setConfiguration(getProperties());
            connector.setResourceAddressFactory(resourceAddressFactory);
            connector.setBridgeServiceFactory(bridgeServiceFactory);
            return connector.connect(address, handler, initializer);
        } else {
            return super.connect(address, handler, initializer);
        }
    }
}
