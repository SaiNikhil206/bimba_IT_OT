package com.bimba.setu.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The React console's page URLs all load index.html; React Router shows the page (ui/src/App.jsx). An organization's
 * pages start with its slug (/northwind-water/pipelines), a record's address adds its public id or "new"
 * (/northwind-water/pipelines/{uid}). The addresses without a slug are the profile, the platform pages and the
 * addresses before organization slugs, which the console moves under the organization. Slugs may not be one of these
 * first segments (OrgService.RESERVED).
 */
@Controller
public class SpaController {
    private static final String ORG = "{org:[a-z0-9][a-z0-9-]*}";

    @GetMapping({"/overview", "/pipelines", "/destinations", "/delivery", "/quarantine", "/registry", "/machines", "/commands", "/agents", "/tokens", "/users", "/roles", "/audit", "/profile", "/login",
            "/organizations", "/forgot-password", "/reset-password",
            "/pipelines/{uid}", "/destinations/{uid}", "/users/{uid}", "/roles/{uid}", "/organizations/{uid}", "/agents/{uid}", "/machines/{uid}", "/commands/{uid}",
            "/" + ORG + "/{page:overview|agents|machines|commands|tokens|pipelines|destinations|delivery|quarantine|registry|users|roles|audit}",
            "/" + ORG + "/{page:pipelines|destinations|users|roles|agents|machines|commands}/{uid}"})
    String page() {
        return "forward:/index.html";
    }
}
