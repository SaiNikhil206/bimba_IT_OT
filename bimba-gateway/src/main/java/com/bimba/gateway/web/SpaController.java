package com.bimba.gateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The React console's page URLs all load index.html; React Router shows the page (ui/src/App.jsx). */
@Controller
public class SpaController {
    @GetMapping({"/overview", "/pipelines", "/destinations", "/delivery", "/agents", "/users", "/roles", "/audit", "/profile", "/login",
            "/organizations", "/forgot-password", "/reset-password"})
    String page() {
        return "forward:/index.html";
    }
}
