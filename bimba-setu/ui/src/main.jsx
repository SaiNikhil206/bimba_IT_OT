import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.jsx";
import { SessionProvider } from "./session.jsx";
import { ToastProvider } from "./ui.jsx";
import "./styles.css";

try {
  const t = localStorage.getItem("bimba.gw.theme");
  if (t) document.documentElement.dataset.theme = t;
} catch {
  /* default theme */
}

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <BrowserRouter>
      <SessionProvider>
        <ToastProvider>
          <App />
        </ToastProvider>
      </SessionProvider>
    </BrowserRouter>
  </StrictMode>,
);
