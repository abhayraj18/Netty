package com.netty.file;

/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map.Entry;

/**
 * This class is meant to be run against {@link HttpUploadServer}.
 */
public final class HttpUploadClient {

    static final String BASE_URL = System.getProperty("baseUrl", "http://127.0.0.1:8080/");
    static final String FILE = "C:\\Users\\abhay.jain\\Workspace\\SpringRestful\\Resources\\ElasticSearch\\Article.json";

    public static void main(String[] args) throws Exception {
        String postSimple, postFile, get;
        if (BASE_URL.endsWith("/")) {
            postSimple = BASE_URL + "formpost";
            postFile = BASE_URL + "formpostmultipart";
            get = BASE_URL + "formget";
        } else {
            postSimple = BASE_URL + "/formpost";
            postFile = BASE_URL + "/formpostmultipart";
            get = BASE_URL + "/formget";
        }

        URI uriSimple = new URI(postSimple);
        String scheme = uriSimple.getScheme() == null? "http" : uriSimple.getScheme();
        String host = uriSimple.getHost() == null? "127.0.0.1" : uriSimple.getHost();
        int port = uriSimple.getPort();
        if (port == -1) {
            if ("http".equalsIgnoreCase(scheme)) {
                port = 80;
            } else if ("https".equalsIgnoreCase(scheme)) {
                port = 443;
            }
        }

        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            System.err.println("Only HTTP(S) is supported.");
            return;
        }

        final boolean ssl = "https".equalsIgnoreCase(scheme);
        final SslContext sslCtx;
        if (ssl) {
            sslCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } else {
            sslCtx = null;
        }

        URI uriFile = new URI(postFile);
        File file = new File(FILE);
        if (!file.canRead()) {
            throw new FileNotFoundException(FILE);
        }

        // Configure the client.
        EventLoopGroup group = new NioEventLoopGroup();

        // setup the factory: here using a mixed memory/disk based on size threshold
        HttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE); // Disk if MINSIZE exceed

        DiskFileUpload.deleteOnExitTemporaryFile = true; // should delete file on exit (in normal exit)
        DiskFileUpload.baseDirectory = null; // system temp directory
        DiskAttribute.deleteOnExitTemporaryFile = true; // should delete file on exit (in normal exit)
        DiskAttribute.baseDirectory = null; // system temp directory

        try {
            Bootstrap b = new Bootstrap();
            b.group(group).channel(NioSocketChannel.class).handler(new HttpUploadClientIntializer(sslCtx));

            // Simple Get form: no factory used (not usable)
            List<Entry<String, String>> headers = formget(b, host, port, get, uriSimple);
            if (headers == null) {
                factory.cleanAllHttpData();
                return;
            }

            // Simple Post form: factory used for big attributes
            List<InterfaceHttpData> bodylist = formpost(b, host, port, uriSimple, file, factory, headers);
            if (bodylist == null) {
                factory.cleanAllHttpData();
                return;
            }

            // Multipart Post form: factory used
            formpostmultipart(b, host, port, uriFile, factory, headers, bodylist);
        } finally {
            // Shut down executor threads to exit.
            group.shutdownGracefully();

            // Really clean all temporary files if they still exist
            factory.cleanAllHttpData();
        }
    }

    /**
     * Standard usage of HTTP API in Netty without file Upload (get is not able to achieve File upload
     * due to limitation on request size).
     *
     * @return the list of headers that will be used in every example after
     **/
    private static List<Entry<String, String>> formget(
            Bootstrap bootstrap, String host, int port, String get, URI uriSimple) throws Exception {
        // XXX /formget
        // No use of HttpPostRequestEncoder since not a POST
        Channel channel = bootstrap.connect(host, port).sync().channel();

        // Prepare the HTTP request.
        QueryStringEncoder encoder = new QueryStringEncoder(get);
        // add Form attribute
        encoder.addParam("getform", "GET");
        encoder.addParam("info", "first value");
        encoder.addParam("secondinfo", "secondvalue ���&");
        // not the big one since it is not compatible with GET size
        // encoder.addParam("thirdinfo", textArea);
        encoder.addParam("thirdinfo", "third value\r\ntest second line\r\n\r\nnew line\r\n");
        encoder.addParam("Send", "Send");

        URI uriGet = new URI(encoder.toString());
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uriGet.toASCIIString());
        HttpHeaders headers = request.headers();
        headers.set(HttpHeaderNames.HOST, host);
        headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        headers.set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP + "," + HttpHeaderValues.DEFLATE);

        headers.set(HttpHeaderNames.ACCEPT_CHARSET, "ISO-8859-1,utf-8;q=0.7,*;q=0.7");
        headers.set(HttpHeaderNames.ACCEPT_LANGUAGE, "fr");
        headers.set(HttpHeaderNames.REFERER, uriSimple.toString());
        headers.set(HttpHeaderNames.USER_AGENT, "Netty Simple Http Client side");
        headers.set(HttpHeaderNames.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        //connection will not close but needed
        // headers.set("Connection","keep-alive");
        // headers.set("Keep-Alive","300");

        headers.set(
                HttpHeaderNames.COOKIE, ClientCookieEncoder.STRICT.encode(
                        new DefaultCookie("my-cookie", "foo"),
                        new DefaultCookie("another-cookie", "bar"))
        );

        // send request
        channel.writeAndFlush(request);

        // Wait for the server to close the connection.
        channel.closeFuture().sync();

        // convert headers to list
        return headers.entries();
    }

    /**
     * Standard post without multipart but already support on Factory (memory management)
     *
     * @return the list of HttpData object (attribute and file) to be reused on next post
     */
    private static List<InterfaceHttpData> formpost(
            Bootstrap bootstrap,
            String host, int port, URI uriSimple, File file, HttpDataFactory factory,
            List<Entry<String, String>> headers) throws Exception {
        // XXX /formpost
        // Start the connection attempt.
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));
        // Wait until the connection attempt succeeds or fails.
        Channel channel = future.sync().channel();

        // Prepare the HTTP request.
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uriSimple.toASCIIString());

        // Use the PostBody encoder
        HttpPostRequestEncoder bodyRequestEncoder =
                new HttpPostRequestEncoder(factory, request, false);  // false => not multipart

        // it is legal to add directly header or cookie into the request until finalize
        for (Entry<String, String> entry : headers) {
            request.headers().set(entry.getKey(), entry.getValue());
        }

        // add Form attribute
        bodyRequestEncoder.addBodyAttribute("getform", "POST");
        bodyRequestEncoder.addBodyAttribute("info", "first value");
        bodyRequestEncoder.addBodyAttribute("secondinfo", "secondvalue ���&");
        bodyRequestEncoder.addBodyAttribute("thirdinfo", textArea);
        bodyRequestEncoder.addBodyFileUpload("myfile", file, "application/x-zip-compressed", false);

        // finalize request
        request = bodyRequestEncoder.finalizeRequest();

        // Create the bodylist to be reused on the last version with Multipart support
        List<InterfaceHttpData> bodylist = bodyRequestEncoder.getBodyListAttributes();

        // send request
        channel.write(request);

        // test if request was chunked and if so, finish the write
        if (bodyRequestEncoder.isChunked()) { // could do either request.isChunked()
            // either do it through ChunkedWriteHandler
            channel.write(bodyRequestEncoder);
        }
        channel.flush();

        // Do not clear here since we will reuse the InterfaceHttpData on the next request
        // for the example (limit action on client side). Take this as a broadcast of the same
        // request on both Post actions.
        //
        // On standard program, it is clearly recommended to clean all files after each request
        // bodyRequestEncoder.cleanFiles();

        // Wait for the server to close the connection.
        channel.closeFuture().sync();
        return bodylist;
    }

    /**
     * Multipart example
     */
    private static void formpostmultipart(
            Bootstrap bootstrap, String host, int port, URI uriFile, HttpDataFactory factory,
            Iterable<Entry<String, String>> headers, List<InterfaceHttpData> bodylist) throws Exception {
        // XXX /formpostmultipart
        // Start the connection attempt.
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));
        // Wait until the connection attempt succeeds or fails.
        Channel channel = future.sync().channel();

        // Prepare the HTTP request.
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uriFile.toASCIIString());

        // Use the PostBody encoder
        HttpPostRequestEncoder bodyRequestEncoder =
                new HttpPostRequestEncoder(factory, request, true); // true => multipart

        // it is legal to add directly header or cookie into the request until finalize
        for (Entry<String, String> entry : headers) {
            request.headers().set(entry.getKey(), entry.getValue());
        }

        // add Form attribute from previous request in formpost()
        bodyRequestEncoder.setBodyHttpDatas(bodylist);

        // finalize request
        bodyRequestEncoder.finalizeRequest();

        // send request
        channel.write(request);

        // test if request was chunked and if so, finish the write
        if (bodyRequestEncoder.isChunked()) {
            channel.write(bodyRequestEncoder);
        }
        channel.flush();

        // Now no more use of file representation (and list of HttpData)
        bodyRequestEncoder.cleanFiles();

        // Wait for the server to close the connection.
        channel.closeFuture().sync();
    }

    // use to simulate a small TEXTAREA field in a form
    private static final String textArea = "short text";
    // use to simulate a big TEXTAREA field in a form
}