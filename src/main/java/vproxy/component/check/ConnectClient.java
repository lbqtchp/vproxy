package vproxy.component.check;

import vfd.DatagramFD;
import vfd.FDProvider;
import vproxy.app.Config;
import vproxy.connection.*;
import vproxy.dns.DNSClient;
import vproxy.http.HttpRespParser;
import vproxy.processor.http1.entity.Header;
import vproxy.processor.http1.entity.Request;
import vproxy.processor.http1.entity.Response;
import vproxy.selector.TimerEvent;
import vproxy.util.*;
import vproxy.util.nio.ByteArrayChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

// connect to target address and send/receive some data then close the connection
// it's useful when running health check
public class ConnectClient {
    abstract class BaseHealthCheckConnectableConnectionHandler implements ConnectableConnectionHandler {
        private final Callback<Void, IOException> callback;
        private final TimerEvent timeoutEvent;
        private boolean done = false;

        BaseHealthCheckConnectableConnectionHandler(Callback<Void, IOException> callback, TimerEvent timeoutEvent) {
            this.callback = callback;
            this.timeoutEvent = timeoutEvent;
        }

        @Override
        public final void exception(ConnectionHandlerContext ctx, IOException err) {
            cancelTimers(); // cancel timer if possible
            ctx.connection.close(true); // close the connection with reset

            assert Logger.lowLevelDebug("exception when doing health check, conn = " + ctx.connection + ", err = " + err);

            if (!callback.isCalled() /*already called by timer*/ && !stopped) callback.failed(err);
        }

        @Override
        public final void remoteClosed(ConnectionHandlerContext ctx) {
            ctx.connection.close(true);
            closed(ctx);
        }

        @Override
        public final void closed(ConnectionHandlerContext ctx) {
            // check whether is done
            if (done)
                return;
            // "not done and closed" means the remote closed the connection
            // which means: remote is listening, but something went wrong
            // maybe an lb with no healthy backend
            // so we consider it an unhealthy check
            cancelTimers();
            closeAndCallFail(ctx, "remote closed");
        }

        @Override
        public final void removed(ConnectionHandlerContext ctx) {
            ctx.connection.close(true);
        }

        protected void cancelTimers() {
            timeoutEvent.cancel(); // cancel the connection timeout event
        }

        protected final void closeAndCallFail(ConnectionHandlerContext ctx, String err) {
            ctx.connection.close();
            if (!callback.isCalled() /*already called by timer*/ && !stopped)
                callback.failed(new IOException(err));
        }

        protected final void closeAndCallSucc(ConnectionHandlerContext ctx) {
            done = true;
            ctx.connection.close(true);
            if (!callback.isCalled() /*already called by timer*/ && !stopped) callback.succeeded(null);
        }
    }

    class ConnectConnectableConnectionHandler extends BaseHealthCheckConnectableConnectionHandler {
        private TimerEvent delayTimeoutEvent;

        ConnectConnectableConnectionHandler(Callback<Void, IOException> callback, TimerEvent connectionTimeoutEvent) {
            super(callback, connectionTimeoutEvent);
        }

        @Override
        public void connected(ConnectableConnectionHandlerContext ctx) {
            cancelTimers(); // cancel timer if possible
            if (checkProtocol == CheckProtocol.tcp) {
                // for non-delay tcp, directly close the connection and return success
                closeAndCallSucc(ctx);
            } else {
                assert checkProtocol == CheckProtocol.tcpDelay;
                // for tcp-delay, wait for a while then close connection
                delayTimeoutEvent = eventLoop.getSelectorEventLoop().delay(
                    50, // fix the delay
                    () -> {
                        // the connection is ok after the timeout
                        // so callback
                        closeAndCallSucc(ctx);
                    });
            }
        }

        @Override
        public void readable(ConnectionHandlerContext ctx) {
            // if readable, the remote endpoint is absolutely alive
            // we close the connection and call callback
            cancelTimers();
            closeAndCallSucc(ctx);
        }

        @Override
        public void writable(ConnectionHandlerContext ctx) {
            // will never fire
        }

        @Override
        protected void cancelTimers() {
            super.cancelTimers();
            if (delayTimeoutEvent != null) {
                delayTimeoutEvent.cancel();
            }
        }
    }

    class HttpHCConnectableConnectionHandler extends BaseHealthCheckConnectableConnectionHandler {
        HttpHCConnectableConnectionHandler(Callback<Void, IOException> callback, TimerEvent timeoutEvent) {
            super(callback, timeoutEvent);
        }

        @Override
        public void connected(ConnectableConnectionHandlerContext ctx) {
            // ignore event, data will flush
        }

        @Override
        public void readable(ConnectionHandlerContext ctx) {
            RingBuffer inBuf = ctx.connection.getInBuffer();
            HttpRespParser parser = new HttpRespParser(false);
            int res = parser.feed(inBuf);
            if (res == -1) {
                String err = parser.getErrorMessage();
                if (err == null) {
                    // input data not fulfilled yet
                    // wait for more data
                    return;
                }
                // got error
                cancelTimers();
                closeAndCallFail(ctx, "response not http: " + err);
                return;
            }
            Response resp = parser.getResult();
            assert resp != null;
            int status = resp.statusCode;
            cancelTimers();
            if (status < 100 || status >= 600) {
                closeAndCallFail(ctx, "unexpected http response status " + status);
                return;
            }
            String expectedStatus = annotatedHcConfig.getHttpStatus();
            if (status < 200) {
                if (expectedStatus.contains("1xx")) {
                    closeAndCallSucc(ctx);
                    return;
                }
            } else if (status < 300) {
                if (expectedStatus.contains("2xx")) {
                    closeAndCallSucc(ctx);
                    return;
                }
            } else if (status < 400) {
                if (expectedStatus.contains("3xx")) {
                    closeAndCallSucc(ctx);
                    return;
                }
            } else if (status < 500) {
                if (expectedStatus.contains("4xx")) {
                    closeAndCallSucc(ctx);
                }
            } else {
                if (expectedStatus.contains("5xx")) {
                    closeAndCallSucc(ctx);
                }
            }
            closeAndCallFail(ctx, "unexpected http response status " + status);
        }

        @Override
        public void writable(ConnectionHandlerContext ctx) {
            // ignore event, data will flush
        }
    }

    public final NetEventLoop eventLoop;
    public final InetSocketAddress remote;
    public final CheckProtocol checkProtocol;
    public final int timeout;
    public final AnnotatedHcConfig annotatedHcConfig;
    private boolean stopped = false;
    private final Consumer<Callback<Void, IOException>> handleFunc;

    private DatagramFD dnsSocket = null;
    private DNSClient dnsClient = null;

    public ConnectClient(NetEventLoop eventLoop,
                         InetSocketAddress remote,
                         CheckProtocol checkProtocol,
                         int timeout,
                         AnnotatedHcConfig annotatedHcConfig) {
        this.eventLoop = eventLoop;
        this.remote = remote;
        this.checkProtocol = checkProtocol;
        this.timeout = timeout;
        this.annotatedHcConfig = annotatedHcConfig;

        switch (this.checkProtocol) {
            case none:
                handleFunc = this::handleNone;
                break;
            case domainSystem:
                handleFunc = this::handleDns;
                break;
            case http:
                handleFunc = this::handleHttp;
                break;
            default:
                assert this.checkProtocol == CheckProtocol.tcp || this.checkProtocol == CheckProtocol.tcpDelay;
                handleFunc = this::handleTcp;
                break;
        }
    }

    private void handleNone(Callback<Void, IOException> cb) {
        assert Logger.lowLevelDebug("checkProtocol == none, so directly return success");
        cb.succeeded(null);
    }

    private void handleTcp(Callback<Void, IOException> cb) {
        // connect to remote
        ConnectableConnection conn;
        try {
            conn = ConnectableConnection.create(remote,
                // we do not use the timeout in connection opts
                // because we need to divide the timeouts into several steps
                ConnectionOpts.getDefault(),
                // set input buffer to 1 to be able to read things
                // output buffer is not useful at all here
                RingBuffer.allocate(1), RingBuffer.EMPTY_BUFFER);
        } catch (IOException e) {
            if (!stopped) cb.failed(e);
            return;
        }
        // create a timer handling the connecting timeout
        TimerEvent timer = eventLoop.getSelectorEventLoop().delay(timeout, () -> {
            assert Logger.lowLevelDebug("timeout when doing health check " + conn);
            conn.close(true);
            if (!cb.isCalled() /*called by connection*/ && !stopped) cb.failed(new InterruptedByTimeoutException());
        });
        try {
            eventLoop.addConnectableConnection(conn, null, new ConnectConnectableConnectionHandler(cb, timer));
        } catch (IOException e) {
            if (!stopped) cb.failed(e);
            // exception occurred, so ignore timeout
            timer.cancel();
        }
    }

    private void handleDns(Callback<Void, IOException> cb) {
        if (dnsSocket == null) {
            dnsSocket = DNSClient.getSocketForDNS();
        }
        if (dnsClient == null) {
            try {
                dnsClient = new DNSClient(eventLoop.getSelectorEventLoop(), dnsSocket, Collections.singletonList(remote), timeout, 1);
            } catch (IOException e) {
                Logger.shouldNotHappen("start dns client failed", e);
                if (!stopped) cb.failed(e);
                return;
            }
        }
        dnsClient.resolveIPv4(Config.domainWhichShouldResolve, new Callback<>() {
            @Override
            protected void onSucceeded(List<InetAddress> value) {
                cb.succeeded(null);
            }

            @Override
            protected void onFailed(UnknownHostException err) {
                // we expect the address to be resolved
                if (!stopped) cb.failed(err);
            }
        });
    }

    private void handleHttp(Callback<Void, IOException> cb) {
        // http request
        Request req = new Request();
        req.method = annotatedHcConfig.getHttpMethod();
        req.uri = annotatedHcConfig.getHttpUrl();
        req.version = "HTTP/1.1";
        req.headers = new ArrayList<>(1);
        String host = annotatedHcConfig.getHttpHost();
        //noinspection ReplaceNullCheck
        if (host == null) {
            req.headers.add(new Header("Host", Utils.l4addrStr(remote)));
        } else {
            req.headers.add(new Header("Host", host));
        }
        ByteArray bytes = req.toByteArray();
        RingBuffer sendBuffer = RingBuffer.allocate(bytes.length());
        sendBuffer.storeBytesFrom(ByteArrayChannel.fromFull(bytes));
        // expecting response HTTP/1.? ??? ......
        // connect to remote
        ConnectableConnection conn;
        try {
            conn = ConnectableConnection.create(remote, ConnectionOpts.getDefault(),
                RingBuffer.allocate(128), sendBuffer);
        } catch (IOException e) {
            if (!stopped) cb.failed(e);
            return;
        }
        // create a timer handling the connecting timeout
        TimerEvent timer = eventLoop.getSelectorEventLoop().delay(timeout, () -> {
            assert Logger.lowLevelDebug("timeout when doing http health check " + conn);
            conn.close(true);
            if (!cb.isCalled() /*called by connection*/ && !stopped) cb.failed(new InterruptedByTimeoutException());
        });
        try {
            eventLoop.addConnectableConnection(conn, null, new HttpHCConnectableConnectionHandler(cb, timer));
        } catch (IOException e) {
            if (!stopped) cb.failed(e);
            // exception occurred, so ignore timeout
            timer.cancel();
        }
    }

    public void handle(Callback<ConnectResult, IOException> cb) {
        long start = FDProvider.get().currentTimeMillis(); // need precise time, so do not use time recorded in Config
        this.handleFunc.accept(new Callback<>() {
            @Override
            protected void onSucceeded(Void value) {
                cb.succeeded(new ConnectResult(
                    FDProvider.get().currentTimeMillis() - start
                ));
            }

            @Override
            protected void onFailed(IOException err) {
                cb.failed(err);
            }
        });
    }

    public void stop() {
        stopped = true;
        if (dnsClient != null) {
            dnsClient.close();
            dnsClient = null;
        }
        if (dnsSocket != null) {
            try {
                dnsSocket.close();
            } catch (IOException ignore) {
            }
            dnsSocket = null;
        }
    }
}
