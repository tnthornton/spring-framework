/*
 * Copyright 2002-2014 the original author or authors.
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

package org.springframework.messaging.simp.stomp;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderInitializer;
import org.springframework.messaging.tcp.FixedIntervalReconnectStrategy;
import org.springframework.messaging.tcp.TcpConnection;
import org.springframework.messaging.tcp.TcpConnectionHandler;
import org.springframework.messaging.tcp.TcpOperations;
import org.springframework.messaging.tcp.reactor.Reactor11TcpClient;
import org.springframework.util.Assert;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.util.concurrent.ListenableFutureTask;

/**
 * A {@link org.springframework.messaging.MessageHandler} that handles messages by forwarding them to a STOMP broker.
 * For each new {@link SimpMessageType#CONNECT CONNECT} message, an independent TCP
 * connection to the broker is opened and used exclusively for all messages from the
 * client that originated the CONNECT message. Messages from the same client are
 * identified through the session id message header. Reversely, when the STOMP broker
 * sends messages back on the TCP connection, those messages are enriched with the session
 * id of the client and sent back downstream through the {@link MessageChannel} provided
 * to the constructor.
 *
 * <p>This class also automatically opens a default "system" TCP connection to the message
 * broker that is used for sending messages that originate from the server application (as
 * opposed to from a client). Such messages are are not associated with any client and
 * therefore do not have a session id header. The "system" connection is effectively
 * shared and cannot be used to receive messages. Several properties are provided to
 * configure the "system" connection including:
 * <ul>
 * 	<li>{@link #setSystemLogin(String)}</li>
 * 	<li>{@link #setSystemPasscode(String)}</li>
 * 	<li>{@link #setSystemHeartbeatSendInterval(long)}</li>
 * 	<li>{@link #setSystemHeartbeatReceiveInterval(long)}</li>
 * </ul>
 *
 * @author Rossen Stoyanchev
 * @author Andy Wilkinson
 * @since 4.0
 */
public class StompBrokerRelayMessageHandler extends AbstractBrokerMessageHandler {

	private static final byte[] EMPTY_PAYLOAD = new byte[0];

	private static final ListenableFutureTask<Void> EMPTY_TASK = new ListenableFutureTask<Void>(new VoidCallable());

	// STOMP recommends error of margin for receiving heartbeats
	private static final long HEARTBEAT_MULTIPLIER = 3;

	private static final Message<byte[]> HEARTBEAT_MESSAGE;

	static {
		EMPTY_TASK.run();
		StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
		HEARTBEAT_MESSAGE = MessageBuilder.createMessage(StompDecoder.HEARTBEAT_PAYLOAD, accessor.getMessageHeaders());
	}


	private final SubscribableChannel clientInboundChannel;

	private final MessageChannel clientOutboundChannel;

	private final SubscribableChannel brokerChannel;

	private String relayHost = "127.0.0.1";

	private int relayPort = 61613;

	private String clientLogin = "guest";

	private String clientPasscode = "guest";

	private String systemLogin = "guest";

	private String systemPasscode = "guest";

	private long systemHeartbeatSendInterval = 10000;

	private long systemHeartbeatReceiveInterval = 10000;

	private String virtualHost;

	private TcpOperations<byte[]> tcpClient;

	private MessageHeaderInitializer headerInitializer;

	private final Map<String, StompConnectionHandler> connectionHandlers =
			new ConcurrentHashMap<String, StompConnectionHandler>();


	/**
	 * Create a StompBrokerRelayMessageHandler instance with the given message channels
	 * and destination prefixes.
	 *
	 * @param clientInChannel the channel for receiving messages from clients (e.g. WebSocket clients)
	 * @param clientOutChannel the channel for sending messages to clients (e.g. WebSocket clients)
	 * @param brokerChannel the channel for the application to send messages to the broker
	 * @param destinationPrefixes the broker supported destination prefixes; destinations
	 * that do not match the given prefix are ignored.
	 */
	public StompBrokerRelayMessageHandler(SubscribableChannel clientInChannel, MessageChannel clientOutChannel,
			SubscribableChannel brokerChannel, Collection<String> destinationPrefixes) {

		super(destinationPrefixes);

		Assert.notNull(clientInChannel, "'clientInChannel' must not be null");
		Assert.notNull(clientOutChannel, "'clientOutChannel' must not be null");
		Assert.notNull(brokerChannel, "'brokerChannel' must not be null");

		this.clientInboundChannel = clientInChannel;
		this.clientOutboundChannel = clientOutChannel;
		this.brokerChannel = brokerChannel;
	}


	/**
	 * Set the STOMP message broker host.
	 */
	public void setRelayHost(String relayHost) {
		Assert.hasText(relayHost, "relayHost must not be empty");
		this.relayHost = relayHost;
	}

	/**
	 * @return the STOMP message broker host.
	 */
	public String getRelayHost() {
		return this.relayHost;
	}

	/**
	 * Set the STOMP message broker port.
	 */
	public void setRelayPort(int relayPort) {
		this.relayPort = relayPort;
	}

	/**
	 * @return the STOMP message broker port.
	 */
	public int getRelayPort() {
		return this.relayPort;
	}

	/**
	 * Set the interval, in milliseconds, at which the "system" connection will, in the
	 * absence of any other data being sent, send a heartbeat to the STOMP broker. A value
	 * of zero will prevent heartbeats from being sent to the broker.
	 * <p>The default value is 10000.
	 * <p>See class-level documentation for more information on the "system" connection.
	 */
	public void setSystemHeartbeatSendInterval(long systemHeartbeatSendInterval) {
		this.systemHeartbeatSendInterval = systemHeartbeatSendInterval;
	}

	/**
	 * @return The interval, in milliseconds, at which the "system" connection will
	 * send heartbeats to the STOMP broker.
	 */
	public long getSystemHeartbeatSendInterval() {
		return this.systemHeartbeatSendInterval;
	}

	/**
	 * Set the maximum interval, in milliseconds, at which the "system" connection
	 * expects, in the absence of any other data, to receive a heartbeat from the STOMP
	 * broker. A value of zero will configure the connection to expect not to receive
	 * heartbeats from the broker.
	 * <p>The default value is 10000.
	 * <p>See class-level documentation for more information on the "system" connection.
	 */
	public void setSystemHeartbeatReceiveInterval(long heartbeatReceiveInterval) {
		this.systemHeartbeatReceiveInterval = heartbeatReceiveInterval;
	}

	/**
	 * @return The interval, in milliseconds, at which the "system" connection expects
	 * to receive heartbeats from the STOMP broker.
	 */
	public long getSystemHeartbeatReceiveInterval() {
		return this.systemHeartbeatReceiveInterval;
	}

	/**
	 * Set the login to use when creating connections to the STOMP broker on
	 * behalf of connected clients.
	 * <p>
	 * By default this is set to "guest".
	 * @see #setSystemLogin(String)
	 */
	public void setClientLogin(String clientLogin) {
		Assert.hasText(clientLogin, "clientLogin must not be empty");
		this.clientLogin = clientLogin;
	}

	/**
	 * @return the configured login to use for connections to the STOMP broker
	 * on behalf of connected clients.
	 * @see #getSystemLogin()
	 */
	public String getClientLogin() {
		return this.clientLogin;
	}

	/**
	 * Set the clientPasscode to use to create connections to the STOMP broker on
	 * behalf of connected clients.
	 * <p>
	 * By default this is set to "guest".
	 * @see #setSystemPasscode(String)
	 */
	public void setClientPasscode(String clientPasscode) {
		Assert.hasText(clientPasscode, "clientPasscode must not be empty");
		this.clientPasscode = clientPasscode;
	}

	/**
	 * @return the configured passocde to use for connections to the STOMP broker on
	 * behalf of connected clients.
	 * @see #getSystemPasscode()
	 */
	public String getClientPasscode() {
		return this.clientPasscode;
	}

	/**
	 * Set the login for the shared "system" connection used to send messages to
	 * the STOMP broker from within the application, i.e. messages not associated
	 * with a specific client session (e.g. REST/HTTP request handling method).
	 * <p>
	 * By default this is set to "guest".
	 */
	public void setSystemLogin(String systemLogin) {
		Assert.hasText(systemLogin, "systemLogin must not be empty");
		this.systemLogin = systemLogin;
	}

	/**
	 * @return the login used for the shared "system" connection to the STOMP broker
	 */
	public String getSystemLogin() {
		return this.systemLogin;
	}

	/**
	 * Set the passcode for the shared "system" connection used to send messages to
	 * the STOMP broker from within the application, i.e. messages not associated
	 * with a specific client session (e.g. REST/HTTP request handling method).
	 * <p>
	 * By default this is set to "guest".
	 */
	public void setSystemPasscode(String systemPasscode) {
		this.systemPasscode = systemPasscode;
	}

	/**
	 * @return the passcode used for the shared "system" connection to the STOMP broker
	 */
	public String getSystemPasscode() {
		return this.systemPasscode;
	}

	/**
	 * Set the value of the "host" header to use in STOMP CONNECT frames. When this
	 * property is configured, a "host" header will be added to every STOMP frame sent to
	 * the STOMP broker. This may be useful for example in a cloud environment where the
	 * actual host to which the TCP connection is established is different from the host
	 * providing the cloud-based STOMP service.
	 * <p>By default this property is not set.
	 */
	public void setVirtualHost(String virtualHost) {
		this.virtualHost = virtualHost;
	}

	/**
	 * @return the configured virtual host value.
	 */
	public String getVirtualHost() {
		return this.virtualHost;
	}

	/**
	 * Configure a TCP client for managing TCP connections to the STOMP broker.
	 * By default {@link org.springframework.messaging.tcp.reactor.Reactor11TcpClient} is used.
	 */
	public void setTcpClient(TcpOperations<byte[]> tcpClient) {
		this.tcpClient = tcpClient;
	}

	/**
	 * Get the configured TCP client. Never {@code null} unless not configured
	 * invoked and this method is invoked before the handler is started and
	 * hence a default implementation initialized.
	 */
	public TcpOperations<byte[]> getTcpClient() {
		return this.tcpClient;
	}

	/**
	 * Configure a {@link MessageHeaderInitializer} to apply to the headers of all
	 * messages created through the {@code StompBrokerRelayMessageHandler} that
	 * are sent to the client outbound message channel.
	 *
	 * <p>By default this property is not set.
	 */
	public void setHeaderInitializer(MessageHeaderInitializer headerInitializer) {
		this.headerInitializer = headerInitializer;
	}

	/**
	 * @return the configured header initializer.
	 */
	public MessageHeaderInitializer getHeaderInitializer() {
		return this.headerInitializer;
	}


	@Override
	protected void startInternal() {

		this.clientInboundChannel.subscribe(this);
		this.brokerChannel.subscribe(this);

		if (this.tcpClient == null) {
			StompDecoder decoder = new StompDecoder();
			decoder.setHeaderInitializer(getHeaderInitializer());
			Reactor11StompCodec codec = new Reactor11StompCodec(new StompEncoder(), decoder);
			this.tcpClient = new StompTcpClientFactory().create(this.relayHost, this.relayPort, codec);
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Initializing \"system\" connection");
		}

		StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.CONNECT);
		headers.setAcceptVersion("1.1,1.2");
		headers.setLogin(this.systemLogin);
		headers.setPasscode(this.systemPasscode);
		headers.setHeartbeat(this.systemHeartbeatSendInterval, this.systemHeartbeatReceiveInterval);
		headers.setHost(getVirtualHost());

		SystemStompConnectionHandler handler = new SystemStompConnectionHandler(headers);
		this.connectionHandlers.put(handler.getSessionId(), handler);

		this.tcpClient.connect(handler, new FixedIntervalReconnectStrategy(5000));
	}

	@Override
	protected void stopInternal() {

		publishBrokerUnavailableEvent();

		this.clientInboundChannel.unsubscribe(this);
		this.brokerChannel.unsubscribe(this);

		try {
			this.tcpClient.shutdown().get(5000, TimeUnit.MILLISECONDS);
		}
		catch (Throwable t) {
			logger.error("Error while shutting down TCP client", t);
		}
	}

	@Override
	protected void handleMessageInternal(Message<?> message) {

		String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());

		if (!isBrokerAvailable()) {
			if (sessionId == null || SystemStompConnectionHandler.SESSION_ID.equals(sessionId)) {
				throw new MessageDeliveryException("Message broker is not active.");
			}
			SimpMessageType messageType = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
			if (messageType.equals(SimpMessageType.CONNECT) && logger.isErrorEnabled()) {
				logger.error("Message broker is not active. Ignoring: " + message);
			}
			else if (logger.isDebugEnabled()) {
				logger.debug("Message broker is not active. Ignoring: " + message);
			}
			return;
		}

		StompHeaderAccessor stompAccessor;
		StompCommand command;

		MessageHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, MessageHeaderAccessor.class);
		if (accessor == null) {
			logger.error("No header accessor, please use SimpMessagingTemplate. Ignoring: " + message);
			return;
		}
		else if (accessor instanceof StompHeaderAccessor) {
			stompAccessor = (StompHeaderAccessor) accessor;
			command = stompAccessor.getCommand();
		}
		else if (accessor instanceof SimpMessageHeaderAccessor) {
			stompAccessor = StompHeaderAccessor.wrap(message);
			command = stompAccessor.getCommand();
			if (command == null) {
				command = stompAccessor.updateStompCommandAsClientMessage();
			}
		}
		else {
			// Should not happen
			logger.error("Unexpected header accessor type: " + accessor + ". Ignoring: " + message);
			return;
		}

		if (sessionId == null) {
			if (!SimpMessageType.MESSAGE.equals(stompAccessor.getMessageType())) {
				logger.error("Only STOMP SEND frames supported on \"system\" connection. Ignoring: " + message);
				return;
			}
			sessionId = SystemStompConnectionHandler.SESSION_ID;
			stompAccessor.setSessionId(sessionId);
		}

		String destination = stompAccessor.getDestination();
		if ((command != null) && command.requiresDestination() && !checkDestinationPrefix(destination)) {
			if (logger.isTraceEnabled()) {
				logger.trace("Ignoring message to destination=" + destination);
			}
			return;
		}

		if (logger.isTraceEnabled()) {
			logger.trace("Processing message=" + message);
		}

		if (StompCommand.CONNECT.equals(command)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Processing CONNECT (total connected=" + this.connectionHandlers.size() + ")");
			}
			stompAccessor = (stompAccessor.isMutable() ? stompAccessor : StompHeaderAccessor.wrap(message));
			stompAccessor.setLogin(this.clientLogin);
			stompAccessor.setPasscode(this.clientPasscode);
			if (getVirtualHost() != null) {
				stompAccessor.setHost(getVirtualHost());
			}
			StompConnectionHandler handler = new StompConnectionHandler(sessionId, stompAccessor);
			this.connectionHandlers.put(sessionId, handler);
			this.tcpClient.connect(handler);
		}
		else if (StompCommand.DISCONNECT.equals(command)) {
			StompConnectionHandler handler = this.connectionHandlers.get(sessionId);
			if (handler == null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Connection already removed for sessionId '" + sessionId + "'");
				}
				return;
			}
			handler.forward(message, stompAccessor);
		}
		else {
			StompConnectionHandler handler = this.connectionHandlers.get(sessionId);
			if (handler == null) {
				if (logger.isWarnEnabled()) {
					logger.warn("Connection for sessionId '" + sessionId + "' not found. Ignoring message");
				}
				return;
			}
			handler.forward(message, stompAccessor);
		}
	}


	private class StompConnectionHandler implements TcpConnectionHandler<byte[]> {

		private final String sessionId;

		private final boolean isRemoteClientSession;

		private final StompHeaderAccessor connectHeaders;

		private volatile TcpConnection<byte[]> tcpConnection;

		private volatile boolean isStompConnected;


		private StompConnectionHandler(String sessionId, StompHeaderAccessor connectHeaders) {
			this(sessionId, connectHeaders, true);
		}

		private StompConnectionHandler(String sessionId, StompHeaderAccessor connectHeaders,
				boolean isRemoteClientSession) {

			Assert.notNull(sessionId, "SessionId must not be null");
			Assert.notNull(connectHeaders, "ConnectHeaders must not be null");

			this.sessionId = sessionId;
			this.connectHeaders = connectHeaders;
			this.isRemoteClientSession = isRemoteClientSession;
		}

		public String getSessionId() {
			return this.sessionId;
		}

		@Override
		public void afterConnected(TcpConnection<byte[]> connection) {
			if (logger.isDebugEnabled()) {
				logger.debug("Established TCP connection to broker in session '" + this.sessionId + "'");
			}
			this.tcpConnection = connection;
			connection.send(MessageBuilder.createMessage(EMPTY_PAYLOAD, this.connectHeaders.getMessageHeaders()));
		}

		@Override
		public void afterConnectFailure(Throwable ex) {
			handleTcpConnectionFailure("Failed to connect to message broker", ex);
		}

		/**
		 * Invoked when any TCP connectivity issue is detected, i.e. failure to establish
		 * the TCP connection, failure to send a message, missed heartbeat.
		 */
		protected void handleTcpConnectionFailure(String errorMessage, Throwable ex) {
			if (logger.isErrorEnabled()) {
				logger.error(errorMessage + ", sessionId '" + this.sessionId + "'", ex);
			}
			try {
				sendStompErrorToClient(errorMessage);
			}
			finally {
				try {
					clearConnection();
				}
				catch (Throwable t) {
					if (logger.isErrorEnabled()) {
						logger.error("Failed to close connection: " + t.getMessage());
					}
				}
			}
		}

		private void sendStompErrorToClient(String errorText) {
			if (this.isRemoteClientSession) {
				StompHeaderAccessor headerAccessor = StompHeaderAccessor.create(StompCommand.ERROR);
				if (getHeaderInitializer() != null) {
					getHeaderInitializer().initHeaders(headerAccessor);
				}
				headerAccessor.setSessionId(this.sessionId);
				headerAccessor.setMessage(errorText);
				Message<?> errorMessage = MessageBuilder.createMessage(EMPTY_PAYLOAD, headerAccessor.getMessageHeaders());
				sendMessageToClient(errorMessage);
			}
		}

		protected void sendMessageToClient(Message<?> message) {
			if (this.isRemoteClientSession) {
				StompBrokerRelayMessageHandler.this.clientOutboundChannel.send(message);
			}
		}

		@Override
		public void handleMessage(Message<byte[]> message) {

			StompHeaderAccessor headerAccessor =
					MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

			headerAccessor.setSessionId(this.sessionId);

			if (headerAccessor.isHeartbeat()) {
				logger.trace("Received broker heartbeat");
			}
			else if (logger.isDebugEnabled()) {
				logger.debug("Received message from broker in session '" + this.sessionId + "'");
			}
			else if (logger.isErrorEnabled() && StompCommand.ERROR == headerAccessor.getCommand()) {
				logger.error("Received STOMP ERROR: " + message);
			}

			if (StompCommand.CONNECTED == headerAccessor.getCommand()) {
				afterStompConnected(headerAccessor);
			}

			headerAccessor.setImmutable();
			sendMessageToClient(message);
		}

		/**
		 * Invoked after the STOMP CONNECTED frame is received. At this point the
		 * connection is ready for sending STOMP messages to the broker.
		 */
		protected void afterStompConnected(StompHeaderAccessor connectedHeaders) {
			this.isStompConnected = true;
			initHeartbeats(connectedHeaders);
		}

		private void initHeartbeats(StompHeaderAccessor connectedHeaders) {

			// Remote clients do their own heartbeat management
			if (this.isRemoteClientSession) {
				return;
			}

			long clientSendInterval = this.connectHeaders.getHeartbeat()[0];
			long clientReceiveInterval = this.connectHeaders.getHeartbeat()[1];

			long serverSendInterval = connectedHeaders.getHeartbeat()[0];
			long serverReceiveInterval = connectedHeaders.getHeartbeat()[1];

			if ((clientSendInterval > 0) && (serverReceiveInterval > 0)) {
				long interval = Math.max(clientSendInterval,  serverReceiveInterval);
				this.tcpConnection.onWriteInactivity(new Runnable() {
					@Override
					public void run() {
						TcpConnection<byte[]> conn = tcpConnection;
						if (conn != null) {
							conn.send(HEARTBEAT_MESSAGE).addCallback(
									new ListenableFutureCallback<Void>() {
										public void onFailure(Throwable t) {
											handleTcpConnectionFailure("Failed to send heartbeat", t);
										}
										public void onSuccess(Void result) {}
									});
						}
					}
				}, interval);
			}

			if (clientReceiveInterval > 0 && serverSendInterval > 0) {
				final long interval = Math.max(clientReceiveInterval, serverSendInterval) * HEARTBEAT_MULTIPLIER;
				this.tcpConnection.onReadInactivity(new Runnable() {
					@Override
					public void run() {
						handleTcpConnectionFailure("No hearbeat from broker for more than " +
								interval + "ms, closing connection", null);
					}
				},  interval);
			}
		}

		@Override
		public void handleFailure(Throwable ex) {
			if (this.tcpConnection == null) {
				return;
			}
			handleTcpConnectionFailure("Closing connection after TCP failure", ex);
		}

		@Override
		public void afterConnectionClosed() {
			if (this.tcpConnection == null) {
				return;
			}
			try {
				if (logger.isDebugEnabled()) {
					logger.debug("TCP connection to broker closed in session '" + this.sessionId + "'");
				}
				sendStompErrorToClient("Connection to broker closed");
			}
			finally {
				try {
					clearConnection();
				}
				catch (Throwable t) {
					// Ignore
				}
			}
		}

		/**
		 * Forward the given message to the STOMP broker.
		 *
		 * <p>The method checks whether we have an active TCP connection and have
		 * received the STOMP CONNECTED frame. For client messages this should be
		 * false only if we lose the TCP connection around the same time when a
		 * client message is being forwarded, so we simply log the ignored message
		 * at trace level. For messages from within the application being sent on
		 * the "system" connection an exception is raised so that components sending
		 * the message have a chance to handle it -- by default the broker message
		 * channel is synchronous.
		 *
		 * <p>Note that if messages arrive concurrently around the same time a TCP
		 * connection is lost, there is a brief period of time before the connection
		 * is reset when one or more messages may sneak through and an attempt made
		 * to forward them. Rather than synchronizing to guard against that, this
		 * method simply lets them try and fail. For client sessions that may
		 * result in an additional STOMP ERROR frame(s) being sent downstream but
		 * code handling that downstream should be idempotent in such cases.
		 *
		 * @param message the message to send, never {@code null}
		 * @return a future to wait for the result
		 */
		@SuppressWarnings("unchecked")
		public ListenableFuture<Void> forward(Message<?> message, final StompHeaderAccessor headerAccessor) {

			TcpConnection<byte[]> conn = this.tcpConnection;

			if (!this.isStompConnected) {
				if (this.isRemoteClientSession) {
					if (logger.isDebugEnabled()) {
						logger.debug("Ignoring client message received " + message +
								(conn != null ? "before CONNECTED frame" : "after TCP connection closed"));
					}
					return EMPTY_TASK;
				}
				else {
					throw new IllegalStateException("Cannot forward messages on system connection " +
							(conn != null ? "before STOMP CONNECTED frame" : "while inactive") +
							". Try listening for BrokerAvailabilityEvent ApplicationContext events.");

				}
			}

			if (logger.isDebugEnabled()) {
				if (headerAccessor.isHeartbeat()) {
					logger.trace("Forwarding heartbeat to broker");
				}
				else {
					logger.debug("Forwarding message to broker");
				}
			}

			if (headerAccessor.isMutable() && headerAccessor.isModified()) {
				message = MessageBuilder.createMessage(message.getPayload(), headerAccessor.getMessageHeaders());
			}

			ListenableFuture<Void> future = conn.send((Message<byte[]>) message);

			future.addCallback(new ListenableFutureCallback<Void>() {
				@Override
				public void onSuccess(Void result) {
					if (headerAccessor.getCommand() == StompCommand.DISCONNECT) {
						clearConnection();
					}
				}
				@Override
				public void onFailure(Throwable t) {
					if (tcpConnection == null) {
						// already reset
					}
					else {
						handleTcpConnectionFailure("Failed to send message " + headerAccessor, t);
					}
				}
			});

			return future;
		}

		/**
		 * Close the TCP connection to the broker and release the connection reference,
		 * Any exception arising from closing the connection is propagated. The caller
		 * must handle and log the exception accordingly.
		 *
		 * <p>If the connection belongs to a client session, the connection handler
		 * for the session (basically the current instance) is also released from the
		 * {@link org.springframework.messaging.simp.stomp.StompBrokerRelayMessageHandler}.
		 */
		public void clearConnection() {

			if (this.isRemoteClientSession) {
				if (logger.isDebugEnabled()) {
					logger.debug("Removing session '" + sessionId + "' (total remaining=" +
							(StompBrokerRelayMessageHandler.this.connectionHandlers.size() - 1) + ")");
				}
				StompBrokerRelayMessageHandler.this.connectionHandlers.remove(this.sessionId);
			}

			this.isStompConnected = false;

			TcpConnection<byte[]> conn = this.tcpConnection;
			this.tcpConnection = null;
			if (conn != null) {
				conn.close();
			}
		}

		@Override
		public String toString() {
			return "StompConnectionHandler{" + "sessionId=" + this.sessionId + "}";
		}
	}

	private class SystemStompConnectionHandler extends StompConnectionHandler {

		public static final String SESSION_ID = "stompRelaySystemSessionId";


		public SystemStompConnectionHandler(StompHeaderAccessor connectHeaders) {
			super(SESSION_ID, connectHeaders, false);
		}

		@Override
		protected void afterStompConnected(StompHeaderAccessor connectedHeaders) {
			super.afterStompConnected(connectedHeaders);
			publishBrokerAvailableEvent();
		}

		@Override
		protected void handleTcpConnectionFailure(String errorMessage, Throwable t) {
			super.handleTcpConnectionFailure(errorMessage, t);
			publishBrokerUnavailableEvent();
		}

		@Override
		public void afterConnectionClosed() {
			super.afterConnectionClosed();
			publishBrokerUnavailableEvent();
		}

		@Override
		public ListenableFuture<Void> forward(Message<?> message, StompHeaderAccessor headerAccessor) {
			try {
				ListenableFuture<Void> future = super.forward(message, headerAccessor);
				future.get();
				return future;
			}
			catch (Throwable t) {
				throw new MessageDeliveryException(message, t);
			}
		}
	}

	private static class StompTcpClientFactory {

		public TcpOperations<byte[]> create(String relayHost, int relayPort, Reactor11StompCodec codec) {
			return new Reactor11TcpClient<byte[]>(relayHost, relayPort, codec);
		}
	}

	private static class VoidCallable implements Callable<Void> {

		@Override
		public Void call() throws Exception {
			return null;
		}
	}

}
