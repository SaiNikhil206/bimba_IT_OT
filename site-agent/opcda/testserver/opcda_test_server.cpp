// BIMBA OPC DA TEST server (TEST ONLY, never shipped): a tiny native in-process OPC DA 2.0 server, so the OPC DA bridge
// (site-agent/opcda, bimba-opcda.exe) can be tested through real COM vtables without any vendor software.
//   ProgID BIMBA.OpcDaTest.1   CLSID {B1B5A0DA-6F2C-4E3B-9C11-0DA7E5715001}   32-bit, ThreadingModel Both
//   Items: Simulation.Ramp (0..100 every 100 s), Simulation.Sine, Simulation.Counter (seconds), Simulation.Switch (flips
//   every 5 s), Simulation.Text, Faults.CommFailure (BAD 0x18), Faults.Uncertain (UNCERTAIN 0x54).
// Build: build.sh (MinGW-w64 i686 in a container). Register in the VM (elevated): C:\Windows\SysWOW64\regsvr32 /s <dll>
#include <windows.h>
#include <ole2.h>
#include <oleauto.h>
#include <cmath>
#include <cstddef>
#include <cwchar>
#include <map>
#include <string>
#include <vector>

// ------------------------------------------------------------------ OPC DA 2.0 types (opcda.idl)

struct OPCITEMDEF { LPWSTR szAccessPath; LPWSTR szItemID; BOOL bActive; DWORD hClient; DWORD dwBlobSize; BYTE* pBlob; VARTYPE vtRequestedDataType; WORD wReserved; };
struct OPCITEMRESULT { DWORD hServer; VARTYPE vtCanonicalDataType; WORD wReserved; DWORD dwAccessRights; DWORD dwBlobSize; BYTE* pBlob; };
struct OPCITEMSTATE { DWORD hClient; FILETIME ftTimeStamp; WORD wQuality; WORD wReserved; VARIANT vDataValue; };
struct OPCSERVERSTATUS { FILETIME ftStartTime; FILETIME ftCurrentTime; FILETIME ftLastUpdateTime; DWORD dwServerState; DWORD dwGroupCount;
                         DWORD dwBandWidth; WORD wMajorVersion; WORD wMinorVersion; WORD wBuildNumber; WORD wReserved; LPWSTR szVendorInfo; };
static_assert(sizeof(OPCITEMDEF) == 28, "OPCITEMDEF layout");
static_assert(sizeof(OPCITEMRESULT) == 20, "OPCITEMRESULT layout");
static_assert(offsetof(OPCITEMSTATE, vDataValue) == 16 && sizeof(OPCITEMSTATE) == 32, "OPCITEMSTATE layout");
static_assert(sizeof(OPCSERVERSTATUS) == 48, "OPCSERVERSTATUS layout");

static const GUID IID_IOPCServer = {0x39c13a4d, 0x011e, 0x11d0, {0x96, 0x75, 0x00, 0x20, 0xaf, 0xd8, 0xad, 0xb3}};
static const GUID IID_IOPCBrowseServerAddressSpace = {0x39c13a4f, 0x011e, 0x11d0, {0x96, 0x75, 0x00, 0x20, 0xaf, 0xd8, 0xad, 0xb3}};
static const GUID IID_IOPCSyncIO = {0x39c13a52, 0x011e, 0x11d0, {0x96, 0x75, 0x00, 0x20, 0xaf, 0xd8, 0xad, 0xb3}};
static const GUID IID_IOPCItemMgt = {0x39c13a54, 0x011e, 0x11d0, {0x96, 0x75, 0x00, 0x20, 0xaf, 0xd8, 0xad, 0xb3}};
static const GUID IID_IOPCCommon = {0xF31DFDE2, 0x07B6, 0x11d2, {0xB2, 0xD8, 0x00, 0x60, 0x08, 0x3B, 0xA1, 0xFB}};
static const GUID CLSID_Test = {0xB1B5A0DA, 0x6F2C, 0x4E3B, {0x9C, 0x11, 0x0D, 0xA7, 0xE5, 0x71, 0x50, 0x01}};
static const wchar_t* CLSID_TEXT = L"{B1B5A0DA-6F2C-4E3B-9C11-0DA7E5715001}";
static const wchar_t* PROGID = L"BIMBA.OpcDaTest.1";
static const wchar_t* CATID_DA20 = L"{63D5F432-CFE4-11d1-B2C8-0060083BA1FB}";

static const HRESULT OPC_E_INVALIDHANDLE = (HRESULT)0xC0040001L;
static const HRESULT OPC_E_UNKNOWNITEMID = (HRESULT)0xC0040007L;

struct IOPCServer : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE AddGroup(LPCWSTR, BOOL, DWORD, DWORD, LONG*, FLOAT*, DWORD, DWORD*, DWORD*, REFIID, LPUNKNOWN*) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetErrorString(HRESULT, LCID, LPWSTR*) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetGroupByName(LPCWSTR, REFIID, LPUNKNOWN*) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetStatus(OPCSERVERSTATUS**) = 0;
    virtual HRESULT STDMETHODCALLTYPE RemoveGroup(DWORD, BOOL) = 0;
    virtual HRESULT STDMETHODCALLTYPE CreateGroupEnumerator(DWORD, REFIID, LPUNKNOWN*) = 0;
};
struct IOPCBrowseServerAddressSpace : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE QueryOrganization(DWORD*) = 0;
    virtual HRESULT STDMETHODCALLTYPE ChangeBrowsePosition(DWORD, LPCWSTR) = 0;
    virtual HRESULT STDMETHODCALLTYPE BrowseOPCItemIDs(DWORD, LPCWSTR, VARTYPE, DWORD, LPENUMSTRING*) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetItemID(LPWSTR, LPWSTR*) = 0;
    virtual HRESULT STDMETHODCALLTYPE BrowseAccessPaths(LPCWSTR, LPENUMSTRING*) = 0;
};
struct IOPCCommon : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE SetLocaleID(LCID) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetLocaleID(LCID*) = 0;
    virtual HRESULT STDMETHODCALLTYPE QueryAvailableLocaleIDs(DWORD*, LCID**) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetErrorString(HRESULT, LPWSTR*) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetClientName(LPCWSTR) = 0;
};
struct IOPCItemMgt : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE AddItems(DWORD, OPCITEMDEF*, OPCITEMRESULT**, HRESULT**) = 0;
    virtual HRESULT STDMETHODCALLTYPE ValidateItems(DWORD, OPCITEMDEF*, BOOL, OPCITEMRESULT**, HRESULT**) = 0;
    virtual HRESULT STDMETHODCALLTYPE RemoveItems(DWORD, DWORD*, HRESULT**) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetActiveState(DWORD, DWORD*, BOOL, HRESULT**) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetClientHandles(DWORD, DWORD*, DWORD*, HRESULT**) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetDatatypes(DWORD, DWORD*, VARTYPE*, HRESULT**) = 0;
    virtual HRESULT STDMETHODCALLTYPE CreateEnumerator(REFIID, LPUNKNOWN*) = 0;
};
struct IOPCSyncIO : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE Read(DWORD, DWORD, DWORD*, OPCITEMSTATE**, HRESULT**) = 0;
    virtual HRESULT STDMETHODCALLTYPE Write(DWORD, DWORD*, VARIANT*, HRESULT**) = 0;
};

// ------------------------------------------------------------------ the simulated address space

static LONG g_objects = 0;
static DWORD g_start = GetTickCount();
static FILETIME g_startTime;

struct SimItem { const wchar_t* branch; const wchar_t* name; VARTYPE vt; };
static const SimItem ITEMS[] = {
    {L"Simulation", L"Ramp", VT_R8}, {L"Simulation", L"Sine", VT_R8}, {L"Simulation", L"Counter", VT_I4},
    {L"Simulation", L"Switch", VT_BOOL}, {L"Simulation", L"Text", VT_BSTR},
    {L"Faults", L"CommFailure", VT_R8}, {L"Faults", L"Uncertain", VT_R8},
};
static const int ITEM_COUNT = sizeof(ITEMS) / sizeof(ITEMS[0]);
static const wchar_t* BRANCHES[] = {L"Simulation", L"Faults"};

static std::wstring fullId(int i) { return std::wstring(ITEMS[i].branch) + L"." + ITEMS[i].name; }

static int findItem(LPCWSTR id) {
    if (!id) return -1;
    for (int i = 0; i < ITEM_COUNT; i++)
        if (fullId(i) == id) return i;
    return -1;
}

/** the current value and quality of item i */
static void sample(int i, VARIANT* v, WORD* quality) {
    double t = (GetTickCount() - g_start) / 1000.0;
    VariantInit(v);
    *quality = 0xC0;
    std::wstring n = fullId(i);
    if (n == L"Simulation.Ramp") { v->vt = VT_R8; v->dblVal = std::fmod(t, 100.0); }
    else if (n == L"Simulation.Sine") { v->vt = VT_R8; v->dblVal = 50 + 50 * std::sin(t / 10); }
    else if (n == L"Simulation.Counter") { v->vt = VT_I4; v->lVal = (LONG)t; }
    else if (n == L"Simulation.Switch") { v->vt = VT_BOOL; v->boolVal = ((long)(t / 5)) % 2 ? VARIANT_TRUE : VARIANT_FALSE; }
    else if (n == L"Simulation.Text") { v->vt = VT_BSTR; v->bstrVal = SysAllocString(L"BIMBA test"); }
    else if (n == L"Faults.CommFailure") { v->vt = VT_R8; v->dblVal = 0; *quality = 0x18; }
    else { v->vt = VT_R8; v->dblVal = 101.5; *quality = 0x54; }
}

static LPWSTR coString(const std::wstring& s) {
    size_t bytes = (s.size() + 1) * sizeof(wchar_t);
    LPWSTR p = (LPWSTR)CoTaskMemAlloc(bytes);
    if (p) memcpy(p, s.c_str(), bytes);
    return p;
}

static FILETIME now() {
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    return ft;
}

static HRESULT errorText(HRESULT hr, LPWSTR* out) {
    if (!out) return E_INVALIDARG;
    const wchar_t* s = hr == OPC_E_UNKNOWNITEMID ? L"The item ID is not defined in the server address space."
                     : hr == OPC_E_INVALIDHANDLE ? L"The value of the handle is invalid." : L"Unknown error.";
    *out = coString(s);
    return S_OK;
}

// ------------------------------------------------------------------ IEnumString

class SimEnumString : public IEnumString {
    LONG refs = 1;
    std::vector<std::wstring> items;
    size_t pos = 0;
public:
    explicit SimEnumString(std::vector<std::wstring> v) : items(std::move(v)) { InterlockedIncrement(&g_objects); }
    virtual ~SimEnumString() { InterlockedDecrement(&g_objects); }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (riid == IID_IUnknown || riid == IID_IEnumString) { *ppv = this; AddRef(); return S_OK; }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
    ULONG STDMETHODCALLTYPE Release() override { LONG r = InterlockedDecrement(&refs); if (!r) delete this; return r; }
    HRESULT STDMETHODCALLTYPE Next(ULONG celt, LPOLESTR* rgelt, ULONG* fetched) override {
        ULONG n = 0;
        while (n < celt && pos < items.size()) rgelt[n++] = coString(items[pos++]);
        if (fetched) *fetched = n;
        return n == celt ? S_OK : S_FALSE;
    }
    HRESULT STDMETHODCALLTYPE Skip(ULONG celt) override { pos += celt; return pos <= items.size() ? S_OK : S_FALSE; }
    HRESULT STDMETHODCALLTYPE Reset() override { pos = 0; return S_OK; }
    HRESULT STDMETHODCALLTYPE Clone(IEnumString** pp) override { *pp = new SimEnumString(items); return S_OK; }
};

// ------------------------------------------------------------------ group

class SimGroup : public IOPCItemMgt, public IOPCSyncIO {
    LONG refs = 1;
    struct Entry { int item; DWORD client; bool live; };
    std::vector<Entry> entries;               // server handle = index + 1
    CRITICAL_SECTION cs;
public:
    SimGroup() { InitializeCriticalSection(&cs); InterlockedIncrement(&g_objects); }
    virtual ~SimGroup() { DeleteCriticalSection(&cs); InterlockedDecrement(&g_objects); }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (riid == IID_IUnknown || riid == IID_IOPCItemMgt) *ppv = static_cast<IOPCItemMgt*>(this);
        else if (riid == IID_IOPCSyncIO) *ppv = static_cast<IOPCSyncIO*>(this);
        else { *ppv = nullptr; return E_NOINTERFACE; }
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
    ULONG STDMETHODCALLTYPE Release() override { LONG r = InterlockedDecrement(&refs); if (!r) delete this; return r; }

    HRESULT add(DWORD count, OPCITEMDEF* defs, OPCITEMRESULT** results, HRESULT** errors, bool keep) {
        if (!defs || !results || !errors) return E_INVALIDARG;
        *results = (OPCITEMRESULT*)CoTaskMemAlloc(sizeof(OPCITEMRESULT) * count);
        *errors = (HRESULT*)CoTaskMemAlloc(sizeof(HRESULT) * count);
        memset(*results, 0, sizeof(OPCITEMRESULT) * count);
        bool all = true;
        EnterCriticalSection(&cs);
        for (DWORD i = 0; i < count; i++) {
            int item = findItem(defs[i].szItemID);
            if (item < 0) { (*errors)[i] = OPC_E_UNKNOWNITEMID; all = false; continue; }
            (*errors)[i] = S_OK;
            if (keep) {
                entries.push_back({item, defs[i].hClient, true});
                (*results)[i].hServer = (DWORD)entries.size();
            }
            (*results)[i].vtCanonicalDataType = ITEMS[item].vt;
            (*results)[i].dwAccessRights = 1;              // OPC_READABLE
        }
        LeaveCriticalSection(&cs);
        return all ? S_OK : S_FALSE;
    }
    HRESULT STDMETHODCALLTYPE AddItems(DWORD n, OPCITEMDEF* d, OPCITEMRESULT** r, HRESULT** e) override { return add(n, d, r, e, true); }
    HRESULT STDMETHODCALLTYPE ValidateItems(DWORD n, OPCITEMDEF* d, BOOL, OPCITEMRESULT** r, HRESULT** e) override { return add(n, d, r, e, false); }
    HRESULT STDMETHODCALLTYPE RemoveItems(DWORD n, DWORD* h, HRESULT** e) override {
        *e = (HRESULT*)CoTaskMemAlloc(sizeof(HRESULT) * n);
        EnterCriticalSection(&cs);
        for (DWORD i = 0; i < n; i++) {
            bool ok = h[i] >= 1 && h[i] <= entries.size() && entries[h[i] - 1].live;
            if (ok) entries[h[i] - 1].live = false;
            (*e)[i] = ok ? S_OK : OPC_E_INVALIDHANDLE;
        }
        LeaveCriticalSection(&cs);
        return S_OK;
    }
    HRESULT okArray(DWORD n, HRESULT** e) { *e = (HRESULT*)CoTaskMemAlloc(sizeof(HRESULT) * n); memset(*e, 0, sizeof(HRESULT) * n); return S_OK; }
    HRESULT STDMETHODCALLTYPE SetActiveState(DWORD n, DWORD*, BOOL, HRESULT** e) override { return okArray(n, e); }
    HRESULT STDMETHODCALLTYPE SetClientHandles(DWORD n, DWORD*, DWORD*, HRESULT** e) override { return okArray(n, e); }
    HRESULT STDMETHODCALLTYPE SetDatatypes(DWORD n, DWORD*, VARTYPE*, HRESULT** e) override { return okArray(n, e); }
    HRESULT STDMETHODCALLTYPE CreateEnumerator(REFIID, LPUNKNOWN* pp) override { *pp = nullptr; return E_NOTIMPL; }

    HRESULT STDMETHODCALLTYPE Read(DWORD, DWORD n, DWORD* h, OPCITEMSTATE** values, HRESULT** errors) override {
        if (!h || !values || !errors) return E_INVALIDARG;
        *values = (OPCITEMSTATE*)CoTaskMemAlloc(sizeof(OPCITEMSTATE) * n);
        *errors = (HRESULT*)CoTaskMemAlloc(sizeof(HRESULT) * n);
        memset(*values, 0, sizeof(OPCITEMSTATE) * n);
        bool all = true;
        FILETIME t = now();
        EnterCriticalSection(&cs);
        for (DWORD i = 0; i < n; i++) {
            OPCITEMSTATE& s = (*values)[i];
            VariantInit(&s.vDataValue);
            if (h[i] < 1 || h[i] > entries.size() || !entries[h[i] - 1].live) { (*errors)[i] = OPC_E_INVALIDHANDLE; all = false; continue; }
            Entry& en = entries[h[i] - 1];
            s.hClient = en.client;
            s.ftTimeStamp = t;
            sample(en.item, &s.vDataValue, &s.wQuality);
            (*errors)[i] = S_OK;
        }
        LeaveCriticalSection(&cs);
        return all ? S_OK : S_FALSE;
    }
    HRESULT STDMETHODCALLTYPE Write(DWORD, DWORD*, VARIANT*, HRESULT**) override { return E_NOTIMPL; }
};

// ------------------------------------------------------------------ server

class SimServer : public IOPCServer, public IOPCBrowseServerAddressSpace, public IOPCCommon {
    LONG refs = 1;
    std::wstring position;                    // "" = root, else a branch
    std::map<DWORD, SimGroup*> groups;
    DWORD nextGroup = 1;
    CRITICAL_SECTION cs;
public:
    SimServer() { InitializeCriticalSection(&cs); InterlockedIncrement(&g_objects); }
    virtual ~SimServer() {
        for (auto& g : groups) g.second->Release();
        DeleteCriticalSection(&cs);
        InterlockedDecrement(&g_objects);
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (riid == IID_IUnknown || riid == IID_IOPCServer) *ppv = static_cast<IOPCServer*>(this);
        else if (riid == IID_IOPCBrowseServerAddressSpace) *ppv = static_cast<IOPCBrowseServerAddressSpace*>(this);
        else if (riid == IID_IOPCCommon) *ppv = static_cast<IOPCCommon*>(this);
        else { *ppv = nullptr; return E_NOINTERFACE; }
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
    ULONG STDMETHODCALLTYPE Release() override { LONG r = InterlockedDecrement(&refs); if (!r) delete this; return r; }

    // IOPCServer
    HRESULT STDMETHODCALLTYPE AddGroup(LPCWSTR, BOOL, DWORD rate, DWORD, LONG*, FLOAT*, DWORD, DWORD* handle, DWORD* revised,
                                       REFIID riid, LPUNKNOWN* unk) override {
        if (!handle || !revised || !unk) return E_INVALIDARG;
        SimGroup* g = new SimGroup();
        HRESULT hr = g->QueryInterface(riid, (void**)unk);
        if (FAILED(hr)) { g->Release(); return hr; }
        EnterCriticalSection(&cs);
        *handle = nextGroup++;
        groups[*handle] = g;                  // keeps the creation reference until RemoveGroup
        LeaveCriticalSection(&cs);
        *revised = rate < 100 ? 100 : rate;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetErrorString(HRESULT hr, LCID, LPWSTR* s) override { return errorText(hr, s); }
    HRESULT STDMETHODCALLTYPE GetGroupByName(LPCWSTR, REFIID, LPUNKNOWN* pp) override { *pp = nullptr; return E_FAIL; }
    HRESULT STDMETHODCALLTYPE GetStatus(OPCSERVERSTATUS** pp) override {
        if (!pp) return E_INVALIDARG;
        OPCSERVERSTATUS* s = (OPCSERVERSTATUS*)CoTaskMemAlloc(sizeof(OPCSERVERSTATUS));
        memset(s, 0, sizeof(*s));
        s->ftStartTime = g_startTime;
        s->ftCurrentTime = now();
        s->ftLastUpdateTime = s->ftCurrentTime;
        s->dwServerState = 1;                 // OPC_STATUS_RUNNING
        s->dwGroupCount = (DWORD)groups.size();
        s->wMajorVersion = 1;
        s->wMinorVersion = 0;
        s->wBuildNumber = 7;
        s->szVendorInfo = coString(L"BIMBA OPC DA test server");
        *pp = s;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE RemoveGroup(DWORD handle, BOOL) override {
        EnterCriticalSection(&cs);
        auto it = groups.find(handle);
        SimGroup* g = it == groups.end() ? nullptr : it->second;
        if (g) groups.erase(it);
        LeaveCriticalSection(&cs);
        if (!g) return E_INVALIDARG;
        g->Release();
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE CreateGroupEnumerator(DWORD, REFIID, LPUNKNOWN* pp) override { *pp = nullptr; return E_NOTIMPL; }

    // IOPCBrowseServerAddressSpace
    HRESULT STDMETHODCALLTYPE QueryOrganization(DWORD* t) override { *t = 1; return S_OK; }      // OPC_NS_HIERARCHIAL
    HRESULT STDMETHODCALLTYPE ChangeBrowsePosition(DWORD dir, LPCWSTR s) override {
        std::wstring to = s ? s : L"";
        if (dir == 1) { position.clear(); return S_OK; }                                       // UP
        if (dir == 3 && to.empty()) { position.clear(); return S_OK; }                         // TO root
        for (auto b : BRANCHES) if (to == b) { position = to; return S_OK; }                   // DOWN / TO a branch
        return E_INVALIDARG;
    }
    HRESULT STDMETHODCALLTYPE BrowseOPCItemIDs(DWORD type, LPCWSTR, VARTYPE, DWORD, LPENUMSTRING* pp) override {
        std::vector<std::wstring> out;
        if (type == 3) { for (int i = 0; i < ITEM_COUNT; i++) out.push_back(fullId(i)); }      // FLAT
        else if (type == 1 && position.empty()) { for (auto b : BRANCHES) out.push_back(b); }  // BRANCH at the root
        else if (type == 2 && !position.empty()) {                                             // LEAF in a branch
            for (int i = 0; i < ITEM_COUNT; i++) if (position == ITEMS[i].branch) out.push_back(ITEMS[i].name);
        }
        bool any = !out.empty();
        *pp = new SimEnumString(std::move(out));
        return any ? S_OK : S_FALSE;
    }
    HRESULT STDMETHODCALLTYPE GetItemID(LPWSTR name, LPWSTR* id) override {
        if (!name || !id) return E_INVALIDARG;
        *id = coString(position.empty() ? std::wstring(name) : position + L"." + name);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE BrowseAccessPaths(LPCWSTR, LPENUMSTRING* pp) override { *pp = nullptr; return E_NOTIMPL; }

    // IOPCCommon
    HRESULT STDMETHODCALLTYPE SetLocaleID(LCID) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE GetLocaleID(LCID* l) override { *l = 0x409; return S_OK; }
    HRESULT STDMETHODCALLTYPE QueryAvailableLocaleIDs(DWORD* n, LCID** l) override {
        *n = 1;
        *l = (LCID*)CoTaskMemAlloc(sizeof(LCID));
        (*l)[0] = 0x409;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetErrorString(HRESULT hr, LPWSTR* s) override { return errorText(hr, s); }
    HRESULT STDMETHODCALLTYPE SetClientName(LPCWSTR) override { return S_OK; }
};

// ------------------------------------------------------------------ class factory + DLL exports

class Factory : public IClassFactory {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (riid == IID_IUnknown || riid == IID_IClassFactory) { *ppv = this; return S_OK; }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return 2; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* outer, REFIID riid, void** ppv) override {
        if (outer) return CLASS_E_NOAGGREGATION;
        SimServer* s = new SimServer();
        HRESULT hr = s->QueryInterface(riid, ppv);
        s->Release();
        return hr;
    }
    HRESULT STDMETHODCALLTYPE LockServer(BOOL) override { return S_OK; }
};
static Factory g_factory;
static HMODULE g_module;

extern "C" BOOL WINAPI DllMain(HINSTANCE h, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = h;
        GetSystemTimeAsFileTime(&g_startTime);
        DisableThreadLibraryCalls(h);
    }
    return TRUE;
}

extern "C" HRESULT STDAPICALLTYPE DllGetClassObject(REFCLSID clsid, REFIID riid, LPVOID* ppv) {
    if (clsid != CLSID_Test) return CLASS_E_CLASSNOTAVAILABLE;
    return g_factory.QueryInterface(riid, ppv);
}

extern "C" HRESULT STDAPICALLTYPE DllCanUnloadNow() { return S_FALSE; }

static bool setKey(const std::wstring& path, const wchar_t* name, const std::wstring& value) {
    HKEY k;
    if (RegCreateKeyExW(HKEY_CLASSES_ROOT, path.c_str(), 0, nullptr, 0, KEY_WRITE, nullptr, &k, nullptr) != ERROR_SUCCESS) return false;
    LONG r = RegSetValueExW(k, name, 0, REG_SZ, (const BYTE*)value.c_str(), (DWORD)((value.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(k);
    return r == ERROR_SUCCESS;
}

extern "C" HRESULT STDAPICALLTYPE DllRegisterServer() {
    wchar_t path[MAX_PATH];
    GetModuleFileNameW(g_module, path, MAX_PATH);
    std::wstring clsid = std::wstring(L"CLSID\\") + CLSID_TEXT;
    bool ok = setKey(clsid, nullptr, L"BIMBA OPC DA test server")
              && setKey(clsid + L"\\InprocServer32", nullptr, path)
              && setKey(clsid + L"\\InprocServer32", L"ThreadingModel", L"Both")
              && setKey(clsid + L"\\ProgID", nullptr, PROGID)
              && setKey(clsid + L"\\Implemented Categories\\" + CATID_DA20, nullptr, L"")
              && setKey(PROGID, nullptr, L"BIMBA OPC DA test server")
              && setKey(std::wstring(PROGID) + L"\\CLSID", nullptr, CLSID_TEXT)
              && setKey(std::wstring(PROGID) + L"\\OPC", nullptr, L"");
    return ok ? S_OK : E_ACCESSDENIED;
}

extern "C" HRESULT STDAPICALLTYPE DllUnregisterServer() {
    RegDeleteTreeW(HKEY_CLASSES_ROOT, (std::wstring(L"CLSID\\") + CLSID_TEXT).c_str());
    RegDeleteTreeW(HKEY_CLASSES_ROOT, PROGID);
    return S_OK;
}
