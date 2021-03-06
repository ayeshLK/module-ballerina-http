/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.stdlib.http.transport.websocket.passthrough;

import io.ballerina.stdlib.http.transport.contract.HttpWsConnectorFactory;
import io.ballerina.stdlib.http.transport.contract.websocket.ClientHandshakeFuture;
import io.ballerina.stdlib.http.transport.contract.websocket.ClientHandshakeListener;
import io.ballerina.stdlib.http.transport.contract.websocket.ServerHandshakeFuture;
import io.ballerina.stdlib.http.transport.contract.websocket.ServerHandshakeListener;
import io.ballerina.stdlib.http.transport.contract.websocket.WebSocketBinaryMessage;
import io.ballerina.stdlib.http.transport.contract.websocket.WebSocketClientConnector;
import io.ballerina.stdlib.http.transport.contract.websocket.WebSocketClientConnectorConfig;
import io.ballerina.stdlib.http.transport.contract.websocket.WebSocketCloseMessage;
import io.ballerina.stdlib.http.transport.contract.websocket.WebSocketConnection;
import io.ballerina.stdlib.http.transport.contract.websocket.WebSocketConnectorListener;
import io.ballerina.stdlib.http.transport.contract.websocket.WebSocketControlMessage;
import io.ballerina.stdlib.http.transport.contract.websocket.WebSocketHandshaker;
import io.ballerina.stdlib.http.transport.contract.websocket.WebSocketTextMessage;
import io.ballerina.stdlib.http.transport.contractimpl.DefaultHttpWsConnectorFactory;
import io.ballerina.stdlib.http.transport.message.HttpCarbonResponse;
import io.ballerina.stdlib.http.transport.util.TestUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

/**
 * Server Connector Listener to check WebSocket pass-through scenarios.
 */
public class WebSocketPassThroughServerConnectorListener implements WebSocketConnectorListener {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketPassThroughServerConnectorListener.class);

    private final HttpWsConnectorFactory connectorFactory = new DefaultHttpWsConnectorFactory();

    @Override
    public void onHandshake(WebSocketHandshaker webSocketHandshaker) {
        String remoteUrl = String.format("ws://%s:%d/%s", "localhost",
                                         TestUtil.WEBSOCKET_REMOTE_SERVER_PORT, "websocket");
        WebSocketClientConnectorConfig configuration = new WebSocketClientConnectorConfig(remoteUrl);
        configuration.setAutoRead(false);
        WebSocketClientConnector clientConnector = connectorFactory.createWsClientConnector(configuration);
        WebSocketConnectorListener clientConnectorListener = new WebSocketPassThroughClientConnectorListener();
        ClientHandshakeFuture clientHandshakeFuture = clientConnector.connect();
        clientHandshakeFuture.setWebSocketConnectorListener(clientConnectorListener);
        clientHandshakeFuture.setClientHandshakeListener(new ClientHandshakeListener() {
            @Override
            public void onSuccess(WebSocketConnection clientWebSocketConnection, HttpCarbonResponse response) {
                ServerHandshakeFuture serverFuture = webSocketHandshaker.handshake();
                serverFuture.setHandshakeListener(new ServerHandshakeListener() {
                    @Override
                    public void onSuccess(WebSocketConnection serverWebSocketConnection) {
                        WebSocketPassThroughTestConnectionManager.getInstance().
                                interRelateSessions(serverWebSocketConnection, clientWebSocketConnection);
                        serverWebSocketConnection.readNextFrame();
                        clientWebSocketConnection.readNextFrame();
                    }

                    @Override
                    public void onError(Throwable t) {
                        LOG.error(t.getMessage());
                        Assert.fail("Error: " + t.getMessage());
                    }
                });
            }

            @Override
            public void onError(Throwable t, HttpCarbonResponse response) {
                Assert.fail(t.getMessage());
            }
        });
    }

    @Override
    public void onMessage(WebSocketTextMessage textMessage) {
        WebSocketConnection serverConnection = WebSocketPassThroughTestConnectionManager.getInstance().
                getClientConnection(textMessage.getWebSocketConnection());
        serverConnection.pushText(textMessage.getText());
        serverConnection.readNextFrame();
    }

    @Override
    public void onMessage(WebSocketBinaryMessage binaryMessage) {
        WebSocketConnection serverConnection = WebSocketPassThroughTestConnectionManager.getInstance().
                getClientConnection(binaryMessage.getWebSocketConnection());
        serverConnection.pushBinary(binaryMessage.getByteBuffer());
        serverConnection.readNextFrame();
    }

    @Override
    public void onMessage(WebSocketControlMessage controlMessage) {
        // Do nothing.
    }

    @Override
    public void onMessage(WebSocketCloseMessage closeMessage) {
        int statusCode = closeMessage.getCloseCode();
        String closeReason = closeMessage.getCloseReason();
        WebSocketConnection clientConnection = WebSocketPassThroughTestConnectionManager.getInstance()
                .getClientConnection(closeMessage.getWebSocketConnection());
        clientConnection.initiateConnectionClosure(statusCode, closeReason).addListener(
                future -> closeMessage.getWebSocketConnection().finishConnectionClosure(statusCode, null));
    }

    @Override
    public void onClose(WebSocketConnection webSocketConnection) {
        //Do nothing
    }

    @Override
    public void onError(WebSocketConnection webSocketConnection, Throwable throwable) {

    }

    @Override
    public void onIdleTimeout(WebSocketControlMessage controlMessage) {
    }
}
