/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
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

package net.openhft.chronicle.network.internal;

import net.openhft.chronicle.network.NioCallback;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Math.round;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Rob Austin.
 */
public abstract class AbstractNetwork implements Closeable {

    public static final int BITS_IN_A_BYTE = 8;


    private static final Logger LOG = LoggerFactory.getLogger(AbstractNetwork.class);

    // currently this is not safe to use as it wont work with the stateless client
    protected static boolean useJavaNIOSelectionKeys = Boolean.valueOf(System.getProperty("useJavaNIOSelectionKeys"));

    protected SelectedSelectionKeySet selectedKeys;

    protected final CloseablesManager closeables = new CloseablesManager();
    protected Selector selector;
    private final ExecutorService executorService;
    private volatile Thread lastThread;
    private Throwable startedHere;
    private Future<?> future;
    private final Queue<Runnable> pendingRegistrations = new ConcurrentLinkedQueue<Runnable>();
    protected final AtomicBoolean hasPendingRegistrations = new AtomicBoolean(false);

    @Nullable
    private final Throttler throttler;

    protected volatile boolean isClosed = false;


    public AbstractNetwork(String name, ThrottlingConfig throttlingConfig)
            throws IOException {
        executorService = Executors.newSingleThreadExecutor(
                new NamedThreadFactory(name, true) {
                    @Override
                    public Thread newThread(@NotNull Runnable r) {
                        return lastThread = super.newThread(r);
                    }
                });

        throttler = throttlingConfig.throttling(DAYS) > 0 ?
                new Throttler(selector,
                        throttlingConfig.bucketInterval(MILLISECONDS),
                        throttlingConfig.throttling(DAYS)) : null;

        startedHere = new Throwable("Started here");
    }

    public Selector openSelector(final CloseablesManager closeables) throws IOException {

        Selector result = Selector.open();
        closeables.add(result);

        if (!useJavaNIOSelectionKeys) {
            closeables.add(new Closeable() {
                               @Override
                               public void close() throws IOException {
                                   {
                                       SelectionKey[] keys = selectedKeys.flip();
                                       for (int i = 0; i < keys.length && keys[i] != null; i++) {
                                           keys[i] = null;
                                       }
                                   }
                                   {
                                       SelectionKey[] keys = selectedKeys.flip();
                                       for (int i = 0; i < keys.length && keys[i] != null; i++) {
                                           keys[i] = null;
                                       }
                                   }
                               }
                           }
            );
            return openSelector(result, selectedKeys);
        }

        return result;
    }

    protected static SocketChannel openSocketChannel(final CloseablesManager closeables) throws IOException {
        SocketChannel result = null;

        try {
            result = SocketChannel.open();
            result.socket().setTcpNoDelay(true);
        } finally {
            if (result != null)
                try {
                    closeables.add(result);
                } catch (IllegalStateException e) {
                    // already closed
                }
        }
        return result;
    }

    /**
     * this is similar to the code in Netty
     *
     * @param selector
     * @return
     */
    private Selector openSelector(@NotNull final Selector selector,
                                  @NotNull final SelectedSelectionKeySet selectedKeySet) {
        try {

            Class<?> selectorImplClass =
                    Class.forName("sun.nio.ch.SelectorImpl", false, getSystemClassLoader());

            // Ensure the current selector implementation is what we can instrument.
            if (!selectorImplClass.isAssignableFrom(selector.getClass())) {
                return selector;
            }

            Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
            Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

            selectedKeysField.setAccessible(true);
            publicSelectedKeysField.setAccessible(true);

            selectedKeysField.set(selector, selectedKeySet);
            publicSelectedKeysField.set(selector, selectedKeySet);

            //   logger.trace("Instrumented an optimized java.util.Set into: {}", selector);
        } catch (Exception e) {
            LOG.error("", e);
            //   logger.trace("Failed to instrument an optimized java.util.Set into: {}", selector, t);
        }

        return selector;
    }

    static ClassLoader getSystemClassLoader() {
        if (System.getSecurityManager() == null) {
            return ClassLoader.getSystemClassLoader();
        } else {
            return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                @Override
                public ClassLoader run() {
                    return ClassLoader.getSystemClassLoader();
                }
            });
        }
    }

    protected void addPendingRegistration(Runnable registration) {
        pendingRegistrations.add(registration);
        hasPendingRegistrations.set(true);
    }

    protected void registerPendingRegistrations() throws ClosedChannelException {
        for (Runnable runnable = pendingRegistrations.poll(); runnable != null;
             runnable = pendingRegistrations.poll()) {
            try {
                runnable.run();
            } catch (Exception e) {
                LOG.info("", e);
            }
        }
    }

    protected abstract void processEvent() throws IOException;

    protected final void start() {
        future = executorService.submit(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            processEvent();
                        } catch (Exception e) {
                            LOG.error("", e);
                        }
                    }
                }
        );
    }

    @Override
    public void close() {
        if (Thread.interrupted())
            LOG.warn("Already interrupted");
        long start = System.currentTimeMillis();
        closeResources();

        try {
            if (future != null)
                future.cancel(true);

            // we HAVE to be sure we have terminated before calling close() on the ReplicatedChronicleMap
            // if this thread is still running and you close() the ReplicatedChronicleMap without
            // fulling terminating this, you can cause the ReplicatedChronicleMap to attempt to
            // process data on a map that has been closed, which would result in a core dump.

            Thread thread = lastThread;
            if (thread != null && Thread.currentThread() != lastThread)
                for (int i = 0; i < 10; i++) {
                    if (thread.isAlive()) {
                        thread.join(1000);
                        dumpThreadStackTrace(start);
                    }
                }
        } catch (InterruptedException e) {
            dumpThreadStackTrace(start);
            LOG.error("", e);
            LOG.error("", startedHere);
        }
    }

    public void closeResources() {
        isClosed = true;
        executorService.shutdown();
        closeables.closeQuietly();

    }

    private void dumpThreadStackTrace(long start) {
        if (lastThread != null && lastThread.isAlive()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Replicator thread still running after ");
            sb.append((System.currentTimeMillis() - start) / 100 / 10.0);
            sb.append(" secs ");
            sb.append(lastThread);
            sb.append(" isAlive= ");
            sb.append(lastThread.isAlive());
            for (StackTraceElement ste : lastThread.getStackTrace()) {
                sb.append("\n\t").append(ste);
            }
            LOG.warn(sb.toString());
        }
    }

    protected void closeEarlyAndQuietly(SelectionKey key) {
        SelectableChannel channel = key.channel();

        Object attachment = key.attachment();

        if (attachment != null) {
            NioCallbackProvider attachment1 = (NioCallbackProvider) attachment;
            NioCallback userAttached = attachment1.getUserAttached();
            if (userAttached != null)
                userAttached.onEvent(null, null, NioCallback.EventType.CLOSED);

        }
        if (throttler != null)
            throttler.remove(channel);
        closeables.closeQuietly(channel);
    }

    protected void checkThrottleInterval() throws ClosedChannelException {
        if (throttler != null)
            throttler.checkThrottleInterval();
    }

    protected void contemplateThrottleWrites(int bytesJustWritten) throws ClosedChannelException {
        if (throttler != null)
            throttler.contemplateThrottleWrites(bytesJustWritten);
    }

    protected void throttle(SelectableChannel channel) {
        if (throttler != null)
            throttler.add(channel);
    }

    /**
     * throttles 'writes' to ensure the network is not swamped, this is achieved by periodically
     * de-registering the write selector during periods of high volume.
     */
    static class Throttler {

        private final Selector selector;
        private final Set<SelectableChannel> channels = new CopyOnWriteArraySet<SelectableChannel>();
        private final long throttleInterval;
        private final long maxBytesInInterval;

        private long lastTime = System.currentTimeMillis();
        private long bytesWritten;

        Throttler(@NotNull Selector selector,
                  long throttleIntervalInMillis,
                  long bitsPerDay) {
            this.selector = selector;
            this.throttleInterval = throttleIntervalInMillis;
            double bytesPerMs = ((double) bitsPerDay) / DAYS.toMillis(1) / BITS_IN_A_BYTE;
            this.maxBytesInInterval = round(bytesPerMs * throttleInterval);
        }

        public void add(SelectableChannel selectableChannel) {
            channels.add(selectableChannel);
        }

        public void remove(SelectableChannel socketChannel) {
            channels.remove(socketChannel);
        }

        /**
         * re register the 'write' on the selector if the throttleInterval has passed
         *
         * @throws java.nio.channels.ClosedChannelException
         */
        public void checkThrottleInterval() throws ClosedChannelException {
            final long time = System.currentTimeMillis();

            if (lastTime + throttleInterval >= time)
                return;

            lastTime = time;
            bytesWritten = 0;

            if (LOG.isDebugEnabled())
                LOG.debug("Restoring OP_WRITE on all channels");

            for (SelectableChannel selectableChannel : channels) {
                final SelectionKey selectionKey = selectableChannel.keyFor(selector);
                if (selectionKey != null)
                    selectionKey.interestOps(selectionKey.interestOps() | OP_WRITE);
            }
        }

        /**
         * checks the number of bytes written in this interval, if this number of bytes exceeds a
         * threshold, the selected will de-register the socket that is being written to, until the
         * interval is finished.
         *
         * @throws java.nio.channels.ClosedChannelException
         */
        public void contemplateThrottleWrites(int bytesJustWritten)
                throws ClosedChannelException {
            bytesWritten += bytesJustWritten;
            if (bytesWritten > maxBytesInInterval) {
                for (SelectableChannel channel : channels) {
                    final SelectionKey selectionKey = channel.keyFor(selector);
                    if (selectionKey != null) {
                        selectionKey.interestOps(selectionKey.interestOps() & ~OP_WRITE);
                    }
                    if (LOG.isDebugEnabled())
                        LOG.debug("Throttling UDP writes");
                }
            }
        }
    }

    /**
     * details about the socket connection
     */
    public static class Details {

        private final InetSocketAddress address;

        public Details(@NotNull final InetSocketAddress address) {
            this.address = address;

        }

        public InetSocketAddress address() {
            return address;
        }

        @Override
        public String toString() {
            return "Details{" +
                    "address=" + address +
                    '}';
        }
    }


    protected abstract class AbstractConnector {

        private final String name;
        private int connectionAttempts = 0;
        private volatile SelectableChannel socketChannel;

        public AbstractConnector(String name) {
            this.name = name;
        }

        protected abstract SelectableChannel doConnect() throws IOException, InterruptedException;

        /**
         * connects or reconnects, but first waits a period of time proportional to the {@code
         * connectionAttempts}
         */
        public final void connectLater() {
            if (socketChannel != null) {
                closeables.closeQuietly(socketChannel);
                socketChannel = null;
            }

            final long reconnectionInterval = connectionAttempts * 100;
            if (connectionAttempts < 5)
                connectionAttempts++;
            doConnect(reconnectionInterval);
        }

        /**
         * connects or reconnects immediately
         */
        public void connect() {
            doConnect(0);
        }

        /**
         * @param reconnectionInterval the period to wait before connecting
         */
        private void doConnect(final long reconnectionInterval) {
            final Thread thread = new Thread(new Runnable() {
                public void run() {
                    SelectableChannel socketChannel = null;
                    try {
                        if (reconnectionInterval > 0)
                            Thread.sleep(reconnectionInterval);

                        socketChannel = doConnect();

                        try {
                            closeables.add(socketChannel);
                        } catch (IllegalStateException e) {
                            // close could have be called from another thread, while we were in Thread.sleep()
                            // which would cause a IllegalStateException
                            closeQuietly(socketChannel);
                            return;
                        }

                        AbstractConnector.this.socketChannel = socketChannel;
                    } catch (Exception e) {
                        closeQuietly(socketChannel);
                        LOG.debug("", e);
                    }
                }

                private void closeQuietly(SelectableChannel socketChannel) {
                    if (socketChannel == null)
                        return;
                    try {
                        socketChannel.close();
                    } catch (Exception e1) {
                        // do nothing
                    }
                }
            });

            thread.setName(name);
            thread.setDaemon(true);
            thread.start();
        }

        public void setSuccessfullyConnected() {
            connectionAttempts = 0;
        }
    }

}