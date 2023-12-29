/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
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

package io.ballerina.stdlib.http.transport.http2.ssl;

import io.ballerina.stdlib.http.transport.contentaware.listeners.EchoMessageListener;
import io.ballerina.stdlib.http.transport.contract.HttpClientConnector;
import io.ballerina.stdlib.http.transport.contract.HttpWsConnectorFactory;
import io.ballerina.stdlib.http.transport.contract.ServerConnector;
import io.ballerina.stdlib.http.transport.contract.ServerConnectorFuture;
import io.ballerina.stdlib.http.transport.contract.config.ListenerConfiguration;
import io.ballerina.stdlib.http.transport.contract.config.SenderConfiguration;
import io.ballerina.stdlib.http.transport.contract.exceptions.ServerConnectorException;
import io.ballerina.stdlib.http.transport.contractimpl.DefaultHttpWsConnectorFactory;
import io.ballerina.stdlib.http.transport.util.TestUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.HashMap;

import static io.ballerina.stdlib.http.transport.contract.Constants.HTTPS_SCHEME;
import static io.ballerina.stdlib.http.transport.contract.Constants.HTTP_2_0;
import static io.ballerina.stdlib.http.transport.contract.Constants.REQUIRE;

/**
 * Test mTLS with certs and keys in HTTP2.
 */
public class Http2AlpnWithCertsTest {

    private static final Logger LOG = LoggerFactory.getLogger(Http2AlpnWithCertsTest.class);
    private ServerConnector serverConnector;
    private HttpClientConnector httpClientConnector;
    private HttpWsConnectorFactory connectorFactory;

    @BeforeClass
    public void setup() throws InterruptedException {

        HttpWsConnectorFactory factory = new DefaultHttpWsConnectorFactory();
        serverConnector = factory
                .createServerConnector(TestUtil.getDefaultServerBootstrapConfig(), getListenerConfigs());
        ServerConnectorFuture future = serverConnector.start();
        future.setHttpConnectorListener(new EchoMessageListener());
        future.sync();

        connectorFactory = new DefaultHttpWsConnectorFactory();
        httpClientConnector = connectorFactory.createHttpClientConnector(new HashMap<>(), getSenderConfigs());
    }

    @Test
    public void testHttp2AlpnWithcerts() {
        TestUtil.testHttpsPost(httpClientConnector, TestUtil.SERVER_PORT1);
    }

    private ListenerConfiguration getListenerConfigs() {
        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setPort(TestUtil.SERVER_PORT1);
        listenerConfiguration.setScheme(HTTPS_SCHEME);
        listenerConfiguration.setVersion(HTTP_2_0);
        listenerConfiguration.setSslSessionTimeOut(TestUtil.SSL_SESSION_TIMEOUT);
        listenerConfiguration.setSslHandshakeTimeOut(TestUtil.SSL_HANDSHAKE_TIMEOUT);
        listenerConfiguration.setServerKeyFile(TestUtil.getAbsolutePath(TestUtil.KEY_FILE));
        listenerConfiguration.setServerCertificates(TestUtil.getAbsolutePath(TestUtil.CERT_FILE));
        listenerConfiguration.setVerifyClient(REQUIRE);
        listenerConfiguration.setServerTrustCertificates(TestUtil.getAbsolutePath(TestUtil.CERT_FILE));
        return listenerConfiguration;
    }

    private SenderConfiguration getSenderConfigs() {
        SenderConfiguration senderConfiguration = new SenderConfiguration();
        senderConfiguration.setClientTrustCertificates(TestUtil.getAbsolutePath(TestUtil.CERT_FILE));
        senderConfiguration.setClientKeyFile(TestUtil.getAbsolutePath(TestUtil.KEY_FILE));
        senderConfiguration.setClientCertificates(TestUtil.getAbsolutePath(TestUtil.CERT_FILE));
        senderConfiguration.setHttpVersion(HTTP_2_0);
        senderConfiguration.setScheme(HTTPS_SCHEME);
        senderConfiguration.setSslSessionTimeOut(TestUtil.SSL_SESSION_TIMEOUT);
        senderConfiguration.setSslHandshakeTimeOut(TestUtil.SSL_HANDSHAKE_TIMEOUT);
        return senderConfiguration;
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        httpClientConnector.close();
        serverConnector.stop();
        try {
            connectorFactory.shutdown();
        } catch (InterruptedException e) {
            LOG.error("Interrupted while waiting for HttpWsFactory to shutdown", e);
        }
    }
}

