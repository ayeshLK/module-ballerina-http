/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package io.ballerina.stdlib.http.transport.http2.servertimeout;

import io.ballerina.stdlib.http.transport.contract.Constants;
import io.ballerina.stdlib.http.transport.contract.HttpClientConnector;
import io.ballerina.stdlib.http.transport.contract.HttpResponseFuture;
import io.ballerina.stdlib.http.transport.contract.HttpWsConnectorFactory;
import io.ballerina.stdlib.http.transport.contract.OperationStatus;
import io.ballerina.stdlib.http.transport.contract.ServerConnector;
import io.ballerina.stdlib.http.transport.contract.ServerConnectorFuture;
import io.ballerina.stdlib.http.transport.contract.config.ListenerConfiguration;
import io.ballerina.stdlib.http.transport.contract.config.SenderConfiguration;
import io.ballerina.stdlib.http.transport.contract.config.TransportsConfiguration;
import io.ballerina.stdlib.http.transport.contractimpl.DefaultHttpWsConnectorFactory;
import io.ballerina.stdlib.http.transport.http2.listeners.Http2ServerWaitDuringDataWrite;
import io.ballerina.stdlib.http.transport.message.HttpCarbonMessage;
import io.ballerina.stdlib.http.transport.message.HttpConnectorUtil;
import io.ballerina.stdlib.http.transport.util.DefaultHttpConnectorListener;
import io.ballerina.stdlib.http.transport.util.TestUtil;
import io.ballerina.stdlib.http.transport.util.client.http2.MessageGenerator;
import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

/**
 * Tests server timeout during response data write with a prior knowledge off HTTP/2 client.
 */
public class TimeoutDuringResponseDataWrite2 {
    private static final Logger LOG = LoggerFactory.getLogger(TimeoutDuringResponseDataWrite.class);

    private HttpClientConnector h2ClientWithoutPriorKnowledge;
    private ServerConnector serverConnector;
    private HttpWsConnectorFactory connectorFactory;
    private static final String PRIOR_OFF_EXPECTED_ERROR = "HTTP/2 stream 1 reset by the remote peer";


    @BeforeClass
    public void setup() throws InterruptedException {
        connectorFactory = new DefaultHttpWsConnectorFactory();
        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setPort(TestUtil.HTTP_SERVER_PORT);
        listenerConfiguration.setScheme(Constants.HTTP_SCHEME);
        listenerConfiguration.setVersion(Constants.HTTP_2_0);
        //Give enough time for the server to respond before the timeout event occurs
        listenerConfiguration.setSocketIdleTimeout(5000);
        serverConnector = connectorFactory.createServerConnector(TestUtil.getDefaultServerBootstrapConfig(),
                                                                 listenerConfiguration);
        ServerConnectorFuture future = serverConnector.start();
        future.setHttpConnectorListener(new Http2ServerWaitDuringDataWrite(10000));
        future.sync();

        TransportsConfiguration transportsConfiguration = new TransportsConfiguration();
        SenderConfiguration senderConfiguration1 = getSenderConfiguration();
        senderConfiguration1.setForceHttp2(true);

        SenderConfiguration senderConfiguration2 = getSenderConfiguration();
        senderConfiguration2.setForceHttp2(false);
        h2ClientWithoutPriorKnowledge = connectorFactory.createHttpClientConnector(
                HttpConnectorUtil.getTransportProperties(transportsConfiguration), senderConfiguration2);
    }

    private SenderConfiguration getSenderConfiguration() {
        SenderConfiguration senderConfiguration = new SenderConfiguration();
        senderConfiguration.setScheme(Constants.HTTP_SCHEME);
        senderConfiguration.setHttpVersion(Constants.HTTP_2_0);
        //Set this to a value larger than server socket timeout value, to make sure that the server times out first
        senderConfiguration.setSocketIdleTimeout(500000);
        return senderConfiguration;
    }

    @Test
    public void testServerTimeout() {
        HttpCarbonMessage httpMsg1 = MessageGenerator.generateRequest(HttpMethod.POST, "Test Http2 Message");
        verifyResult(httpMsg1, h2ClientWithoutPriorKnowledge);
    }

    private void verifyResult(HttpCarbonMessage httpCarbonMessage, HttpClientConnector http2ClientConnector) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            DefaultHttpConnectorListener msgListener = new DefaultHttpConnectorListener(latch);
            HttpResponseFuture responseFuture = http2ClientConnector.send(httpCarbonMessage);
            responseFuture.setHttpConnectorListener(msgListener);
            latch.await(TestUtil.HTTP2_RESPONSE_TIME_OUT, TimeUnit.SECONDS);
            responseFuture.sync();
            Thread.sleep(10000); //Wait for the stream reset error.
            OperationStatus status = responseFuture.getStatus();
            if (status.getCause() != null) {
                String errorMsg = status.getCause().getMessage();
                assertEquals(errorMsg, PRIOR_OFF_EXPECTED_ERROR);
            } else {
                fail("TimeoutDuringResponseDataWrite2 is heavily dependent on timing of events and needs an alternate" +
                             " solution");
            }
        } catch (InterruptedException e) {
            LOG.error("Interrupted exception occurred");
        }
    }

    @AfterClass
    public void cleanUp() {
        h2ClientWithoutPriorKnowledge.close();
        serverConnector.stop();
        try {
            connectorFactory.shutdown();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for HttpWsFactory to close");
        }
    }
}
