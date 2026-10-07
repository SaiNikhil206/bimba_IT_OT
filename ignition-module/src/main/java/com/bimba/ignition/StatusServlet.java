package com.bimba.ignition;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** GET http://&lt;gateway&gt;:8088/system/bimba-status  ->  module health as JSON. */
public class StatusServlet extends HttpServlet {
    public static final String PATH = "bimba-status";
    static volatile BimbaService service;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        BimbaService s = service;
        if (s == null) {
            resp.setStatus(503);
            resp.getWriter().write("{\"error\":\"BIMBA IT/OT Agent not started\"}");
            return;
        }
        resp.getWriter().write(Json.PRETTY.toJson(s.status()));
    }
}
