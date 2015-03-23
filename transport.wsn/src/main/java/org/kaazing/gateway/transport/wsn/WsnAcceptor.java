/**
 * Copyright (c) 2007-2014 Kaazing Corporation. All rights reserved.
 *
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

package org.kaazing.gateway.transport.wsn;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.kaazing.gateway.resource.address.ResourceAddress.NEXT_PROTOCOL;
import static org.kaazing.gateway.resource.address.ResourceAddress.TRANSPORT;
import static org.kaazing.gateway.resource.address.http.HttpResourceAddress.REALM_CHALLENGE_SCHEME;
import static org.kaazing.gateway.resource.address.ws.WsResourceAddress.CODEC_REQUIRED;
import static org.kaazing.gateway.resource.address.ws.WsResourceAddress.EXTENSIONS;
import static org.kaazing.gateway.resource.address.ws.WsResourceAddress.INACTIVITY_TIMEOUT;
import static org.kaazing.gateway.resource.address.ws.WsResourceAddress.LIGHTWEIGHT;
import static org.kaazing.gateway.resource.address.ws.WsResourceAddress.MAX_MESSAGE_SIZE;
import static org.kaazing.gateway.transport.http.bridge.filter.HttpMergeRequestFilter.DRAFT76_KEY3_BUFFER_KEY;
import static org.kaazing.gateway.transport.http.bridge.filter.HttpSubjectSecurityFilter.AUTH_SCHEME_APPLICATION_PREFIX;
import static org.kaazing.gateway.transport.ws.extension.WsExtension.EndpointKind.SERVER;
import static org.kaazing.gateway.transport.ws.extension.WsExtensionUtils.negotiateWebSocketExtensions;
import static org.kaazing.gateway.transport.ws.util.WsUtils.negotiateWebSocketProtocol;
import static org.kaazing.gateway.transport.wsn.WsnSession.SESSION_KEY;
import static org.kaazing.mina.core.buffer.IoBufferEx.FLAG_NONE;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Resource;
import javax.security.auth.Subject;

import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.CloseFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.TransportMetadata;
import org.apache.mina.core.session.AttributeKey;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionInitializer;
import org.apache.mina.core.write.WriteRequest;
import org.kaazing.gateway.resource.address.Protocol;
import org.kaazing.gateway.resource.address.ResourceAddress;
import org.kaazing.gateway.resource.address.ResourceAddressFactory;
import org.kaazing.gateway.resource.address.ResourceOptions;
import org.kaazing.gateway.resource.address.URLUtils;
import org.kaazing.gateway.resource.address.ws.WsResourceAddress;
import org.kaazing.gateway.resource.address.wsn.WsnResourceAddressFactorySpi;
import org.kaazing.gateway.security.auth.DefaultLoginResult;
import org.kaazing.gateway.security.auth.context.ResultAwareLoginContext;
import org.kaazing.gateway.transport.AbstractBridgeAcceptor;
import org.kaazing.gateway.transport.Bindings;
import org.kaazing.gateway.transport.Bindings.Binding;
import org.kaazing.gateway.transport.BridgeAcceptor;
import org.kaazing.gateway.transport.BridgeServiceFactory;
import org.kaazing.gateway.transport.BridgeSession;
import org.kaazing.gateway.transport.BridgeSessionInitializer;
import org.kaazing.gateway.transport.BridgeSessionInitializerAdapter;
import org.kaazing.gateway.transport.DefaultIoSessionConfigEx;
import org.kaazing.gateway.transport.DefaultTransportMetadata;
import org.kaazing.gateway.transport.ExceptionLoggingFilter;
import org.kaazing.gateway.transport.IoFilterAdapter;
import org.kaazing.gateway.transport.IoHandlerAdapter;
import org.kaazing.gateway.transport.NioBindException;
import org.kaazing.gateway.transport.ObjectLoggingFilter;
import org.kaazing.gateway.transport.TypedAttributeKey;
import org.kaazing.gateway.transport.UpgradeFuture;
import org.kaazing.gateway.transport.http.HttpAcceptSession;
import org.kaazing.gateway.transport.http.HttpAcceptor;
import org.kaazing.gateway.transport.http.HttpProtocol;
import org.kaazing.gateway.transport.http.HttpStatus;
import org.kaazing.gateway.transport.http.HttpUtils;
import org.kaazing.gateway.transport.http.bridge.HttpResponseMessage;
import org.kaazing.gateway.transport.http.bridge.filter.HttpLoginSecurityFilter;
import org.kaazing.gateway.transport.http.bridge.filter.HttpMergeRequestFilter;
import org.kaazing.gateway.transport.http.bridge.filter.HttpProtocolCompatibilityFilter;
import org.kaazing.gateway.transport.http.bridge.filter.HttpSubjectSecurityFilter;
import org.kaazing.gateway.transport.ws.WsAcceptor;
import org.kaazing.gateway.transport.ws.WsBinaryMessage;
import org.kaazing.gateway.transport.ws.WsCloseMessage;
import org.kaazing.gateway.transport.ws.WsContinuationMessage;
import org.kaazing.gateway.transport.ws.WsMessage;
import org.kaazing.gateway.transport.ws.WsPingMessage;
import org.kaazing.gateway.transport.ws.WsPongMessage;
import org.kaazing.gateway.transport.ws.WsTextMessage;
import org.kaazing.gateway.transport.ws.bridge.extensions.WsExtensions;
import org.kaazing.gateway.transport.ws.bridge.filter.ExtensionAwareCodecFilter;
import org.kaazing.gateway.transport.ws.bridge.filter.WsBuffer;
import org.kaazing.gateway.transport.ws.bridge.filter.WsBufferAllocator;
import org.kaazing.gateway.transport.ws.bridge.filter.WsCheckAliveFilter;
import org.kaazing.gateway.transport.ws.bridge.filter.WsCodecFilter;
import org.kaazing.gateway.transport.ws.bridge.filter.WsDraftHixieBufferAllocator;
import org.kaazing.gateway.transport.ws.bridge.filter.WsDraftHixieFrameCodecFilter;
import org.kaazing.gateway.transport.ws.bridge.filter.WsFrameBase64Filter;
import org.kaazing.gateway.transport.ws.bridge.filter.WsFrameEncodingSupport;
import org.kaazing.gateway.transport.ws.bridge.filter.WsFrameTextFilter;
import org.kaazing.gateway.transport.ws.bridge.filter.WsFrameUtf8Filter;
import org.kaazing.gateway.transport.ws.extension.ActiveWsExtensions;
import org.kaazing.gateway.transport.ws.extension.WsExtensionNegotiationResult;
import org.kaazing.gateway.transport.ws.util.WsHandshakeNegotiationException;
import org.kaazing.gateway.transport.ws.util.WsUtils;
import org.kaazing.gateway.util.Encoding;
import org.kaazing.gateway.util.scheduler.SchedulerProvider;
import org.kaazing.gateway.util.ws.WebSocketWireProtocol;
import org.kaazing.mina.core.buffer.IoBufferAllocatorEx;
import org.kaazing.mina.core.buffer.IoBufferEx;
import org.kaazing.mina.core.future.UnbindFuture;
import org.kaazing.mina.core.service.IoProcessorEx;
import org.kaazing.mina.core.session.IoSessionEx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WsnAcceptor extends AbstractBridgeAcceptor<WsnSession, WsnBindings.WsnBinding> {

    private static final TypedAttributeKey<Subject> SUBJECT_TRANSFER_KEY = new TypedAttributeKey<Subject>(WsnAcceptor.class, "subject_transfer");

    static final String CHECK_ALIVE_FILTER = WsnProtocol.NAME + "#checkalive";
	        static final String CODEC_FILTER = WsnProtocol.NAME + "#codec";
	private static final String UTF8_FILTER = WsnProtocol.NAME + "#utf8";
	private static final String BASE64_FILTER = WsnProtocol.NAME + "#base64";
	private static final String TEXT_FILTER = WsnProtocol.NAME + "#text";
    private static final String FAULT_LOGGING_FILTER = WsnProtocol.NAME + "#fault";
    private static final String TRACE_LOGGING_FILTER = WsnProtocol.NAME + "#logging";

    private static final AttributeKey LOCAL_ADDRESS_KEY = new AttributeKey(WsnAcceptor.class, "localAddress");
    private static final AttributeKey HTTP_REQUEST_URI_KEY = new AttributeKey(WsnAcceptor.class, "httpRequestURI");

    private static final TypedAttributeKey<ResourceAddress> WEBSOCKET_LOCAL_ADDRESS
            = new TypedAttributeKey<ResourceAddress>(WsnAcceptor.class, "websocketLocalAddress");

    private static final TypedAttributeKey<String[]> SUPPORTED_PROTOCOLS
            = new TypedAttributeKey<String[]>(WsnAcceptor.class, "supportedProtocols");

    private static final String HEADER_ORIGIN = "Origin";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_UPGRADE = "Upgrade";
    private static final String HEADER_WEBSOCKET_ORIGIN = "WebSocket-Origin";
    private static final String HEADER_WEBSOCKET_LOCATION = "WebSocket-Location";
    private static final String HEADER_WEBSOCKET_PROTOCOL = "WebSocket-Protocol";

    private static final String HEADER_X_WEBSOCKET_PROTOCOL = "X-WebSocket-Protocol";
    private static final String HEADER_X_WEBSOCKET_EXTENSION = WsExtensions.HEADER_X_WEBSOCKET_EXTENSIONS;

    // "secure" header keys introduced in protocol draft 76
    private static final String HEADER_SEC_WEBSOCKET_LOCATION = "Sec-WebSocket-Location";
    private static final String HEADER_SEC_WEBSOCKET_ORIGIN = "Sec-WebSocket-Origin";
    private static final String HEADER_SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
    private static final String HEADER_SEC_WEBSOCKET_EXTENSION = WsExtensions.HEADER_SEC_WEBSOCKET_EXTENSIONS;

    private static final String REASON_WEB_SOCKET_HANDSHAKE = "Web Socket Protocol Handshake";
    private static final String HEADER_WEBSOCKET_KEY1 = "Sec-WebSocket-Key1";
    private static final String HEADER_WEBSOCKET_KEY2 = "Sec-WebSocket-Key2";
    private static final String WEB_SOCKET = "WebSocket";
    private static final String WEB_SOCKET_LOWERCASE = "websocket";

    private static final String PROTOCOL_NAME_WEB_SOCKET_RFC6455 = "ws/rfc6455";
    private static final String PROTOCOL_NAME_WEB_SOCKET_DRAFT = "ws/draft-7x";

    // "Sec-WebSocket-Key" used by HyBi Drafts
    private static final String HEADER_WEBSOCKET_KEY = "Sec-WebSocket-Key";
    private static final String HEADER_WEBSOCKET_ACCEPT = "Sec-WebSocket-Accept";
    private static final String HEADER_WEBSOCKET_VERSION = "Sec-WebSocket-Version";
    private static final String WEB_SOCKET_VERSION_KEY = "WebSocketVersion";

    private static final String LOGGER_NAME = String.format("transport.%s.accept", WsnProtocol.NAME);

    private final Logger logger = LoggerFactory.getLogger(LOGGER_NAME);

    private Properties configuration =  new Properties();
    private ScheduledExecutorService scheduler;
    private BridgeServiceFactory bridgeServiceFactory;
    private ResourceAddressFactory resourceAddressFactory;

    public WsnAcceptor() {
        super(new DefaultIoSessionConfigEx());
    }

    @Resource(name = "configuration")
    public void setConfiguration(Properties configuration) {
        this.configuration = configuration;
    }

    @Resource(name = "schedulerProvider")
    public void setSchedulerProvider(SchedulerProvider provider) {
        this.scheduler = provider.getScheduler("Scheduler-wsn", false);
    }

    @Resource(name = "bridgeServiceFactory")
    public void setBridgeServiceFactory(BridgeServiceFactory bridgeServiceFactory) {
        this.bridgeServiceFactory = bridgeServiceFactory;
    }


    @Resource(name = "resourceAddressFactory")
    public void setResourceAddressFactory(ResourceAddressFactory factory) {
        this.resourceAddressFactory = factory;
    }

    @Override
    protected void init() {
        // Fail gateway startup if the obsolete system property "org.kaazing.gateway.transport.ws.INACTIVITY_TIMEOUT"
        // from JMS Edition release 3.5.3 is used (KG-7125)
        WsCheckAliveFilter.validateSystemProperties(configuration, logger);

        super.init();
    }

    @Override
    public void addBridgeFilters(IoFilterChain filterChain) {
        // setup logging filters for bridge session
        if (logger.isTraceEnabled()) {
            filterChain.addFirst(TRACE_LOGGING_FILTER, new ObjectLoggingFilter(logger, WsnProtocol.NAME + "#%s"));
        } else if (logger.isDebugEnabled()) {
            filterChain.addFirst(FAULT_LOGGING_FILTER, new ExceptionLoggingFilter(logger, WsnProtocol.NAME + "#%s"));
        }
    }

    /* for test observalibility only */
    Bindings<WsnBindings.WsnBinding> bindings() {
        return super.bindings;
    }

    @Override
    protected WsnBindings initBindings() {
        return new WsnBindings();
    }

    @Override
    protected IoProcessorEx<WsnSession> initProcessor() {
        return new WsnAcceptProcessor();
    }

    @Override
    protected boolean canBind(String transportName) {
        return transportName.equals("wsn") || transportName.equals("ws");
    }

    @Override
    public void bind(ResourceAddress address,
                     IoHandler handler,
                     BridgeSessionInitializer<? extends IoFuture> initializer) {
        bind0(address, handler, initializer);
    }

    @Deprecated // move to HttpBalancerFilter added to WsnAcceptor.httpBridgeHandler filterChain instead
    private <T extends IoFuture> /*final*/ void bind0(ResourceAddress address, IoHandler handler, final BridgeSessionInitializer<T> initializer) {

        BridgeSessionInitializer<T> balancerInitializer = new BridgeSessionInitializerAdapter<T>() {
            @Override
            public void initializeSession(IoSession session, T future) {
                if (initializer != null) {
                    initializer.initializeSession(session, future);
                }
                final WsnSession wsnSession = (WsnSession) session;
                boolean redirectResponse = false;
                if (wsnSession.isBalanceSupported()) {
                    // NOTE: this collection is either null, empty or length one.
                    //       the balancee URI is selected in the HttpBalancerService's
                    //       preHttpUpgradeSessionInitializer.  That's why the iterator.next() is assumed here.

                    Collection<URI> balanceeURIs = wsnSession.getBalanceeURIs();
                    String response = "" + '\uf0ff'; // Unique prefix to avoid collisions with responses from non Kaazing servers
                    if (balanceeURIs == null) {
                        // No balancer participated in this session initialization
                        response += "N";
                    } else {
                        if (balanceeURIs.isEmpty()) {
                            // Balancer participated in this session initialization but found no balancees
                            redirectResponse = true;
                            response += "R";
                        } else {
                            // Balancer participated in this session initialization and found balancees
                            try {
                                redirectResponse = true;
                                response += "R";
                                response += HttpUtils.mergeQueryParameters(wsnSession.getParentHttpRequestURI(),
                                        balanceeURIs.iterator().next()).toString();
                            } catch (URISyntaxException e) {
                                logger.error(
                                        String.format("Failed to manufacture a balancee URI:  The Http Request URI Query '%s' cannot merge with the configured balancee URI '%s'",
                                                wsnSession.getParentHttpRequestURI().getQuery(), balanceeURIs.iterator().next()), e);
                            }
                        }
                    }
                    WebSocketWireProtocol wsVersion = wsnSession.getVersion();

                    WriteFuture writeFuture;

                    Boolean codecRequired = wsnSession.getLocalAddress().getOption(CODEC_REQUIRED);

                    final IoSessionEx parent = wsnSession.getParent();
                    if (WebSocketWireProtocol.HYBI_13.equals(wsVersion) || WebSocketWireProtocol.HYBI_8.equals(wsVersion)) {

                        final IoBufferAllocatorEx<? extends WsBuffer> allocator = wsnSession.getBufferAllocator();
                        ByteBuffer messageNioBuf = allocator.allocate(response.getBytes().length + 4);
                        IoBufferEx messageBuf = allocator.wrap(messageNioBuf).setAutoExpander(allocator);
                        try {
                            messageBuf.putString(response, Charset.forName("UTF-8").newEncoder());
                            messageBuf.flip();
                        } catch (CharacterCodingException e) {
                            logger.error(e.toString());
                        }
                        WsBinaryMessage message = new WsBinaryMessage(messageBuf);

                        if (codecRequired) {
                            IoBufferAllocatorEx<?> parentAllocator = parent.getBufferAllocator();
                            IoBufferEx b = WsFrameEncodingSupport.doEncode(parentAllocator, FLAG_NONE, message);
                            writeFuture = parent.write(b); // Write on parent session to avoid another encoding by the WebSocket session
                        } else {
                            writeFuture = parent.write(message);
                        }
                    } else {
                        IoBufferAllocatorEx<?> parentAllocator = parent.getBufferAllocator();

                        ByteBuffer parentNioBuf = parentAllocator.allocate(response.getBytes().length + 6);
                        IoBufferEx parentBuf = parentAllocator.wrap(parentNioBuf).setAutoExpander(parentAllocator);

                        if ( codecRequired ) { parentBuf.put((byte) 0x00); }
                        try {
                            parentBuf.putString(response, Charset.forName("UTF-8").newEncoder());
                        } catch (CharacterCodingException e) {
                            logger.error(e.toString());
                        }
                        if ( codecRequired ) { parentBuf.put((byte) 0xff); }
                        parentBuf.flip();
                        writeFuture = parent.write(parentBuf); // Write on parent session to avoid encoding on ByteSocket connections
                    }

                    // KG-3496: serialize the balancer directive write and the
                    // session close, so that the close cannot win.  Moved the
                    // session close to here with the redirectResponse logic.
                    if (redirectResponse) {
                        writeFuture.addListener(new IoFutureListener<WriteFuture>() {
                            @Override
                            public void operationComplete(WriteFuture future) {
                                // (KG-6305) Must send a WS CLOSE frame to conform to RFC 6455 so client knows the websocket is closed
                                if (wsnSession.sendCloseFrame.compareAndSet(true, false)) {
                                    wsnSession.getParent().write(WsCloseMessage.NORMAL_CLOSE);
                                }
                            }
                        });
                    }
                }
            }

            @Override
            public BridgeSessionInitializer<T> getParentInitializer(Protocol protocol) {
                return initializer != null ? initializer.getParentInitializer(protocol):super.getParentInitializer(protocol);
            }
        };

        //
        // Bind this address to the handler and initializers.
        //
        super.bind(address, handler, balancerInitializer);
    }


    @Override
    protected <T extends IoFuture> void bindInternal(final ResourceAddress address, IoHandler handler,
                                                     final BridgeSessionInitializer<T> initializer) {
        //
        // Bind the transport
        //
        final BridgeSessionInitializer<T> httpInitializer =
                (initializer != null) ? initializer.getParentInitializer(HttpProtocol.HTTP)
                : null;

        BridgeSessionInitializer<T> wrapperInitializer = new BridgeSessionInitializerAdapter<T>() {
            @Override
            public void initializeSession(IoSession session, T future) {
                if ( httpInitializer != null ) {
                    httpInitializer.initializeSession(session, future);
                }

                String nextProtocol = address.getOption(ResourceAddress.NEXT_PROTOCOL);
                String[] supportedProtocols = address.getOption(WsResourceAddress.SUPPORTED_PROTOCOLS);
                String[] allSupportedProtocols = supportedProtocols;
                if (nextProtocol != null) {
                    // add next-protocol (if specified) to end of supported protocols (whether empty or not)
                    allSupportedProtocols = new String[supportedProtocols.length + 1];
                    System.arraycopy(supportedProtocols, 0, allSupportedProtocols, 0, supportedProtocols.length);
                    allSupportedProtocols[supportedProtocols.length] = nextProtocol;
                }

                // Store the next over-the-top of-websocket protocols the server supports for this address on session.
                SUPPORTED_PROTOCOLS.set(session, allSupportedProtocols);
            }
        };

        BridgeAcceptor acceptor = bridgeServiceFactory.newBridgeAcceptor(address.getTransport());
        try {
            ResourceAddress transport = address.getTransport();
            IoHandler transportHandler = selectHandler(address);
            acceptor.bind(transport, transportHandler, wrapperInitializer);
        } catch (NioBindException e) {
            throw new RuntimeException("Unable to bind address " + address.getTransport() + ": " + e.getMessage(), e);
        }
    }

    @Override
    protected UnbindFuture unbindInternal(ResourceAddress address, IoHandler handler,
            BridgeSessionInitializer<? extends IoFuture> initializer) {
        ResourceAddress transport = address.getTransport();
        BridgeAcceptor acceptor = bridgeServiceFactory.newBridgeAcceptor(transport);
        return acceptor.unbind(address.getTransport());
    }

    private IoHandler selectHandler(ResourceAddress address) {
        ResourceAddress httpTransport = address.getTransport();
        String httpNextProtocol = httpTransport.getOption(NEXT_PROTOCOL);
        if ( PROTOCOL_NAME_WEB_SOCKET_RFC6455.equals(httpNextProtocol)) {
            return wsnHttpRFC6455BridgeHandler;
        } else if ( PROTOCOL_NAME_WEB_SOCKET_DRAFT.equals(httpNextProtocol)) {
            return wsnHttpDelegatingBridgeHandler;
        }
        return null;
    }


    @Override
    protected IoFuture dispose0() throws Exception {
        scheduler.shutdownNow();
        return super.dispose0();
    }

    @Override
    public TransportMetadata getTransportMetadata() {
        return new DefaultTransportMetadata(WsnProtocol.NAME);
    }

    private IoHandlerAdapter<IoSessionEx> ioBridgeHandler = new IoHandlerAdapter<IoSessionEx>()  {

        private final WsFrameBase64Filter base64 = new WsFrameBase64Filter();
        private final WsFrameUtf8Filter utf8 = new WsFrameUtf8Filter();
        private final WsFrameTextFilter text = new WsFrameTextFilter();

        @Override
        protected void doSessionOpened(final IoSessionEx session) throws Exception {

            IoFilterChain filterChain = session.getFilterChain();

            final ResourceAddress localAddress = WEBSOCKET_LOCAL_ADDRESS.get(session);

            final WebSocketWireProtocol wsVersion = (WebSocketWireProtocol) session.getAttribute(WEB_SOCKET_VERSION_KEY);

            final boolean wasHixieHandshake = wsVersion != null &&
                                              (wsVersion == WebSocketWireProtocol.HIXIE_76 ||
                                               wsVersion == WebSocketWireProtocol.HIXIE_75);

            addBridgeFilters(filterChain, localAddress);

            session.resumeRead();

            //
            // Construct remote address from transport session's remote address.
            //

            ResourceAddress transportAddress = BridgeSession.REMOTE_ADDRESS.get(session);
            ResourceOptions options = ResourceOptions.FACTORY.newResourceOptions();
            options.setOption(TRANSPORT, transportAddress);
            options.setOption(NEXT_PROTOCOL, localAddress.getOption(NEXT_PROTOCOL));

            final ResourceAddress remoteAddress =
                    resourceAddressFactory.newResourceAddress(localAddress.getResource(),options);

            //
            // We remember the http uri from the session below us.
            //
            final URI httpUri = (URI) session.getAttribute(HTTP_REQUEST_URI_KEY);

            //
            // Create the re-validation address to bind to, and re-validation uri to associate with the WsnSession.
            //
            final boolean isLightweightWsnSession = localAddress.getOption(WsResourceAddress.LIGHTWEIGHT);
            String sessionId = HttpUtils.newSessionId();

            final ActiveWsExtensions wsExtensions = ActiveWsExtensions.get(session);

            //
            // Build a new Wsn Session.
            //
            final WsnSession wsnSession = newSession(new IoSessionInitializer<IoFuture>() {
                @Override
                public void initializeSession(IoSession wsnSession, IoFuture future) {
                    wsnSession.setAttribute(HttpAcceptor.SERVICE_REGISTRATION_KEY, session
                              .getAttribute(HttpAcceptor.SERVICE_REGISTRATION_KEY));
                    WsnSession typedWsnSession = (WsnSession) wsnSession;
                    typedWsnSession.setSubject(SUBJECT_TRANSFER_KEY.remove(session));
                    wsnSession.setAttribute(BridgeSession.NEXT_PROTOCOL_KEY, session.getAttribute(BridgeSession.NEXT_PROTOCOL_KEY));
                    ActiveWsExtensions.get(session).set(wsnSession);
                    HttpMergeRequestFilter.INITIAL_HTTP_REQUEST_KEY.set(wsnSession, HttpMergeRequestFilter.INITIAL_HTTP_REQUEST_KEY.remove(session));
                    DRAFT76_KEY3_BUFFER_KEY.set(wsnSession, DRAFT76_KEY3_BUFFER_KEY.remove(session));
                    if (HttpEmptyPacketWriterFilter.writeExtraEmptyPacketRequired(wsnSession)) {
                        wsnSession.setAttribute(HttpProtocolCompatibilityFilter.EMPTY_PACKET_PRODUCER_FILTER, HttpEmptyPacketWriterFilter.INSTANCE);
                    }
                }
            }, new Callable<WsnSession>() {
                @Override
                public WsnSession call() {
                    URI httpRequestURI = httpUri;
                    ResultAwareLoginContext loginContext = (ResultAwareLoginContext) session.getAttribute(HttpLoginSecurityFilter.LOGIN_CONTEXT_KEY);
                    IoBufferAllocatorEx<?> parentAllocator = session.getBufferAllocator();
                    IoBufferAllocatorEx<WsBuffer> allocator = wasHixieHandshake ? new WsDraftHixieBufferAllocator(parentAllocator)
                                                                                : new WsBufferAllocator(parentAllocator, false /* masking */);
                    WsnSession newWsnSession = new WsnSession(WsnAcceptor.this, getProcessor(), localAddress, remoteAddress,
                            session, allocator, httpRequestURI, loginContext == null ? new DefaultLoginResult() : loginContext.getLoginResult(), wsExtensions,
                            wsVersion);

                    IoHandler handler = getHandler(localAddress);

                    newWsnSession.setHandler(handler);
                    newWsnSession.setBridgeServiceFactory(bridgeServiceFactory);
                    newWsnSession.setResourceAddressFactory(resourceAddressFactory);
                    newWsnSession.setScheduler(scheduler);
                    return newWsnSession;
                }
            });

            // Establish session cleanup listener.
            CloseFuture closeFuture = wsnSession.getCloseFuture();
            closeFuture.addListener(new IoFutureListener<CloseFuture>() {
                @Override
                public void operationComplete(CloseFuture future) {
                    if (logger.isTraceEnabled()) {
                        logger.trace(WsnAcceptor.class.getSimpleName()
                                + " removing enforcement of lifetime for closed session (" + wsnSession.getId() + ").");
                    }

                    DRAFT76_KEY3_BUFFER_KEY.remove(wsnSession);

                    wsnSession.shutdownScheduledCommands();
                    wsnSession.logout();
                }
            });

            SESSION_KEY.set(session, wsnSession);

            if ( !session.isClosing() ) {
                wsnSession.startupScheduledCommands();
            }
        }

        @Override
        protected void doMessageReceived(IoSessionEx session, Object message) throws Exception {
            WsnSession wsnSession = SESSION_KEY.get(session);

            if (wsnSession != null) {
                IoFilterChain filterChain = wsnSession.getFilterChain();
                IoBufferAllocatorEx<? extends WsBuffer> allocator = wsnSession.getBufferAllocator();

                final boolean hasPostUpgradeChildWsnSession = wsnSession.getHandler() == ioBridgeHandler;
                final ResourceAddress wsnSessionLocalAddress = wsnSession.getLocalAddress();
                final boolean isLightweightWsnSession = wsnSessionLocalAddress.getOption(LIGHTWEIGHT);
                boolean sendMessagesDirect = isLightweightWsnSession
                                             && hasPostUpgradeChildWsnSession; // post-upgrade
                if ( sendMessagesDirect ) {
                    filterChain.fireMessageReceived(message);
                    return;
                }


                WsMessage wsMessage = (WsMessage) message;
                switch (wsMessage.getKind()) {
                case CONTINUATION:
                    WsContinuationMessage wsCont = (WsContinuationMessage) wsMessage;
                    IoBufferEx wsContBytes = wsCont.getBytes();
                    WsBuffer wsContBuffer = allocator.wrap(wsContBytes.buf());
                    wsContBuffer.setKind(WsBuffer.Kind.CONTINUATION);
                    wsContBuffer.setFin(wsCont.isFin());
                    filterChain.fireMessageReceived(wsContBuffer);
                    break;
                case TEXT:
                    WsTextMessage wsText = (WsTextMessage) wsMessage;
                    IoBufferEx wsTextBytes = wsText.getBytes();
                    WsBuffer wsTextBuffer = allocator.wrap(wsTextBytes.buf());
                    wsTextBuffer.setKind(WsBuffer.Kind.TEXT);
                    wsTextBuffer.setFin(wsText.isFin());
                    filterChain.fireMessageReceived(wsTextBuffer);
                    break;
                case BINARY:
                    WsBinaryMessage wsBinary = (WsBinaryMessage) wsMessage;
                    IoBufferEx wsBinaryBytes = wsBinary.getBytes();
                    WsBuffer wsBinaryBuffer = allocator.wrap(wsBinaryBytes.buf());
                    wsBinaryBuffer.setKind(WsBuffer.Kind.BINARY);
                    wsBinaryBuffer.setFin(wsBinary.isFin());
                    filterChain.fireMessageReceived(wsBinaryBuffer);
                    break;
                case PING:
                    // bounce back PONGs in response to client PINGs
                    WsPingMessage ping = (WsPingMessage) wsMessage;
                    IoBufferEx payload = ping.getBytes();
                    WsPongMessage pong = new WsPongMessage(payload);
                    pong.setStyle(ping.getStyle());
                    session.write(pong);
                    break;
                case PONG:
                    // We should recognize but choose to ignore PONGS.
                    // The WsCheckAliveFilter when present will handle the
                    // interpretation of PONG messages.  If we get here, it
                    // is safe to ignore them per the RFC6455 spec.
                    break;
                case CLOSE:
                    // WebSockt API version 13: echo close frame to client
                    if (wsnSession.sendCloseFrame.compareAndSet(true, false)) {
                        WsCloseMessage close =  (WsCloseMessage) wsMessage;
                        WsCloseMessage closeResponse = new WsCloseMessage(close.getStatus(), close.getReason());
                        session.write(closeResponse);
                    }
                    //close the connection
                    session.close(false);
                    break;
                default:
                    throw new IllegalArgumentException("Unrecognized message kind: " + wsMessage.getKind());
                }
            }
        }

        @Override
        protected void doExceptionCaught(IoSessionEx session, Throwable cause) throws Exception {
            WsnSession wsnSession = SESSION_KEY.get(session);
            if (wsnSession != null && !wsnSession.isClosing()) {
                wsnSession.reset(cause);
            }
            else {
                if (logger.isDebugEnabled()) {
                    String message = format("Error on WebSocket connection, closing connection: %s", cause);
                    if (logger.isTraceEnabled()) {
                        // note: still debug level, but with extra detail about the exception
                        logger.debug(message, cause);
                    }
                    else {
                        logger.debug(message);
                    }
                }
                session.close(true);
            }
        }

        /*
         * error here when we get message sent for http session durring upgrade
         *
         * @Override protected void doMessageSent(IoSession session, Object message) throws Exception { IoFilterChain filterChain
         * = getSessionFilterChain(session); filterChain.fireMessageSent(new DefaultWriteRequest(message)); }
         */

        @Override
        protected void doSessionClosed(IoSessionEx session) throws Exception {
            WsnSession wsnSession = SESSION_KEY.get(session);
            if (wsnSession != null && !wsnSession.isClosing() && !wsnSession.getLocalAddress().getOption(WsResourceAddress.LIGHTWEIGHT)) {
                wsnSession.reset(new Exception("Network connectivity has been lost or transport was closed at other end").fillInStackTrace());
            }

            if (wsnSession != null && wsnSession.getLocalAddress().getOption(WsResourceAddress.LIGHTWEIGHT)) {
                wsnSession.close(true);
            }

            IoFilterChain filterChain = session.getFilterChain();
            removeBridgeFilters(filterChain);
        }

        @Override
        protected void doSessionIdle(IoSessionEx session, IdleStatus status) throws Exception {
            WsnSession wsnSession = SESSION_KEY.get(session);
            if (wsnSession != null) {
                IoFilterChain filterChain = wsnSession.getFilterChain();
                filterChain.fireSessionIdle(status);
            }
        }

        @Override
        protected void doSessionCreated(IoSessionEx session) throws Exception {
            // TODO: might need to move code in open to here
        }

        private void addBridgeFilters(IoFilterChain filterChain,
                                      ResourceAddress localAddress) {

            IoSession session = filterChain.getSession();
            Encoding encoding = (Encoding) session.getAttribute("encoding");
            IoFilter codec;

            WebSocketWireProtocol wsVersion = (WebSocketWireProtocol) session.getAttribute(WEB_SOCKET_VERSION_KEY);
            Boolean codecRequired = localAddress.getOption(CODEC_REQUIRED);
            Boolean lightWeightWsnSession = localAddress.getOption(LIGHTWEIGHT);
            int wsMaxMessageSize = localAddress.getOption(MAX_MESSAGE_SIZE);

            boolean rfc = WebSocketWireProtocol.HYBI_13.equals(wsVersion) ||
                          WebSocketWireProtocol.HYBI_8.equals(wsVersion);


            // TODO: don't create codec filter if not required
            if ( rfc ) {
                codec = new WsCodecFilter(wsMaxMessageSize, false);
            } else {
                codec = new WsDraftHixieFrameCodecFilter(wsMaxMessageSize);
            }


            // add framing before encoding
            if ( codecRequired ) {
                filterChain.addLast(CODEC_FILTER, codec);
                session.setAttribute("codecKey", codec);
            }
            switch (encoding) {
            case UTF8:
                filterChain.addLast(UTF8_FILTER, utf8);
                break;
            case BASE64:
                filterChain.addLast(BASE64_FILTER, base64);
                break;
            case TEXT:
                filterChain.addLast(TEXT_FILTER, text);
                break;
            default:
                break;
            }

            // If the WS version is high enough, ensure that we conform to
            // the RFC with regard to CLOSE frames (see KG-6745).
            if (codecRequired && WsCloseFilter.neededForProtocolVersion(wsVersion)) {
                if (logger.isTraceEnabled()) {
                    logger.trace(String.format("Adding CLOSE frame filter for WS protocol version %s for session %s", wsVersion, session));
                }

                filterChain.addLast(WsAcceptor.CLOSE_FILTER, new WsCloseFilter(wsVersion, configuration, logger, scheduler));
            }

            // Use ping and pong, if available, to detect and close dead connections (KG-6379)
            if (codecRequired) { // do NOT include the filter on the upper ws session (extended handshake case)
                if (rfc) {
                    WsCheckAliveFilter.addIfFeatureEnabled(filterChain, CHECK_ALIVE_FILTER, localAddress.getOption(INACTIVITY_TIMEOUT), logger);
                }
                else {
                    if (logger.isDebugEnabled() && localAddress.getOption(INACTIVITY_TIMEOUT) > 0) {
                        logger.debug(String.format(
                                "WebSocket protocol version %s is not 8 or 13, WebSocket inactivity timeout not supported for client session %s",
                                wsVersion, session));
                    }
                }
            }

            // Set the extensions only once all the extensions have been negotiated.
            if (!lightWeightWsnSession) {
                ActiveWsExtensions extensions = ActiveWsExtensions.get(session);
                IoSession codecSession = session;
                if ( !codecRequired ) {
                    // completed extended handshake case, session is a WsnSession so we must get its parent,
                    // and merge in the extensions from the original (RFC 6455) handshake
                    codecSession = ((BridgeSession) session).getParent();
                    codec = (IoFilter) codecSession.removeAttribute("codecKey");
                    ActiveWsExtensions extensionsFromOriginalHandshake = ActiveWsExtensions.get(codecSession);
                    extensions = ActiveWsExtensions.merge(extensions, extensionsFromOriginalHandshake, SERVER);
                }
                if ( codec instanceof ExtensionAwareCodecFilter) {
                    ((ExtensionAwareCodecFilter) codec).setExtensions(codecSession, extensions);
                }
                else {
                    throw new IllegalArgumentException(
                            "Please use an escape-aware codec filter when escape sequences are required for extensions.");
                }
                extensions.updateBridgeFilters(codecSession.getFilterChain());
            }
        }

        private void removeBridgeFilters(IoFilterChain filterChain) {
            removeFilter(filterChain, CODEC_FILTER);
            removeFilter(filterChain, utf8);
            removeFilter(filterChain, base64);
            removeFilter(filterChain, text);
            removeFilter(filterChain, WsAcceptor.CLOSE_FILTER);
            IoSession session = filterChain.getSession();
            ActiveWsExtensions extensions = ActiveWsExtensions.get(session);
            if (extensions != null) {
                extensions.removeBridgeFilters(filterChain);
            }
        }
    };


    private class WsnHttpBridgeHandler extends IoHandlerAdapter<HttpAcceptSession> {


        private static final String WEB_SOCKET_UPGRADE_FAILED_REASON = "WebSocket Upgrade Failure";

        protected boolean doUpgradeEligibilityChecks(HttpAcceptSession session) {
            // KG-3357: Check for case where there is an extra path element compared to the accept URI
            // Note that this check relies on the logic in the DefaultHttpSession constructor logic that sets the
            // pathInfo to the path relative to the service accept URI path.
            String path = session.getPathInfo().getPath();
            if (path == null || path.length() == 0) {
                return true;
            }

            if (logger.isInfoEnabled()) {
                logger.info(String.format("%s - websocket path \"%s\" not found, does not match a configured accept URI",
                                          session.getParent().getRemoteAddress(), session.getRequestURI().getPath()));
            }

            session.setStatus(HttpStatus.CLIENT_NOT_FOUND);
            session.setReason(WEB_SOCKET_UPGRADE_FAILED_REASON);
            session.close(false);

            return false;
        }

        protected void doUpgradeFailure(HttpAcceptSession session) throws Exception {
            session.setStatus(HttpStatus.CLIENT_BAD_REQUEST);
            session.setReason(WEB_SOCKET_UPGRADE_FAILED_REASON);
            session.close(false);
        }


        protected URI getWebSocketLocation(HttpAcceptSession session, WebSocketWireProtocol wireProtocol) throws URISyntaxException {
            URI uri = session.getRequestURL();
            String scheme = session.isSecure() ? "wss" : "ws";
            // Note: According to specification, WebSocket location MUST CONTAIN
            // query string and fragment "resource name" as defined by the
            // middle token of the first line of the WebSocket handshake
            // Note: Chrome 4.0.249.78 closes WebSocket after specification-compliant
            // handshake if WebSocket location contains a query string.
            // Workaround to avoid echoing back the query string in that case.
            String query = uri.getQuery();
            if ("nq".equals(session.getParameter(".kl"))) {
                query = null;
            }
            int port = uri.getPort();
            if (wireProtocol == WebSocketWireProtocol.HIXIE_76) {
                if (session.isSecure() && port == 443) {
                    port = -1;
                } else if (!session.isSecure() && port == 80) {
                    port = -1;
                }
            }
            URI location = new URI(scheme, uri.getUserInfo(), uri.getHost(), port, uri.getPath(), query, uri
                    .getFragment());

            return location;
        }

        protected Encoding getWebSocketEncoding(HttpAcceptSession session) {
            String encoding = session.getParameter("encoding");
            Encoding wsEncoding = Encoding.TEXT; // default to TEXT
            if (encoding == null) {
                String wsVersion = session.getReadHeader(HEADER_WEBSOCKET_VERSION);
                if ("13".equals(wsVersion)) {
                    wsEncoding = Encoding.BINARY;
                }

                String acceptFrameType = session.getReadHeader("X-Accept-FrameType");
                if ("binary".equals(acceptFrameType)) {
                    wsEncoding = Encoding.BINARY;
                } else if ("base64".equals(acceptFrameType)) {
                    wsEncoding = Encoding.BASE64;
                } else if ("utf8".equals(acceptFrameType)) {
                    wsEncoding = Encoding.UTF8;
                }
            } else if ("binary".equals(encoding)) {
                wsEncoding = Encoding.BINARY;
            } else if ("base64".equals(encoding)) {
                wsEncoding = Encoding.BASE64;
            } else if ("utf8".equals(encoding)) {
                wsEncoding = Encoding.UTF8;
            }
            return wsEncoding;
        }

        protected ResourceAddress getWsLocalAddress(HttpAcceptSession session,
                                                    final String schemeName,
                                                    String nextProtocol) {

            ResourceOptions options = ResourceOptions.FACTORY.newResourceOptions();
            options.setOption(TRANSPORT, session.getLocalAddress());
            options.setOption(NEXT_PROTOCOL, nextProtocol);

            URI resource = session.getLocalAddress().getResource();

            URI wsLocalAddressLocation = URLUtils.modifyURIScheme(resource,
                    schemeName);

            ResourceAddress candidate = resourceAddressFactory.newResourceAddress(
                    wsLocalAddressLocation, options);

            Binding binding = bindings.getBinding(candidate);

            if (binding == null) {
                if ( logger.isDebugEnabled() ) {
                    logger.debug("\n***Did NOT find local address for WS session:" +
                                 "\n***using candidate:\n" +
                                 candidate +
                                 "\n***with bindings " +
                                 bindings);
                }
                return null;
            }

            if (logger.isTraceEnabled()) {
                logger.trace("\n***Found local address for WS session:\n" +
                             binding.bindAddress() +
                             "\n***via candidate:\n" +
                             candidate +
                             "\n***with bindings " +
                             bindings);
            }

            return binding.bindAddress();
        }

        @Override
        protected void doExceptionCaught(HttpAcceptSession session, Throwable cause) throws Exception {
            String msg = String.format("Exception encountered in WsnAcceptor.WsnHttpBridgeHandler: %s",
                    cause.getLocalizedMessage());

            if ( logger.isDebugEnabled() ) {
                logger.warn(msg, cause);
            } else {
                logger.warn(msg);
            }

            doUpgradeFailure(session);
            }

        /*
         * "Application Basic", "Application Token", "Application Negotiate" challenge schemes
         * need kaazing specific client library to do authentication. If the request is native
         * WebSocket request(like chrome native), just reject it. Since there is no way to
         * negotiate authentication credentials without kaazing specific client library, just
         * send 403
         */
        protected boolean verifyApplicationChallengeSchemeSecurity(final HttpAcceptSession session) {
            // HttpMergeRequestFilter.INITIAL_HTTP_REQUEST_KEY is used on WsnSession, NioSocketChannelIoSession
            // to keep track of initial x-kaazing-handshake request
            if (HttpMergeRequestFilter.INITIAL_HTTP_REQUEST_KEY.get(session.getParent()) == null) {
                // Not a x-kaazing-handshake initial or extended request
                String httpChallengeScheme = session.getLocalAddress().getOption(REALM_CHALLENGE_SCHEME);
                if (httpChallengeScheme != null && httpChallengeScheme.startsWith(AUTH_SCHEME_APPLICATION_PREFIX)) {
                    // challenge scheme starts with "Application ", so reject it (403 as no way to negotiate)
                    session.setStatus(HttpStatus.CLIENT_FORBIDDEN);
                    session.close(false);
                    return false;
                }
            }
            return true;
        }
    }


    private WsnHttpBridgeHandler wsnHttpRFC6455BridgeHandler = new WsnHttpBridgeHandler() {

        @Override
        protected void doSessionOpened(HttpAcceptSession session) throws Exception {
            super.doSessionOpened(session);

            if (!super.doUpgradeEligibilityChecks(session)) {
                // prevent processing of WebSocket handshake for failed upgrades (e.g. path not found).
                return;
            }

            // Verify header requirements for paranoia.
            String upgrade = session.getReadHeader(HEADER_UPGRADE);
            if (upgrade != null && WEB_SOCKET.equalsIgnoreCase(upgrade) && session.getReadHeader(HEADER_WEBSOCKET_KEY) != null) {
                // do native upgrade for HyBi draft versions 8+
                doUpgrade(session);
            } else {
                doUpgradeFailure(session);
            }
        }

        private void doUpgrade(final HttpAcceptSession session) throws Exception {
            switch (session.getMethod()) {
                case GET:
                    // suspend reads until after protocol switch complete
                    session.suspendRead();

                    // get key values from session
                    List<String> clientRequestedWsProtocols = session.getReadHeaders(HEADER_SEC_WEBSOCKET_PROTOCOL);
                    List<String> clientRequestedExtensions = session.getReadHeaders(HEADER_SEC_WEBSOCKET_EXTENSION);

                    final String wsVersionString = session.getReadHeader(HEADER_WEBSOCKET_VERSION);
                    String key = session.getReadHeader(HEADER_WEBSOCKET_KEY);
                    final Encoding encoding = getWebSocketEncoding(session);

                    // RFC 64554.2.1.4 request MUST contain Connection: Upgrade
                    boolean connectionUpgrade = false;
                    final List<String> connectionHeaders = session.getReadHeaders(HEADER_CONNECTION);
                    for (String connectionHeader : connectionHeaders) {
                        if (HEADER_UPGRADE.equalsIgnoreCase(connectionHeader)) {
                            connectionUpgrade = true;
                        }
                    }
                    if (!connectionUpgrade) {
                        session.setStatus(HttpStatus.CLIENT_BAD_REQUEST);
                        session.close(false);
                        return;
                    }

                    WebSocketWireProtocol wsv;
                    if ("13".equals(wsVersionString)) {
                        wsv = WebSocketWireProtocol.HYBI_13;
                    } else if ("8".equals(wsVersionString)) {
                        wsv = WebSocketWireProtocol.HYBI_8;
                    } else {
                        session.setStatus(HttpStatus.CLIENT_BAD_REQUEST);
                        session.close(false);
                        return;
                    }
                    final WebSocketWireProtocol wsVersion = wsv;

                    // workaround for KG-8996: old 3.5 iOS rfc clients send and expect Upgrade: WebSocket back
                    String webSocketUpgradeResponseValue = WEB_SOCKET_LOWERCASE;
                    String upgrade = session.getReadHeader(HEADER_UPGRADE);
                    if (WEB_SOCKET.equals(upgrade)) {
                        webSocketUpgradeResponseValue = WEB_SOCKET;
                    }

                    // build the HTML5 WebSocket handshake response
                    session.setStatus(HttpStatus.INFO_SWITCHING_PROTOCOLS);
                    session.setReason(REASON_WEB_SOCKET_HANDSHAKE);
                    session.addWriteHeader(HEADER_UPGRADE, webSocketUpgradeResponseValue);
                    session.addWriteHeader(HEADER_CONNECTION, HEADER_UPGRADE);
                    session.addWriteHeader(HEADER_WEBSOCKET_ACCEPT, WsUtils.AcceptHash(key));

                    // negotiate protocol
                    String chosenProtocol;
                    try {
                        chosenProtocol = negotiateWebSocketProtocol(session,
                                HEADER_SEC_WEBSOCKET_PROTOCOL,
                                clientRequestedWsProtocols,
                                asList(SUPPORTED_PROTOCOLS.remove(session)));

                    } catch (WsHandshakeNegotiationException e) {
                        String msg = "Failure during protocol negotiation.";
                        if ( logger.isDebugEnabled() ) {
                            logger.warn(msg, e);
                        } else {
                            logger.warn(msg);
                        }
                        doUpgradeFailure(session);
                        return;
                    }

                    // If configured with "Application xxx" challenge scheme, reject and close the connection
                    if (!verifyApplicationChallengeSchemeSecurity(session)) {
                        return;
                    }

                    // find (based on this http session) the local address for the WS session
                    // we are about to upgrade to.
                    ResourceAddress localAddress = getWsLocalAddress(session, WsnResourceAddressFactorySpi.SCHEME_NAME, chosenProtocol);

                    // fallback to null protocol as a workaround until we properly inject next protocol from service during bind
                    // This is safe as we guard this logic via negotiateWebSocketProtocol function
                    // If the client send any bogus protocol that is not in the list of supported protocols, we will fail fast before getting here
                    if (localAddress == null) {
                        chosenProtocol = null;
                        localAddress = getWsLocalAddress(session, WsnResourceAddressFactorySpi.SCHEME_NAME, null);
                    }

                    final ResourceAddress wsLocalAddress = localAddress;


                    // null next-protocol from client gives null local address when we only have explicitly named next-protocol binds
                    List<String> wsExtensions = (wsLocalAddress != null) ? wsLocalAddress.getOption(EXTENSIONS) : EXTENSIONS.defaultValue();

                    // negotiate extension
                    WsExtensionNegotiationResult extNegotiationResult =
                            negotiateWebSocketExtensions(
                                    wsLocalAddress, session, HEADER_SEC_WEBSOCKET_EXTENSION,
                                    clientRequestedExtensions, wsExtensions);
                    if (extNegotiationResult.isFailure()) {
                        // This happens when the extension negotiation leads to
                        // a fatal failure; the session should be closed because
                        // the service REQUIRED some extension that the client
                        // did not request.
                        if (logger.isDebugEnabled()) {
                            if (logger.isDebugEnabled()) {
                                // KG-10384: make sure port is explicitly included in the request URI we use for lookup since it is always
                                // included when the service registry is created since we force use of explicit port in accepts.
                                // TODO: consider doing this "at the edge" when the HTTP request object (or http session) is created.
                                URI requestURI = HttpUtils.getRequestURI(session.getRequestURL(), session.getReadHeader("Host"),
                                        session);
                                logger.debug(String.format(
                                        "Rejected %s request for URI \"%s\" on session '%s': failed to negotiate client requested extensions '%s'",
                                        session.getMethod(), requestURI, session, clientRequestedExtensions));
                            }
                        }
                        session.setStatus(HttpStatus.CLIENT_NOT_FOUND);
                        session.setReason("WebSocket Extensions not found");
                        session.close(false);
                        return;
                    }


                    final String wsProtocol0 = chosenProtocol;
                    final ActiveWsExtensions wsExtensions0 = extNegotiationResult.getExtensions();

                    // do upgrade
                    UpgradeFuture upgradeFuture = session.upgrade(ioBridgeHandler);
                    upgradeFuture.addListener(new IoFutureListener<UpgradeFuture>() {
                        @Override
                        public void operationComplete(UpgradeFuture future) {
                            IoSession parent = future.getSession();
                            parent.setAttribute("encoding", encoding);
                            parent.setAttribute(BridgeSession.NEXT_PROTOCOL_KEY, wsProtocol0);
                            wsExtensions0.set(parent);
                            WEBSOCKET_LOCAL_ADDRESS.set(parent, wsLocalAddress);
                            parent.setAttribute(WEB_SOCKET_VERSION_KEY, wsVersion);
                            parent.setAttribute(LOCAL_ADDRESS_KEY, session.getLocalAddress());
                            parent.setAttribute(HttpAcceptor.SERVICE_REGISTRATION_KEY, session.getAttribute(HttpAcceptor.SERVICE_REGISTRATION_KEY));
                            parent.setAttribute(SUBJECT_TRANSFER_KEY, session.getSubject());
                            parent.setAttribute(HttpSubjectSecurityFilter.LOGIN_CONTEXT_KEY, session.getAttribute(HttpSubjectSecurityFilter.LOGIN_CONTEXT_KEY));
                            parent.setAttribute(HTTP_REQUEST_URI_KEY, session.getRequestURL());
                        }
                    });
                    session.close(false);

                    break;
                default:
                    session.setStatus(HttpStatus.CLIENT_METHOD_NOT_ALLOWED);
                    session.close(false);
                    break;
            }
        }
    };

    private WsnHttpBridgeHandler wsnHttpDelegatingBridgeHandler = new WsnHttpBridgeHandler() {

        private static final String WSN_HTTP_BRIDGE_HANDLER = "wsn.delegate.bridge.handler";


        @Override
        protected void doSessionOpened(HttpAcceptSession session) throws Exception {
            super.doSessionOpened(session);

            if (!super.doUpgradeEligibilityChecks(session)) {
                // prevent processing of WebSocket handshake for failed upgrades (e.g. path not found).
                return;
            }

            final WsnHttpBridgeHandler handler =
                    (WsnHttpBridgeHandler)
                            session.getAttribute(WSN_HTTP_BRIDGE_HANDLER);

            if (handler != null ) {
                handler.sessionOpened(session);
                return;
            }

            String upgrade = session.getReadHeader(HEADER_UPGRADE);


            // Handle the case where the Upgrade header is not present as well
            if (upgrade == null) {
                return;
            }

            if (WEB_SOCKET.equalsIgnoreCase(upgrade)) {
                if (session.getReadHeader(HEADER_WEBSOCKET_KEY) == null &&
                        session.getReadHeader(HEADER_WEBSOCKET_KEY1) != null) {
                    // We're going to assume you are waiting for the extra "key3" bytes in Hixie Draft 76
                    session.setAttribute(WSN_HTTP_BRIDGE_HANDLER, wsnHttpDraft76BridgeHandler);
                    wsnHttpDraft76BridgeHandler.sessionOpened(session);

                } else if (session.getReadHeader(HEADER_WEBSOCKET_KEY) == null &&
                        session.getReadHeader(HEADER_WEBSOCKET_KEY1) == null) {
                    // The absence of any sec- headers indicates that the UA speaks
                    // WebSocket protocol version 75
                    session.setAttribute(WSN_HTTP_BRIDGE_HANDLER, wsnHttpDraft75BridgeHandler);
                    wsnHttpDraft75BridgeHandler.sessionOpened(session);
                } else {
                    doUpgradeFailure(session);
                }
            } else {
                doUpgradeFailure(session);
            }


        }

        @Override
        protected void doMessageReceived(HttpAcceptSession session, Object message) throws Exception {
            final WsnHttpBridgeHandler handler =
                    (WsnHttpBridgeHandler)
                            session.getAttribute(WSN_HTTP_BRIDGE_HANDLER);

            if (handler != null ) {
                handler.messageReceived(session, message);
                return;
            }

            super.doMessageReceived(session, message);
        }

        @Override
        protected void doSessionClosed(HttpAcceptSession session) throws Exception {
            // clean up the attribute.
            session.removeAttribute(WSN_HTTP_BRIDGE_HANDLER);
        }


    };

    private WsnHttpBridgeHandler wsnHttpDraft76BridgeHandler = new WsnHttpBridgeHandler() {


        @Override
        protected void doSessionOpened(HttpAcceptSession session) throws Exception {
            super.doSessionOpened(session);

            if (!super.doUpgradeEligibilityChecks(session)) {
                // prevent processing of WebSocket handshake for failed upgrades (e.g. path not found).
                return;
            }

            String upgrade = session.getReadHeader(HEADER_UPGRADE);

            // Handle the case where the Upgrade header is not present as well
            if (upgrade == null) {

                return;
            }

            if (WEB_SOCKET.equalsIgnoreCase(upgrade) &&
                        session.getReadHeader(HEADER_WEBSOCKET_KEY) == null &&
                        session.getReadHeader(HEADER_WEBSOCKET_KEY1) != null) {
                    // We're going to assume you are waiting for the extra "key3" bytes in Hixie Draft 76
            } else {
                doUpgradeFailure(session);
            }
        }

        @Override
        protected void doMessageReceived(HttpAcceptSession session, Object message) throws Exception {
            IoBufferEx key3 = (IoBufferEx) message;
            doUpgrade76(session, key3);
        }


        private void doUpgrade76(final HttpAcceptSession session, IoBufferEx key3) throws URISyntaxException {
            switch (session.getMethod()) {
                case GET:
                    // suspend reads until after protocol switch complete
                    session.suspendRead();

                    // get key values from session
                    String origin = session.getReadHeader(HEADER_ORIGIN);
                    List<String> clientRequestedWsProtocols = session.getReadHeaders(HEADER_SEC_WEBSOCKET_PROTOCOL);
                    List<String> clientRequestedExtensions = session.getReadHeaders(HEADER_SEC_WEBSOCKET_EXTENSION);
                    URI wsLocation = getWebSocketLocation(session, WebSocketWireProtocol.HIXIE_76);
                    final Encoding encoding = getWebSocketEncoding(session);

                    // build the HTML5 WebSocket handshake response
                    session.setStatus(HttpStatus.INFO_SWITCHING_PROTOCOLS);
                    session.setReason(REASON_WEB_SOCKET_HANDSHAKE);
                    session.addWriteHeader(HEADER_UPGRADE, WEB_SOCKET);
                    session.addWriteHeader(HEADER_CONNECTION, HEADER_UPGRADE);
                    session.addWriteHeader(HEADER_SEC_WEBSOCKET_ORIGIN, origin);
                    session.addWriteHeader(HEADER_SEC_WEBSOCKET_LOCATION, wsLocation.toASCIIString());



                    String wsProtocol;

                    try {
                        wsProtocol = negotiateWebSocketProtocol(session,
                                HEADER_SEC_WEBSOCKET_PROTOCOL,
                                clientRequestedWsProtocols,
                                asList(SUPPORTED_PROTOCOLS.remove(session)));
                    } catch (WsHandshakeNegotiationException e) {
                        return;
                    }

                    // If configured with "Application xxx" challenge scheme, close the connection
                    if (!verifyApplicationChallengeSchemeSecurity(session)) {
                        return;
                    }

                    // find (based on this http session) the local address for the WS session
                    // we are about to upgrade to.
                    ResourceAddress localAddress = getWsLocalAddress(session, WsnResourceAddressFactorySpi.SCHEME_NAME, wsProtocol);

                    // fallback to null protocol as a workaround until we properly inject next protocol from service during bind
                    // This is safe as we guard this logic via negotiateWebSocketProtocol function
                    // If the client send any bogus protocol that is not in the list of supported protocols, we will fail fast before getting here
                    if (localAddress == null) {
                        wsProtocol = null;
                        localAddress = getWsLocalAddress(session, WsnResourceAddressFactorySpi.SCHEME_NAME, null);
                    }

                    final ResourceAddress wsLocalAddress = localAddress;

                    WsExtensionNegotiationResult extNegotiationResult =
                            negotiateWebSocketExtensions(
                                    wsLocalAddress, session, HEADER_SEC_WEBSOCKET_EXTENSION,
                                    clientRequestedExtensions, wsLocalAddress.getOption(EXTENSIONS));
                    if (extNegotiationResult.isFailure()) {
                        // This happens when the extension negotiation leads to
                        // a fatal failure; the session should be closed because
                        // the service REQUIRED some extension that the client
                        // did not request.
                    if (logger.isDebugEnabled()) {
                        if (logger.isDebugEnabled()) {
                            URI requestURI = session.getRequestURL();
                            logger.debug(String.format(
                                    "Rejected %s request for URI \"%s\" on session '%s': failed to negotiate client requested extensions '%s'",
                                    session.getMethod(), requestURI, session, clientRequestedExtensions));
                        }
                    }
                        session.setStatus(HttpStatus.CLIENT_NOT_FOUND);
                        session.setReason("WebSocket Extensions not found");
                        session.close(false);
                        return;
                    }

                    final String wsProtocol0 = wsProtocol;
                    final  ActiveWsExtensions wsExtensions0 = extNegotiationResult.getExtensions();

                    // Encoding.TEXT is default behavior
                    switch (encoding) {
                        case BASE64:
                        case BINARY:
                            session.addWriteHeader("X-Frame-Type", encoding.toString().toLowerCase());
                            break;
                    }

                    String key1 = session.getReadHeader(HEADER_WEBSOCKET_KEY1);
                    String key2 = session.getReadHeader(HEADER_WEBSOCKET_KEY2);
                    final IoBufferEx key3Duplicate = key3.duplicate();

                    try {
                    final IoBufferAllocatorEx<?> allocator = session.getBufferAllocator();
                    final IoBufferEx digest = allocator.wrap(WsUtils.computeHash(key1, key2, key3.buf()));

                        // do upgrade
                        UpgradeFuture upgrade = session.upgrade(ioBridgeHandler);
                        upgrade.addListener(new IoFutureListener<UpgradeFuture>() {
                            @Override
                            public void operationComplete(UpgradeFuture future) {
                                IoSession parent = future.getSession();
                                // write WebSocket digest response
                                parent.setAttribute("encoding", encoding);
                                parent.setAttribute(BridgeSession.NEXT_PROTOCOL_KEY, wsProtocol0);
                                if (!wsLocalAddress.getOption(CODEC_REQUIRED)) {
                                    ByteBuffer encoded = Encoding.UTF8.encode(digest.buf());
                                    IoBufferEx encodedEx = allocator.wrap(encoded, digest.flags());
                                    parent.write(encodedEx);
                                } else {
                                    parent.write(digest);
                                }

                                wsExtensions0.set(parent);
                                WEBSOCKET_LOCAL_ADDRESS.set(parent, wsLocalAddress);
                                DRAFT76_KEY3_BUFFER_KEY.set(parent, key3Duplicate);
                                parent.setAttribute(WEB_SOCKET_VERSION_KEY, WebSocketWireProtocol.HIXIE_76);
                                parent.setAttribute(LOCAL_ADDRESS_KEY, session.getLocalAddress());
                                parent.setAttribute(HttpAcceptor.SERVICE_REGISTRATION_KEY, session
                                        .getAttribute(HttpAcceptor.SERVICE_REGISTRATION_KEY));
                                parent.setAttribute(SUBJECT_TRANSFER_KEY, session.getSubject());
                                parent.setAttribute(HTTP_REQUEST_URI_KEY, session.getRequestURL());
                                parent.setAttribute(HttpSubjectSecurityFilter.LOGIN_CONTEXT_KEY,
                                                    session.getAttribute(HttpSubjectSecurityFilter.LOGIN_CONTEXT_KEY));


                            }
                        });
                        session.close(false);

                    } catch (Exception ex) {
                        session.close(true);
                    }
                    break;
                default:
                    session.setStatus(HttpStatus.CLIENT_METHOD_NOT_ALLOWED);
                    session.close(false);
                    break;
            }
        }
    };


    private WsnHttpBridgeHandler wsnHttpDraft75BridgeHandler = new WsnHttpBridgeHandler() {

           @Override
           protected void doSessionOpened(HttpAcceptSession session) throws Exception {
               super.doSessionOpened(session);

               if (!super.doUpgradeEligibilityChecks(session)) {
                   // prevent processing of WebSocket handshake for failed upgrades (e.g. path not found).
                   return;
               }

               String upgrade = session.getReadHeader(HEADER_UPGRADE);

               if ( upgrade != null &&
                       WEB_SOCKET.equalsIgnoreCase(upgrade) &&
                       session.getReadHeader(HEADER_WEBSOCKET_KEY) == null &&
                       session.getReadHeader(HEADER_WEBSOCKET_KEY1) == null) {
                   // The absence of any sec- headers indicates that the UA speaks
                   // WebSocket protocol version 75
                   doUpgrade75(session);
               } else {
                   doUpgradeFailure(session);
               }
           }


           private void doUpgrade75(final HttpAcceptSession session) throws URISyntaxException {
               switch (session.getMethod()) {
               case GET:
                   // suspend reads until after protocol switch complete
                   session.suspendRead();

                   // get key values from session
                   String origin = session.getReadHeader(HEADER_ORIGIN);
                   URI wsLocation = getWebSocketLocation(session, WebSocketWireProtocol.HIXIE_75);
                   final Encoding encoding = getWebSocketEncoding(session);

                   // build the HTML5 WebSocket handshake response
                   session.setStatus(HttpStatus.INFO_SWITCHING_PROTOCOLS);
                   session.setReason(REASON_WEB_SOCKET_HANDSHAKE);
                   session.addWriteHeader(HEADER_UPGRADE, WEB_SOCKET);
                   session.addWriteHeader(HEADER_CONNECTION, HEADER_UPGRADE);
                   session.addWriteHeader(HEADER_WEBSOCKET_ORIGIN, origin);
                   session.addWriteHeader(HEADER_WEBSOCKET_LOCATION, wsLocation.toASCIIString());


                   String clientWebSocketProtocolHeaderName = null;

                   List<String> clientRequestedWsProtocols = session.getReadHeaders(HEADER_SEC_WEBSOCKET_PROTOCOL);
                   if ( clientRequestedWsProtocols != null ) { clientWebSocketProtocolHeaderName = HEADER_SEC_WEBSOCKET_PROTOCOL; }

                   if ( clientRequestedWsProtocols == null ) {
                       clientRequestedWsProtocols = session.getReadHeaders(HEADER_X_WEBSOCKET_PROTOCOL);
                       if ( clientRequestedWsProtocols != null ) { clientWebSocketProtocolHeaderName = HEADER_X_WEBSOCKET_PROTOCOL; }
                   }

                   if ( clientRequestedWsProtocols == null ) {
                       clientRequestedWsProtocols = session.getReadHeaders(HEADER_WEBSOCKET_PROTOCOL);
                       if ( clientRequestedWsProtocols != null ) { clientWebSocketProtocolHeaderName = HEADER_WEBSOCKET_PROTOCOL; }

                   }

                   List<String> clientRequestedExtensions = session.getReadHeaders(HEADER_SEC_WEBSOCKET_EXTENSION);
                   if ( clientRequestedExtensions == null ) {
                           clientRequestedExtensions = session.getReadHeaders(HEADER_X_WEBSOCKET_EXTENSION);
                   }
                   if ( clientRequestedExtensions == null ) {
                       clientRequestedExtensions = session.getReadHeaders(WsExtensions.HEADER_WEBSOCKET_EXTENSIONS);
                   }


                   String wsProtocol;

                   try {
                       wsProtocol = negotiateWebSocketProtocol(session,
                               clientWebSocketProtocolHeaderName,
                               clientRequestedWsProtocols,
                               asList(SUPPORTED_PROTOCOLS.remove(session)));

                   } catch (WsHandshakeNegotiationException e) {
                       return;
                   }

                   // If configured with "Application xxx" challenge scheme, close the connection
                   if (!verifyApplicationChallengeSchemeSecurity(session)) {
                       return;
                   }

                   // find (based on this http session) the local address for the WS session
                   // we are about to upgrade to.
                   ResourceAddress localAddress = getWsLocalAddress(session, WsnResourceAddressFactorySpi.SCHEME_NAME, wsProtocol);

                   // fallback to null protocol as a workaround until we properly inject next protocol from service during bind
                   // This is safe as we guard this logic via negotiateWebSocketProtocol function
                   // If the client send any bogus protocol that is not in the list of supported protocols, we will fail fast before getting here
                   if (localAddress == null) {
                       wsProtocol = null;
                       localAddress = getWsLocalAddress(session, WsnResourceAddressFactorySpi.SCHEME_NAME, null);
                   }

                   final ResourceAddress wsLocalAddress = localAddress;

                   WsExtensionNegotiationResult extNegotiationResult =
                       negotiateWebSocketExtensions(
                               wsLocalAddress, session, HEADER_SEC_WEBSOCKET_EXTENSION,
                               clientRequestedExtensions, wsLocalAddress.getOption(EXTENSIONS));
                   if (extNegotiationResult.isFailure()) {
                       // This happens when the extension negotiation leads to
                       // a fatal failure; the session should be closed because
                       // the service REQUIRED some extension that the client
                       // did not request.
                    if (logger.isDebugEnabled()) {
                        if (logger.isDebugEnabled()) {
                            URI requestURI = session.getRequestURL();
                            logger.debug(String.format(
                                    "Rejected %s request for URI \"%s\" on session '%s': failed to negotiate client requested extensions '%s'",
                                    session.getMethod(), requestURI, session, clientRequestedExtensions));
                        }
                    }
                       session.setStatus(HttpStatus.CLIENT_NOT_FOUND);
                       session.setReason("WebSocket Extensions not found");
                       session.close(false);
                       return;
                   }

                   final String wsProtocol0 = wsProtocol;
                   final ActiveWsExtensions wsExtensions0 = extNegotiationResult.getExtensions();

                   // Encoding.TEXT is default behavior
                   switch (encoding) {
                   case BASE64:
                   case BINARY:
                       session.addWriteHeader("X-Frame-Type", encoding.toString().toLowerCase());
                       break;
                   }


                   // do upgrade
                   UpgradeFuture upgrade = session.upgrade(ioBridgeHandler);
                   upgrade.addListener(new IoFutureListener<UpgradeFuture>() {
                       @Override
                       public void operationComplete(UpgradeFuture future) {
                           IoSession parent = future.getSession();
                           parent.setAttribute("encoding", encoding);
                           parent.setAttribute(BridgeSession.NEXT_PROTOCOL_KEY, wsProtocol0);
                           wsExtensions0.set(parent);
                           parent.setAttribute(LOCAL_ADDRESS_KEY, session.getLocalAddress());
                           WEBSOCKET_LOCAL_ADDRESS.set(parent, wsLocalAddress);
                           parent.setAttribute(WEB_SOCKET_VERSION_KEY, WebSocketWireProtocol.HIXIE_75);
                           parent.setAttribute(HttpAcceptor.SERVICE_REGISTRATION_KEY, session
                                   .getAttribute(HttpAcceptor.SERVICE_REGISTRATION_KEY));
                           parent.setAttribute(SUBJECT_TRANSFER_KEY, session.getSubject());
                           parent.setAttribute(HTTP_REQUEST_URI_KEY, session.getRequestURL());
                           parent.setAttribute(HttpSubjectSecurityFilter.LOGIN_CONTEXT_KEY,
                                               session.getAttribute(HttpSubjectSecurityFilter.LOGIN_CONTEXT_KEY));


                       }
                   });
                   session.close(false);
                   break;
               default:
                   session.setStatus(HttpStatus.CLIENT_METHOD_NOT_ALLOWED);
                   session.close(false);
                   break;
               }
           }
       };


    static class HttpEmptyPacketWriterFilter extends IoFilterAdapter<WsnSession> {

        private final static Logger logger = LoggerFactory.getLogger("transport.http");

        static final HttpEmptyPacketWriterFilter INSTANCE = new HttpEmptyPacketWriterFilter();

        @Override
        protected void doFilterWrite(final NextFilter nextFilter,
                                     final WsnSession wsnSession,
                                     WriteRequest writeRequest) throws Exception {

            IoSession parent = wsnSession.getParent();
            Encoding encoding = (Encoding) parent.getAttribute("encoding");
            final WsMessage emptyWsMessage;

            final IoBufferAllocatorEx<?> allocator = wsnSession.getBufferAllocator();
            switch(encoding) {
                case TEXT:
                    emptyWsMessage = new WsTextMessage(allocator.wrap(allocator.allocate(0)));
                    break;
                default:
                    emptyWsMessage = new WsBinaryMessage(allocator.wrap(allocator.allocate(0)));
            }
            writeRequest.getFuture().addListener(new IoFutureListener<WriteFuture>() {
                @Override
                public void operationComplete(WriteFuture future) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("HttpEmptyPacketWriterFilter writing empty packet.");
                    }
                    wsnSession.getParent().write(emptyWsMessage);
                }
            });
            HttpResponseMessage message = (HttpResponseMessage) writeRequest.getMessage();
            if ( message.getStatus() != HttpStatus.CLIENT_UNAUTHORIZED) {
                wsnSession.getFilterChain().remove(HttpEmptyPacketWriterFilter.this);
            }
            nextFilter.filterWrite(wsnSession, writeRequest);


        }

        public static boolean writeExtraEmptyPacketRequired(IoSession session) {
            if ("x-kaazing-handshake".equals(BridgeSession.LOCAL_ADDRESS.get(session).getOption(NEXT_PROTOCOL))) {
                WsnSession wsnSession = (WsnSession) session;
                String query = wsnSession.getParentHttpRequestURI().getQuery();
                return (query != null) && (query.contains(".kl=Y")||query.contains(".kv=10.05"));
            }
            return false;
        }
    }

}