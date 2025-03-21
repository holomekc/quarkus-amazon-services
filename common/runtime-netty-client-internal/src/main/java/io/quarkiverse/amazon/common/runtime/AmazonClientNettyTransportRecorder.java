package io.quarkiverse.amazon.common.runtime;

import java.util.Collections;
import java.util.HashSet;
import java.util.function.Supplier;

import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslProvider;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import software.amazon.awssdk.http.TlsTrustManagersProvider;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.Http2Configuration;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup;
import software.amazon.awssdk.utils.ThreadFactoryBuilder;

@Recorder
public class AmazonClientNettyTransportRecorder extends AbstractAmazonClientTransportRecorder {

    @SuppressWarnings("rawtypes")
    @Override
    public RuntimeValue<SdkAsyncHttpClient.Builder> configureAsync(String clientName,
            RuntimeValue<AsyncHttpClientConfig> asyncConfigRuntime) {
        NettyNioAsyncHttpClient.Builder builder = NettyNioAsyncHttpClient.builder();
        AsyncHttpClientConfig asyncConfig = asyncConfigRuntime.getValue();
        validateNettyClientConfig(clientName, asyncConfig);

        builder.connectionAcquisitionTimeout(asyncConfig.connectionAcquisitionTimeout());
        builder.connectionMaxIdleTime(asyncConfig.connectionMaxIdleTime());
        builder.connectionTimeout(asyncConfig.connectionTimeout());
        asyncConfig.connectionTimeToLive().ifPresent(builder::connectionTimeToLive);
        builder.maxConcurrency(asyncConfig.maxConcurrency());
        builder.maxPendingConnectionAcquires(asyncConfig.maxPendingConnectionAcquires());
        builder.protocol(asyncConfig.protocol());
        builder.readTimeout(asyncConfig.readTimeout());
        builder.writeTimeout(asyncConfig.writeTimeout());
        builder.tcpKeepAlive(asyncConfig.tcpKeepAlive());
        asyncConfig.sslProvider().map(this::map).map(SslProviderTypeNetty::create).ifPresent(builder::sslProvider);
        builder.useIdleConnectionReaper(asyncConfig.useIdleConnectionReaper());

        if (asyncConfig.http2().initialWindowSize().isPresent() || asyncConfig.http2().maxStreams().isPresent()) {
            Http2Configuration.Builder http2Builder = Http2Configuration.builder();
            asyncConfig.http2().initialWindowSize().ifPresent(http2Builder::initialWindowSize);
            asyncConfig.http2().maxStreams().ifPresent(http2Builder::maxStreams);
            asyncConfig.http2().healthCheckPingPeriod().ifPresent(http2Builder::healthCheckPingPeriod);
            builder.http2Configuration(http2Builder.build());
        }

        if (asyncConfig.proxy().enabled() && asyncConfig.proxy().endpoint().isPresent()) {
            software.amazon.awssdk.http.nio.netty.ProxyConfiguration.Builder proxyBuilder = software.amazon.awssdk.http.nio.netty.ProxyConfiguration
                    .builder().scheme(asyncConfig.proxy().endpoint().get().getScheme())
                    .host(asyncConfig.proxy().endpoint().get().getHost())
                    .nonProxyHosts(new HashSet<>(asyncConfig.proxy().nonProxyHosts().orElse(Collections.emptyList())));

            if (asyncConfig.proxy().endpoint().get().getPort() != -1) {
                proxyBuilder.port(asyncConfig.proxy().endpoint().get().getPort());
            }
            builder.proxyConfiguration(proxyBuilder.build());
        }

        builder.tlsKeyManagersProvider(getTlsKeyManagersProvider(asyncConfig.tlsKeyManagersProvider()));
        TlsTrustManagersProvider tlsTrustManagerProvider = getTlsTrustManagersProvider(asyncConfig.tlsTrustManagersProvider());
        if (tlsTrustManagerProvider != null) {
            builder.tlsTrustManagersProvider(tlsTrustManagerProvider);
        }

        return new RuntimeValue<>(builder);
    }

    public RuntimeValue<SdkAsyncHttpClient.Builder> configureNettyAsync(RuntimeValue<SdkAsyncHttpClient.Builder> builderRuntime,
            Supplier<EventLoopGroup> eventLoopSupplier, RuntimeValue<AsyncHttpClientConfig> asyncConfigRuntime) {
        AsyncHttpClientConfig asyncConfig = asyncConfigRuntime.getValue();
        NettyNioAsyncHttpClient.Builder builder = (NettyNioAsyncHttpClient.Builder) builderRuntime.getValue();

        if (asyncConfig.eventLoop().override()) {
            SdkEventLoopGroup.Builder eventLoopBuilder = SdkEventLoopGroup.builder();
            asyncConfig.eventLoop().numberOfThreads().ifPresent(eventLoopBuilder::numberOfThreads);
            if (asyncConfig.eventLoop().threadNamePrefix().isPresent()) {
                eventLoopBuilder.threadFactory(
                        new ThreadFactoryBuilder().threadNamePrefix(asyncConfig.eventLoop().threadNamePrefix().get()).build());
            }
            builder.eventLoopGroupBuilder(eventLoopBuilder);
        } else {
            var eventLoopGroup = SdkEventLoopGroup.create(eventLoopSupplier.get());
            builder.eventLoopGroup(eventLoopGroup);
        }

        return new RuntimeValue<>(builder);
    }

    private void validateNettyClientConfig(String extension, AsyncHttpClientConfig config) {
        if (config.maxConcurrency() <= 0) {
            throw new RuntimeConfigurationError(
                    String.format("quarkus.%s.async-client.max-concurrency may not be negative or zero.", extension));
        }

        if (config.http2().maxStreams().isPresent() && config.http2().maxStreams().get() <= 0) {
            throw new RuntimeConfigurationError(
                    String.format("quarkus.%s.async-client.http2().max-streams may not be negative.", extension));
        }

        if (config.http2().initialWindowSize().isPresent() && config.http2().initialWindowSize().getAsInt() <= 0) {
            throw new RuntimeConfigurationError(
                    String.format("quarkus.%s.async-client.http2().initial-window-size may not be negative.", extension));
        }

        if (config.maxPendingConnectionAcquires() <= 0) {
            throw new RuntimeConfigurationError(
                    String.format("quarkus.%s.async-client.max-pending-connection-acquires may not be negative or zero.",
                            extension));
        }
        if (config.eventLoop().override()) {
            if (config.eventLoop().numberOfThreads().isPresent() && config.eventLoop().numberOfThreads().getAsInt() <= 0) {
                throw new RuntimeConfigurationError(
                        String.format("quarkus.%s.async-client.event-loop.number-of-threads may not be negative or zero.",
                                extension));
            }
        }
        if (config.proxy().enabled()) {
            config.proxy().endpoint().ifPresent(uri -> validateProxyEndpoint(extension, uri, "async"));
        }

        validateTlsKeyManagersProvider(extension, config.tlsKeyManagersProvider(), "async");
        validateTlsTrustManagersProvider(extension, config.tlsTrustManagersProvider(), "async");
    }

    public SslProviderTypeNetty map(AsyncHttpClientConfig.SslProviderType sslProviderType) {
        switch (sslProviderType) {
            case JDK:
                return SslProviderTypeNetty.JDK;
            case OPENSSL:
                return SslProviderTypeNetty.OPENSSL;
            case OPENSSL_REFCNT:
                return SslProviderTypeNetty.OPENSSL_REFCNT;
            default:
                return SslProviderTypeNetty.JDK;
        }
    }

    public enum SslProviderTypeNetty {

        JDK {
            @Override
            SslProvider create() {
                return io.netty.handler.ssl.SslProvider.JDK;
            }
        },
        OPENSSL {
            @Override
            SslProvider create() {
                return io.netty.handler.ssl.SslProvider.OPENSSL;
            }
        },
        OPENSSL_REFCNT {
            @Override
            SslProvider create() {
                return io.netty.handler.ssl.SslProvider.OPENSSL_REFCNT;
            }
        };

        abstract SslProvider create();
    }
}
