package com.bimba.setu;

import com.bimba.setu.agents.AgentCa;
import com.bimba.setu.web.Tls;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BIMBA Setu: our own SCADA edge server (Spring Boot).
 * <pre>
 *   BIMBA_CONFIG_DIR   config.json, tag tree, device connections (default ./config)
 *   BIMBA_DATA_DIR     disk buffer + HTTPS key store (default ./data)
 *   BIMBA_HTTP_PORT    default 8090
 *   BIMBA_HTTPS_PORT   default 8443 (0 = off); self-signed certificate created on first start
 *   BIMBA_TLS_NAMES    extra host names / IPs for that certificate, comma separated
 *   BIMBA_TLS_CERT + BIMBA_TLS_KEY (PEM) or BIMBA_TLS_PFX + BIMBA_TLS_PFX_PASSWORD
 *                      the customer's HTTPS certificate instead of the self-signed one (see web/Tls)
 *   BIMBA_DB_*         shared PostgreSQL (site agent registry, console users and roles)
 *   BIMBA_INSTALLER_DIR  folder with BIMBA-Site-Agent-Setup.exe offered in the console (default /installers)
 * </pre>
 * See application.yml for the Spring settings these map to.
 */
@SpringBootApplication
@EnableScheduling
public class BimbaSetuApplication {
    public static final String VERSION = "2.1.0";

    public static void main(String[] args) throws Exception {
        // destinations behind an HTTP proxy with a user name (HTTPS goes through CONNECT, where the JDK refuses Basic by default)
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
        // the HTTPS key store must exist before the web server starts
        Tls.prepare(System.getenv("BIMBA_DATA_DIR"), System.getenv("BIMBA_HTTPS_PORT"), System.getenv("BIMBA_TLS_NAMES"));
        AgentCa.prepare(System.getenv("BIMBA_DATA_DIR"));
        SpringApplication.run(BimbaSetuApplication.class, args);
    }
}
