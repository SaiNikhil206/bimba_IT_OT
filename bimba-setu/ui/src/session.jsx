import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, onSessionEvents, setShownOrg } from "./api.js";

// Who is signed in, the organization they work in (and the others they can switch to), their permissions there, and the
// password / session policy shown on the screens.

const SessionContext = createContext(null);

export function SessionProvider({ children }) {
  const [state, setState] = useState({ loading: true, user: null, organizations: [], policy: {}, version: "", message: null, error: null, notice: null });

  const apply = useCallback((user, organizations) => {
    setShownOrg(user?.org?.id);
    setState((o) => ({ ...o, loading: false, user, organizations: organizations ?? [], error: null }));
  }, []);

  const reload = useCallback(async () => {
    try {
      const s = await api("session");
      setShownOrg(s.user?.org?.id);
      setState((o) => ({ ...o, loading: false, user: s.user, organizations: s.organizations ?? [], policy: s.policy, version: s.version, error: null }));
      return s.user;
    } catch (e) {
      setState((o) => ({ ...o, loading: false, error: e.message }));
      return null;
    }
  }, []);

  const signedOut = useCallback((message) => {
    setShownOrg(null);
    setState((o) => ({ ...o, user: null, organizations: [], message }));
  }, []);
  const signedIn = useCallback((user, organizations) => {
    apply(user, organizations);
    setState((o) => ({ ...o, message: null }));
  }, [apply]);

  useEffect(() => {
    onSessionEvents({
      signedOut,
      passwordRequired: () => setState((o) => (o.user ? { ...o, user: { ...o.user, mustChangePassword: true } } : o)),
      // another tab switched the organization: show that one here too
      orgChanged: async (message) => {
        await reload();
        setState((o) => ({ ...o, notice: message }));
      },
    });
    reload();
  }, [reload, signedOut]);

  const logout = useCallback(async () => {
    try {
      await api("logout", { method: "POST" });
    } catch {
      /* already signed out */
    }
    signedOut("You are signed out.");
  }, [signedOut]);

  /** works in another organization from now on; every page shows that organization's data */
  const switchOrg = useCallback(async (orgId) => {
    await api("session/organization", { method: "POST", body: { orgId } });
    await reload();
  }, [reload]);

  const clearNotice = useCallback(() => setState((o) => ({ ...o, notice: null })), []);

  const value = useMemo(() => ({
    ...state,
    org: state.user?.org ?? null,
    can: (p) => !!state.user && state.user.permissions.includes(p),
    // held for the whole organization (not only through role assignments limited to some sites, agents or machines)
    everywhere: (p) => !!state.user && state.user.permissions.includes(p) && !state.user.scopes?.[p],
    reload, signedIn, signedOut, logout, switchOrg, clearNotice,
  }), [state, reload, signedIn, signedOut, logout, switchOrg, clearNotice]);

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export const useSession = () => useContext(SessionContext);
