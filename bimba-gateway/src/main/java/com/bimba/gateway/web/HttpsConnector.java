package com.bimba.gateway.web;

import com.bimba.gateway.agents.AgentCa;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Second Tomcat connector: HTTPS on bimba.https-port (8443) with the gateway's own certificate, next to plain HTTP on
 * server.port (8090). Site agents use HTTPS (pinning the fingerprint of a self-signed certificate) and, once they have
 * one, a client certificate from the gateway's agent authority (mutual TLS).
 */
@Component
public class HttpsConnector implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    private final int port;

    public HttpsConnector(@Value("${bimba.https-port:8443}") int port) {
        this.port = port;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (port <= 0 || Tls.keyStore == null) {
            return;
        }
        Connector c = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        c.setScheme("https");
        c.setSecure(true);
        c.setPort(port);
        Http11NioProtocol protocol = (Http11NioProtocol) c.getProtocolHandler();
        protocol.setSSLEnabled(true);
        SSLHostConfig host = new SSLHostConfig();
        host.setProtocols("TLSv1.2+TLSv1.3");
        SSLHostConfigCertificate cert = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
        cert.setCertificateKeystoreFile(Tls.keyStore.getAbsolutePath());
        cert.setCertificateKeystorePassword(Tls.password);
        cert.setCertificateKeystoreType("PKCS12");
        host.addCertificate(cert);
        if (AgentCa.trustStore != null) {
            // site agents sign in with a client certificate from the gateway's agent authority; browsers are asked
            // for one too but may send none ("optional"), so the console keeps working on this port
            host.setCertificateVerification("optional");
            host.setTruststoreFile(AgentCa.trustStore.getAbsolutePath());
            host.setTruststorePassword(AgentCa.trustPassword);
            host.setTruststoreType("PKCS12");
        }
        protocol.addSslHostConfig(host);
        factory.addAdditionalConnectors(c);
    }
}
