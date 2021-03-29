/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.agent.protocol.io;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.openremote.container.Container;
import org.openremote.model.util.Retry;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.syslog.SyslogCategory;

import javax.validation.constraints.NotNull;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

/**
 * This is a {@link IoClient} implementation for netty.
 * <p>
 * It uses the netty component for managing the connection.
 * <p>
 * Concrete implementations are responsible for providing the {@link ChannelOutboundHandler}s and
 * {@link ChannelInboundHandler}s required to encode/decode the specific type of messages sent/received to/from this
 * client. For {@link IoClient}s that require some specific encoders/decoders irrespective of the message type (e.g.
 * {@link org.openremote.agent.protocol.udp.UdpIoClient} and {@link org.openremote.agent.protocol.websocket.WebsocketIoClient})
 * the {@link #addEncodersDecoders} method can be overridden so the {@link IoClient} can exactly control
 * the order and types of encoders/decoders in the pipeline.
 * <p>
 * Users of the {@link IoClient} can add encoders/decoders for their specific message type using the
 * {@link #setEncoderDecoderProvider}, each {@link IoClient} should make it clear to users what the required output
 * type(s) are for the last encoder and decoder that a user may wish to add, if adding encoders/decoders is not
 * supported then {@link IoClient}s should override this setter and throw an {@link UnsupportedOperationException}.
 * <p>
 * Typically for outgoing messages a single {@link ChannelOutboundHandler} is sufficient and the
 * {@link MessageToByteEncoder} can be used as a base.
 * <p>
 * For inbound messages; the decoders required are very much dependent on the message type and {@link IoClient} type,
 * any number of standard netty {@link ChannelInboundHandler}s can be used but the last handler should build messages
 * of type &lt;T&gt; and pass them to the {@link #onMessageReceived} method of the client; the {@link ByteToMessageDecoder}
 * or {@link MessageToByteEncoder} can be used for this purpose, which one to use will depend on the previous
 * {@link ChannelInboundHandler}s in the pipeline.
 * <p>
 * <b>NOTE: Care must be taken when working with Netty {@link ByteBuf} as Netty uses reference counting to manage their
 * lifecycle. Refer to the Netty documentation for more information.</b>
 */
public abstract class AbstractNettyIoClient<T, U extends SocketAddress> implements IoClient<T> {

    /**
     * This is intended to be used at the end of a decoder chain where the previous decoder outputs a {@link ByteBuf};
     * the provided {@link #decoder} should extract the messages of type &lt;T&gt; from the {@link ByteBuf} and add them
     * to the {@link List} and they will then be passed to the {@link IoClient}.
     */
    public static class ByteToMessageDecoder<T> extends io.netty.handler.codec.ByteToMessageDecoder {
        protected List<T> messages = new ArrayList<>(1);
        protected AbstractNettyIoClient<T, ?> client;
        protected BiConsumer<ByteBuf, List<T>> decoder;

        public ByteToMessageDecoder(AbstractNettyIoClient<T, ?> client, @NotNull BiConsumer<ByteBuf, List<T>> decoder) {
            this.client = client;
            this.decoder = decoder;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            decoder.accept(in, messages);

            if (!messages.isEmpty()) {
                // Don't pass them along the channel pipeline just consume them
                messages.forEach(m -> client.onMessageReceived(m));
                messages.clear();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            client.onDecodeException(ctx, cause);
        }
    }

    /**
     * This is intended to be used at the end of a decoder chain where the previous decoder outputs messages of type &lt;T&gt;.
     */
    public static class MessageToMessageDecoder<T> extends SimpleChannelInboundHandler<T> {
        protected AbstractNettyIoClient<T,?> client;

        public MessageToMessageDecoder(Class<? extends T> typeClazz, AbstractNettyIoClient<T, ?> client) {
            super(typeClazz);
            this.client = client;
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, T msg) throws Exception {
            client.onMessageReceived(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            client.onDecodeException(ctx, cause);
        }
    }

    /**
     * Concrete implementations must provide an encoder to fill the {@link ByteBuf} ready to be sent `over the wire`.
     */
    public static class MessageToByteEncoder<T> extends io.netty.handler.codec.MessageToByteEncoder<T> {
        protected AbstractNettyIoClient<T, ?> client;
        protected BiConsumer<T, ByteBuf> encoder;

        public MessageToByteEncoder(Class<? extends T> typeClazz, AbstractNettyIoClient<T, ?> client, BiConsumer<T, ByteBuf> encoder) {
            super(typeClazz);
            this.client = client;
            this.encoder = encoder;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, T msg, ByteBuf out) {
            encoder.accept(msg, out);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            super.exceptionCaught(ctx, cause);
            client.onEncodeException(ctx, cause);
        }
    }

    private static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, AbstractNettyIoClient.class);
    protected final static long RECONNECT_DELAY_INITIAL_MILLIS = 1000L;
    protected final static long RECONNECT_DELAY_MAX_MILLIS = 5*60000L;
    protected final static long RECONNECT_DELAY_JITTER_MILLIS = 10000L;
    protected final List<Consumer<T>> messageConsumers = new ArrayList<>();
    protected final List<Consumer<ConnectionStatus>> connectionStatusConsumers = new ArrayList<>();
    protected ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;
    protected ChannelFuture channelStartFuture;
    protected Channel channel;
    protected Bootstrap bootstrap;
    protected EventLoopGroup workerGroup;
    protected ScheduledExecutorService executorService;
    protected Retry connectRetry;
    protected boolean permanentError;
    protected Supplier<ChannelHandler[]> encoderDecoderProvider;

    protected AbstractNettyIoClient() {
        this.executorService = Container.EXECUTOR_SERVICE;
    }

    @Override
    public void setEncoderDecoderProvider(Supplier<ChannelHandler[]> encoderDecoderProvider) throws UnsupportedOperationException {
        this.encoderDecoderProvider = encoderDecoderProvider;
    }

    protected abstract Class<? extends Channel> getChannelClass();

    protected abstract EventLoopGroup getWorkerGroup();

    protected abstract ChannelFuture startChannel();

    protected void configureChannel() {
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
    }

    @Override
    public void connect() {
        synchronized (this) {
            if (permanentError) {
                LOG.info("Unable to connect as permanent error has been set");
                return;
            }

            if (connectionStatus != ConnectionStatus.DISCONNECTED) {
                LOG.finer("Must be disconnected before calling connect: " + getClientUri());
                return;
            }

            LOG.fine("Connecting IO Client: " + getClientUri());
            onConnectionStatusChanged(ConnectionStatus.CONNECTING);
        }

        scheduleDoConnect();
    }

    protected void scheduleDoConnect() {
        connectRetry = new Retry("Connect to '" + getClientUri() + "'", executorService, () -> {
            boolean success = false;
            try {
                success = doConnect().get();
                if (success) {
                    onConnectionStatusChanged(ConnectionStatus.CONNECTED);
                } else {
                    // Cleanup resources ready for next connection attempt
                    doDisconnect();
                }
            } catch (Exception e) {
                LOG.log(Level.INFO, "An exception was thrown during connection attempt", e);
            }
            return success;
        })
            .setSuccessCallback(() -> this.connectRetry = null)
            .setLogger(LOG)
            .setInitialDelay(RECONNECT_DELAY_INITIAL_MILLIS)
            .setMaxDelay(RECONNECT_DELAY_MAX_MILLIS)
            .setJitterMargin(RECONNECT_DELAY_JITTER_MILLIS);

        connectRetry.run();
    }

    protected Future<Boolean> doConnect() {

        LOG.info("Establishing connection: " + getClientUri());

        if (workerGroup == null) {
            // TODO: In Netty 5 you can pass in an executor service; can only pass in thread factory for now
            workerGroup = getWorkerGroup();
        }

        bootstrap = new Bootstrap();
        bootstrap.channel(getChannelClass());
        configureChannel();
        bootstrap.group(workerGroup);

        bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(Channel channel) {
                AbstractNettyIoClient.this.initChannel(channel);
            }
        });

        // Start and store the channel
        channelStartFuture = startChannel();
        channel = channelStartFuture.channel();

        // Create connected future
        CompletableFuture<Boolean> connectedFuture = createConnectedFuture();

        // Add closed callback
        channel.closeFuture().addListener(future -> {
            synchronized (this) {
                if (connectionStatus == ConnectionStatus.CONNECTED) {
                    LOG.info("Connection closed un-expectedly: " + getClientUri());
                    onConnectionStatusChanged(ConnectionStatus.CONNECTING);
                    doDisconnect();
                    scheduleDoConnect();
                }
            }
        });

        return connectedFuture;
    }

    protected CompletableFuture<Boolean> createConnectedFuture() {
        CompletableFuture<Boolean> connectedFuture = new CompletableFuture<>();
        channelStartFuture.addListener(future -> onConnectedFutureComplete(future, connectedFuture));
        return connectedFuture;
    }

    protected void onConnectedFutureComplete(io.netty.util.concurrent.Future<? super Void> future, CompletableFuture<Boolean> connectedFuture) {
        synchronized (this) {

            if (connectionStatus != ConnectionStatus.CONNECTING) {
                return;
            }

            if (future.isSuccess()) {
                LOG.log(Level.INFO, "Connected: " + getClientUri());
            } else if (future.cause() != null) {
                LOG.log(Level.WARNING, "Connection error: " + getClientUri(), future.cause());
            }

            connectedFuture.complete(future.isSuccess());
        }
    }

    @Override
    public void disconnect() {
        synchronized (this) {
            if (connectionStatus == ConnectionStatus.DISCONNECTED) {
                LOG.finest("Already disconnected: " + getClientUri());
                return;
            }

            LOG.finest("Disconnecting IO client: " + getClientUri());
            onConnectionStatusChanged(ConnectionStatus.DISCONNECTED);
        }

        if (connectRetry != null) {
            connectRetry.cancel(true);
            connectRetry = null;
        }

        doDisconnect();
    }

    protected void doDisconnect() {
        try {
            if (channelStartFuture != null) {
                channelStartFuture.cancel(true);
                channelStartFuture = null;
            }

            // Close the channel
            if (channel != null) {
                channel.disconnect();
                channel.close();
                channel = null;
            }
        } finally {
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
                workerGroup = null;
            }
        }
    }

    @Override
    public void sendMessage(T message) {
        if (connectionStatus != ConnectionStatus.CONNECTED) {
            return;
        }

        try {
            // Don't block here as it can cause deadlock
            channel.writeAndFlush(message);
            LOG.finest("Message sent to server: " + getClientUri());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Message send failed: " + getClientUri(), e);
        }
    }

    @Override
    public ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    @Override
    public void addConnectionStatusConsumer(Consumer<ConnectionStatus> connectionStatusConsumer) {
        synchronized (connectionStatusConsumers) {
            if (!connectionStatusConsumers.contains(connectionStatusConsumer)) {
                connectionStatusConsumers.add(connectionStatusConsumer);
            }
        }
    }

    @Override
    public void removeConnectionStatusConsumer(Consumer<ConnectionStatus> connectionStatusConsumer) {
        synchronized (connectionStatusConsumers) {
            connectionStatusConsumers.remove(connectionStatusConsumer);
        }
    }

    @Override
    public void removeAllConnectionStatusConsumers() {
        synchronized (connectionStatusConsumers) {
            connectionStatusConsumers.clear();
        }
    }

    @Override
    public void addMessageConsumer(Consumer<T> messageConsumer) {
        synchronized (messageConsumers) {
            if (!messageConsumers.contains(messageConsumer)) {
                messageConsumers.add(messageConsumer);
            }
        }
    }

    @Override
    public void removeMessageConsumer(Consumer<T> messageConsumer) {
        synchronized (messageConsumers) {
            messageConsumers.remove(messageConsumer);
        }
    }

    @Override
    public void removeAllMessageConsumers() {
        synchronized (messageConsumers) {
            messageConsumers.clear();
        }
    }

    /**
     * Inserts the decoders and encoders into the channel pipeline
     */
    protected void initChannel(Channel channel) {
        // Below is un-necessary as channel listener handles this
        addEncodersDecoders(channel);
    }

    protected void addEncodersDecoders(Channel channel) {
        if (encoderDecoderProvider != null) {
            ChannelHandler[] handlers = encoderDecoderProvider.get();
            if (handlers != null) {
                Arrays.stream(handlers).forEach(
                    handler -> channel.pipeline().addLast(handler)
                );
            }
        }
    }

    protected void onMessageReceived(T message) {
        if (connectionStatus != ConnectionStatus.CONNECTED) {
            return;
        }

        LOG.finest("Message received notifying consumers: " + getClientUri());
        messageConsumers.forEach(consumer -> {
            try {
                consumer.accept(message);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Exception occurred in message handler: " + getClientUri(), e);
            }
        });
    }

    protected void onDecodeException(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.SEVERE, "Exception occurred on in-bound message: " + getClientUri(), cause);
    }

    protected void onEncodeException(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.SEVERE, "Exception occurred on out-bound message: " + getClientUri(), cause);
    }

    protected void onConnectionStatusChanged(ConnectionStatus connectionStatus) {
        this.connectionStatus = connectionStatus;

        executorService.submit(() -> {
            synchronized (connectionStatusConsumers) {
                connectionStatusConsumers.forEach(
                    consumer -> {
                        try {
                            consumer.accept(connectionStatus);
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, "Connection status change handler threw an exception: " + getClientUri(), e);
                        }
                    });
            }
        });
    }

    protected void setPermanentError(String message) {

        synchronized (this) {
            if (permanentError) {
                return;
            }

            LOG.info("An unrecoverable error has occurred with client '" + getClientUri() + "' is no longer usable: " + message);
            this.permanentError = true;
        }
        disconnect();
    }

    @Override
    public String toString() {
        return getClientUri();
    }
}
