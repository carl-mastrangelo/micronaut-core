package io.micronaut.http.client.netty

import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpStatus
import io.micronaut.http.HttpVersion
import io.micronaut.http.server.netty.ssl.CertificateProvidedSslBuilder
import io.micronaut.http.ssl.SslConfiguration
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2Settings
import io.netty.handler.codec.http2.Http2SettingsAckFrame
import io.netty.handler.codec.http2.Http2SettingsFrame
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import reactor.core.publisher.Mono
import spock.lang.Specification

class ConnectionManagerSpec extends Specification {
    private static void patch(DefaultHttpClient httpClient, EmbeddedChannel... channels) {
        httpClient.connectionManager = new ConnectionManager(httpClient.connectionManager) {
            int i = 0

            @Override
            protected ChannelFuture doConnect(DefaultHttpClient.RequestKey requestKey, ChannelInitializer<? extends Channel> channelInitializer) {
                def channel = channels[i++]
                channel.pipeline().addLast(channelInitializer)
                def promise = channel.newPromise()
                promise.setSuccess()
                return promise
            }
        }
    }

    private static ChannelHandler tlsHandler() {
        def certificate = new SelfSignedCertificate()
        def builder = SslContextBuilder.forServer(certificate.key(), certificate.cert())
        CertificateProvidedSslBuilder.setupSslBuilder(builder, new SslConfiguration(), HttpVersion.HTTP_2_0);
        return builder.build().newHandler(ByteBufAllocator.DEFAULT)
    }

    def 'simple get'() {
        given:
        def ctx = ApplicationContext.run([
                'micronaut.http.client.ssl.insecure-trust-all-certificates': true,
        ])
        def client = ctx.getBean(DefaultHttpClient)

        def serverChannel = new EmbeddedChannel(new DummyChannelId('server'))
        serverChannel.freezeTime()
        serverChannel.config().setAutoRead(true)
        serverChannel.pipeline()
                .addLast(tlsHandler())
                .addLast(new ApplicationProtocolNegotiationHandler("h2") {
                    @Override
                    protected void configurePipeline(ChannelHandlerContext chtx, String protocol) throws Exception {
                        chtx.pipeline()
                                .addLast(Http2FrameCodecBuilder.forServer().build())
                    }
                })

        def clientChannel = new EmbeddedChannel(new DummyChannelId('client'))
        clientChannel.freezeTime()
        EmbeddedTestUtil.connect(serverChannel, clientChannel)
        patch(client, clientChannel)

        when:
        def future = Mono.from(client.exchange('https://example.com/foo')).toFuture()
        future.exceptionally(t -> t.printStackTrace())
        EmbeddedTestUtil.advance(serverChannel, clientChannel)
        then:
        serverChannel.readInbound() instanceof Http2SettingsFrame

        when:
        serverChannel.writeOutbound(new DefaultHttp2SettingsFrame(Http2Settings.defaultSettings()))
        EmbeddedTestUtil.advance(serverChannel, clientChannel)
        then:
        serverChannel.readInbound() instanceof Http2SettingsAckFrame
        Http2HeadersFrame request = serverChannel.readInbound()
        request.headers().get(Http2Headers.PseudoHeaderName.PATH.value()) == '/foo'
        request.headers().get(Http2Headers.PseudoHeaderName.SCHEME.value()) == 'https'
        request.headers().get(Http2Headers.PseudoHeaderName.AUTHORITY.value()) == 'example.com'
        request.headers().get(Http2Headers.PseudoHeaderName.METHOD.value()) == 'GET'

        when:
        def responseHeaders = new DefaultHttp2Headers()
        responseHeaders.add(Http2Headers.PseudoHeaderName.STATUS.value(), "200")
        serverChannel.writeOutbound(new DefaultHttp2HeadersFrame(responseHeaders, true).stream(request.stream()))
        EmbeddedTestUtil.advance(serverChannel, clientChannel)
        then:
        def response = future.get()
        response.status() == HttpStatus.OK

        cleanup:
        client.close()
        ctx.close()
    }
}
