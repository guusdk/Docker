package org.ifsoft.websockets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import javax.xml.bind.DatatypeConverter;

import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.*;
import javax.net.ssl.*;
import javax.security.auth.callback.*;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.openfire.XMPPServer;

import net.sf.json.*;
import org.dom4j.Element;
import org.xmpp.packet.*;


public class ProxyConnection
{
    private static Logger Log = LoggerFactory.getLogger( "ProxyConnection" );
    private boolean isSecure = false;
    private ProxyWebSocket socket;
    private boolean connected = false;
    private WebSocketClient client = null;
    private ProxySocket proxySocket = null;
    private String subprotocol = null;
    private boolean archiveMetaData = false;
    private boolean archiveChat = false;

    public ProxyConnection(URI uri, String subprotocol, int connectTimeout)
    {
        Log.info("ProxyConnection " + uri + " " + subprotocol);

        this.subprotocol = subprotocol;

        SslContextFactory sec = new SslContextFactory();

        archiveMetaData = JiveGlobals.getBooleanProperty("docker.docserver.archive.metadata", false);
        archiveChat = JiveGlobals.getBooleanProperty("docker.docserver.archive.chat", true);

        if("wss".equals(uri.getScheme()))
        {
            sec.setValidateCerts(false);

            Log.debug("ProxyConnection - SSL");
            getSSLContext();
            isSecure = true;
        }

        client = new WebSocketClient(sec);
        proxySocket = new ProxySocket(this);

        try
        {
            client.start();
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            if (subprotocol != null) request.setSubProtocols(subprotocol);
            client.connect(proxySocket, uri, request);

            Log.debug("Connecting to : " + uri);
        }
        catch (Exception e)
        {
            Log.error("ProxyConnection", e);
        }
        finally
        {
            try
            {
                //client.stop();
            }
            catch (Exception e1)
            {
                Log.error("ProxyConnection", e1);
            }
        }

        connected = true;
    }

    public void setSocket( ProxyWebSocket socket ) {
        this.socket = socket;
    }

    public void deliver(String text)
    {
        Log.debug("ProxyConnection - deliver \n" + text);

        int pos = text.indexOf("[");

        if (pos > -1 && (archiveMetaData || archiveChat))     // docServer JSON payload
        {
            handleDocServerStream(text.substring(pos));
        }

        if (proxySocket != null)
        {
            proxySocket.deliver(text);
        }
    }

    public void disconnect()
    {
        Log.debug("ProxyConnection - disconnect");
        if (proxySocket != null) proxySocket.disconnect();
    }

    public void onClose(int code, String reason)
    {
        Log.debug("ProxyConnection - onClose " + reason + " " + code);
        connected = false;

        if (this.socket != null) this.socket.disconnect();
    }

    public void onMessage(String text) {
        Log.debug("ProxyConnection - onMessage \n" + text);

        try {
            this.socket.deliver(text);
        }

        catch (Exception e) {
            Log.error("deliverRawText error", e);
        }
    }

    public boolean isSecure() {
        return isSecure;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setSecure(boolean isSecure) {
        this.isSecure = isSecure;
    }

    private SSLContext getSSLContext()
    {
        SSLContext sc = null;

        try {
            Log.debug("ProxyConnection SSL truster");

            TrustManager[] trustAllCerts = new TrustManager[]
            {
               new X509TrustManager() {
                  public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                  }

                  public void checkClientTrusted(X509Certificate[] certs, String authType) {  }

                  public void checkServerTrusted(X509Certificate[] certs, String authType) {  }

               }
            };

            sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Create all-trusting host name verifier
            HostnameVerifier allHostsValid = new HostnameVerifier()
            {
                public boolean verify(String hostname, SSLSession session) {
                  return true;
                }
            };
            // Install the all-trusting host verifier
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

        } catch (Exception e)   {
            Log.error("WireLynkComponent - getSSLContext SSL truster", e);
        }

        return sc;
    }

    private void handleDocServerStream(String msg)
    {
        try {
            JSONArray jsonA = new JSONArray(msg);

            for (int i = 0; i < jsonA.length(); i++)
            {
                JSONObject json = new JSONObject(jsonA.getString(i));

                Log.debug("ProxySocket onMessage: " + json);

                if (json.has("docid") && json.has("openCmd") && archiveMetaData)
                {
                    String docId = json.getString("docid");
                    JSONObject openCmd = json.getJSONObject("openCmd");

                    JSONObject userJson = new JSONObject(new String(DatatypeConverter.parseBase64Binary(openCmd.getString("userid"))));
                    sendMessage(userJson, "opens " + openCmd.getString("url"));
                }
                else

                if (json.has("messages") && archiveChat && "message".equals(json.getString("type")))
                {
                    JSONArray messages = json.getJSONArray("messages");

                    if (messages.length() == 1) // multiple messages are history, ignore
                    {
                        JSONObject message = messages.getJSONObject(0);

                        String docId = message.getString("docid");
                        String msgText = message.getString("message");
                        String time = message.getString("time");
                        String useridOriginal = message.getString("useridoriginal");
                        String username = message.getString("username");

                        JSONObject userJson = new JSONObject(new String(DatatypeConverter.parseBase64Binary(useridOriginal)));
                        sendMessage(userJson, msgText);
                    }
                }
            }

        } catch (Exception e) {
            Log.error("handleDocServerStream", e);
        }
    }

    private void sendMessage(JSONObject userJson, String msgText)
    {
        Log.debug("sendMessage " + userJson + "\n" + msgText);

        Message message = new Message();
        message.setTo(userJson.getString("to"));
        message.setFrom(userJson.getString("from"));
        message.setType(userJson.getString("type").equals("chat") ? Message.Type.chat : Message.Type.groupchat);
        message.setBody(msgText);

        XMPPServer.getInstance().getMessageRouter().route(message);
    }

    @WebSocket(maxTextMessageSize = 64 * 1024) public class ProxySocket
    {
        private Session session;
        private ProxyConnection proxyConnection;
        private String lastMessage = null;
        private String ipaddr = null;
        private String docserverPort = null;
        private String httpPort = null;
        private String docserverUrl = null;

        public ProxySocket(ProxyConnection proxyConnection)
        {
            this.proxyConnection = proxyConnection;
            this.docserverPort = JiveGlobals.getProperty("docker.docserver.port", "32771");
            this.ipaddr = JiveGlobals.getProperty("docker.ipaddr", XMPPServer.getInstance().getServerInfo().getHostname());
            this.httpPort = JiveGlobals.getProperty("httpbind.port.plain", "7070");
            this.docserverUrl = JiveGlobals.getProperty("docker.docserver.url", "http://" + ipaddr + ":" + httpPort);
        }

        @OnWebSocketError public void onError(Throwable t)
        {
            Log.error("Error: "  + t.getMessage(), t);
        }

        @OnWebSocketClose public void onClose(int statusCode, String reason)
        {
            Log.debug("ProxySocket onClose " + statusCode + " " + reason);
            this.session = null;
            if (proxyConnection != null) proxyConnection.onClose(statusCode, reason);
        }

        @OnWebSocketConnect public void onConnect(Session session)
        {
            Log.debug("ProxySocket onConnect: " + session);
            this.session = session;

            if (lastMessage != null) deliver(lastMessage);
        }

        @OnWebSocketMessage public void onMessage(String msg)
        {
            Log.debug("ProxySocket onMessage \n" + msg);

            int pos = msg.indexOf("[");

            if (pos > -1 && (archiveMetaData || archiveChat))     // docServer JSON payload
            {
                handleDocServerStream(msg.substring(pos));
            }

            msg = msg.replace("http://" + ipaddr + ":" + docserverPort, docserverUrl);
            if (proxyConnection != null) proxyConnection.onMessage(msg);
        }

        public void deliver(String text)
        {
            if (session != null)
            {
                try {
                    Log.debug("ProxySocket deliver: \n" + text);
                    session.getRemote().sendStringByFuture(text);
                    lastMessage = null;
                } catch (Exception e) {
                    Log.error("ProxySocket deliver", e);
                }
            } else lastMessage = text;
        }

        public void disconnect()
        {
            if (session != null) session.close(StatusCode.NORMAL,"I'm done");
        }

    }
}
