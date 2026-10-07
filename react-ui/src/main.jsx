import React, { Suspense, lazy } from "react";
import { createRoot } from "react-dom/client";

// Pages, each with its own stylesheet: /console = OT/IT Bridge Console, /control-room = BIMBA
// Control Room, /operations = BIMBA Operations, everything else = BIMBA live UI. Lazy imports keep
// each page's CSS out of the others. Keep them as separate lazy() calls: one call with a conditional
// import gets a single preload list and loads the wrong stylesheet.
const ConsolePage = lazy(() => import("./console/Console.jsx"));
const ControlRoomPage = lazy(() => import("./control/ControlRoom.jsx"));
const OperationsPage = lazy(() => import("./ops/Ops.jsx"));
const LivePage = lazy(() => import("./App.jsx"));
const PAGES = { "/console": ConsolePage, "/control-room": ControlRoomPage, "/operations": OperationsPage };
const path = window.location.pathname.replace(/\/+$/, "");
// sub-paths (/control-room/machine/<ID>, /operations/machines/<ID>, ...) are routed inside each app
const Page = PAGES[path]
  ?? (path.startsWith("/control-room/") ? ControlRoomPage : path.startsWith("/operations/") ? OperationsPage : LivePage);

createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <Suspense fallback={null}>
      <Page />
    </Suspense>
  </React.StrictMode>
);
