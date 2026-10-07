package com.bimba.ignition;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ignition gateway entry point of the BIMBA IT/OT Agent module. */
public class GatewayHook extends AbstractGatewayModuleHook {
    private static final Logger log = LoggerFactory.getLogger("BIMBA");

    private GatewayContext context;
    private BimbaService service;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
    }

    @Override
    public void startup(LicenseState activationState) {
        service = new BimbaService(context);
        StatusServlet.service = service;
        context.getWebResourceManager().addServlet(StatusServlet.PATH, StatusServlet.class);
        service.start();
        log.info("BIMBA IT/OT Agent module started (status: /system/{})", StatusServlet.PATH);
    }

    @Override
    public void shutdown() {
        try {
            context.getWebResourceManager().removeServlet(StatusServlet.PATH);
        } catch (Exception e) {
            log.debug("servlet removal", e);
        }
        if (service != null) {
            service.stop();
        }
        StatusServlet.service = null;
        log.info("BIMBA IT/OT Agent module stopped");
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }
}
