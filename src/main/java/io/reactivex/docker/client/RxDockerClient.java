package io.reactivex.docker.client;


import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.reactivex.docker.client.model.DockerInfo;
import io.reactivex.docker.client.model.DockerVersion;
import io.reactivex.docker.client.ssl.DockerCertificates;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.pipeline.ssl.DefaultFactories;
import io.reactivex.netty.protocol.http.client.FlatResponseOperator;
import io.reactivex.netty.protocol.http.client.HttpClient;
import io.reactivex.netty.protocol.http.client.HttpClientBuilder;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import javax.net.ssl.SSLEngine;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Optional;

import static io.reactivex.netty.protocol.http.client.HttpClientRequest.createGet;

public class RxDockerClient {

    public static final String DEFAULT_DOCKER_HOST = "localhost";
    public static final int DEFAULT_DOCKER_PORT = 2375;

    private final Logger logger = LoggerFactory.getLogger(RxDockerClient.class);
    private final String apiUri;
    private final HttpClient<ByteBuf, ByteBuf> rxClient;

    public RxDockerClient(final String dockerHost, final String dockerCertPath) {
        this(Optional.ofNullable(dockerHost), Optional.ofNullable(dockerCertPath));
    }

    private RxDockerClient(final Optional<String> dockerHost, final Optional<String> dockerCertPath) {
        final HostAndPort hostAndPort = dockerHost.map(endpoint -> HostAndPort.from(endpoint)).orElse(HostAndPort.using(DEFAULT_DOCKER_HOST, DEFAULT_DOCKER_PORT));
        final String scheme = dockerCertPath.isPresent() ? "https" : "http";

        apiUri = new StringBuilder(scheme).append("://").append(hostAndPort.getHost()).append(":").append(hostAndPort.getPort()).toString();
        logger.info("Docker API uri {}", apiUri);

        HttpClientBuilder<ByteBuf, ByteBuf> builder = RxNetty.<ByteBuf, ByteBuf>newHttpClientBuilder(hostAndPort.getHost(), hostAndPort.getPort());

        if (dockerCertPath.isPresent()) {
            DefaultFactories.SSLContextBasedFactory sslContextBasedFactory = new DefaultFactories.SSLContextBasedFactory(new DockerCertificates(Paths.get(dockerCertPath.get())).sslContext()) {
                @Override
                public SSLEngine createSSLEngine(ByteBufAllocator allocator) {
                    SSLEngine sslEngine = super.createSSLEngine(allocator);
                    sslEngine.setUseClientMode(true);
                    return sslEngine;
                }
            };
            builder.withSslEngineFactory(sslContextBasedFactory);
        }
        rxClient = builder.build();
    }

    /**
     * Builds the client using DOCKER_HOST and DOCKER_CERT_PATH environment variables
     *
     * @return a new instance of RxDockerClient
     */
    public static RxDockerClient fromDefaultEnv() {
        return new RxDockerClient(Optional.ofNullable(System.getenv("DOCKER_HOST")), Optional.ofNullable(System.getenv("DOCKER_CERT_PATH")));
    }

    public Observable<DockerVersion> serverVersionObs() {
        return _toEndpointObservable("/version", DockerVersion.class);
    }


    public DockerVersion getServerVersion() {
        return serverVersionObs().
                toBlocking().
                first();
    }

    public Observable<DockerInfo> infoObs() {
        return _toEndpointObservable("/info", DockerInfo.class);
    }

    public DockerInfo info() {
        return infoObs().
                toBlocking().
                first();
    }

    private <T> Observable<T> _toEndpointObservable(String uri, Class<T> model) {
        Observable<HttpClientResponse<ByteBuf>> observable = rxClient.submit(createGet(uri));
        Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE).create();
        return observable.
                lift(FlatResponseOperator.<ByteBuf>flatResponse()).
                map(resp -> gson.fromJson(resp.getContent().toString(Charset.defaultCharset()), model));
    }

    public String getApiUri() {
        return apiUri;
    }
}
