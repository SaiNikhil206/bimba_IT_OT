using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using FILETIME = System.Runtime.InteropServices.ComTypes.FILETIME;

namespace Bimba.OpcDa
{
    // OPC Data Access 2.05a custom interfaces (opcda.idl, OPC Foundation), declared here so no OPC Foundation runtime
    // assembly is needed. Only what the bridge uses; method order must match the vtable exactly.

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    internal struct OPCITEMDEF
    {
        [MarshalAs(UnmanagedType.LPWStr)] public string szAccessPath;
        [MarshalAs(UnmanagedType.LPWStr)] public string szItemID;
        public int bActive;
        public int hClient;
        public int dwBlobSize;
        public IntPtr pBlob;
        public short vtRequestedDataType;
        public short wReserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct OPCITEMRESULT
    {
        public int hServer;
        public short vtCanonicalDataType;
        public short wReserved;
        public int dwAccessRights;
        public int dwBlobSize;
        public IntPtr pBlob;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct OPCITEMSTATE
    {
        public int hClient;
        public FILETIME ftTimeStamp;
        public short wQuality;
        public short wReserved;
        [MarshalAs(UnmanagedType.Struct)] public object vDataValue;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    internal struct OPCSERVERSTATUS
    {
        public FILETIME ftStartTime;
        public FILETIME ftCurrentTime;
        public FILETIME ftLastUpdateTime;
        public int dwServerState;
        public int dwGroupCount;
        public int dwBandWidth;
        public short wMajorVersion;
        public short wMinorVersion;
        public short wBuildNumber;
        public short wReserved;
        [MarshalAs(UnmanagedType.LPWStr)] public string szVendorInfo;
    }

    internal static class Opc
    {
        public const int DS_CACHE = 1, DS_DEVICE = 2;
        public const int NS_HIERARCHIAL = 1, NS_FLAT = 2;
        public const int BROWSE_UP = 1, BROWSE_DOWN = 2, BROWSE_TO = 3;
        public const int BRANCH = 1, LEAF = 2, FLAT = 3;
        public static readonly Guid IID_IOPCItemMgt = new Guid("39c13a54-011e-11d0-9675-0020afd8adb3");
        public static readonly Guid OpcEnumClsid = new Guid("13486D51-4821-11D2-A494-3CBA00000000");

        public static readonly string[] States = { "", "running", "failed", "no configuration", "suspended", "test", "communication fault" };
    }

    [ComImport, Guid("39c13a4d-011e-11d0-9675-0020afd8adb3"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IOPCServer
    {
        void AddGroup([MarshalAs(UnmanagedType.LPWStr)] string szName, int bActive, int dwRequestedUpdateRate, int hClientGroup,
                      IntPtr pTimeBias, IntPtr pPercentDeadband, int dwLCID, out int phServerGroup, out int pRevisedUpdateRate,
                      ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppUnk);
        void GetErrorString(int dwError, int dwLocale, [MarshalAs(UnmanagedType.LPWStr)] out string ppString);
        void GetGroupByName([MarshalAs(UnmanagedType.LPWStr)] string szName, ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppUnk);
        void GetStatus(out IntPtr ppServerStatus);
        void RemoveGroup(int hServerGroup, int bForce);
        void CreateGroupEnumerator(int dwScope, ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppUnk);
    }

    [ComImport, Guid("39c13a54-011e-11d0-9675-0020afd8adb3"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IOPCItemMgt
    {
        void AddItems(int dwCount, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] OPCITEMDEF[] pItemArray,
                      out IntPtr ppAddResults, out IntPtr ppErrors);
        void ValidateItems(int dwCount, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] OPCITEMDEF[] pItemArray,
                           int bBlobUpdate, out IntPtr ppValidationResults, out IntPtr ppErrors);
        void RemoveItems(int dwCount, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] int[] phServer, out IntPtr ppErrors);
        void SetActiveState(int dwCount, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] int[] phServer, int bActive, out IntPtr ppErrors);
        void SetClientHandles(int dwCount, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] int[] phServer,
                              [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] int[] phClient, out IntPtr ppErrors);
        void SetDatatypes(int dwCount, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] int[] phServer,
                          [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] short[] pRequestedDatatypes, out IntPtr ppErrors);
        void CreateEnumerator(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppUnk);
    }

    [ComImport, Guid("39c13a52-011e-11d0-9675-0020afd8adb3"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IOPCSyncIO
    {
        void Read(int dwSource, int dwCount, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 1)] int[] phServer,
                  out IntPtr ppItemValues, out IntPtr ppErrors);
        void Write(int dwCount, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] int[] phServer,
                   [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] object[] pItemValues, out IntPtr ppErrors);
    }

    [ComImport, Guid("39c13a4f-011e-11d0-9675-0020afd8adb3"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IOPCBrowseServerAddressSpace
    {
        void QueryOrganization(out int pNameSpaceType);
        void ChangeBrowsePosition(int dwBrowseDirection, [MarshalAs(UnmanagedType.LPWStr)] string szString);
        void BrowseOPCItemIDs(int dwBrowseFilterType, [MarshalAs(UnmanagedType.LPWStr)] string szFilterCriteria, short vtDataTypeFilter,
                              int dwAccessRightsFilter, out IEnumString ppIEnumString);
        void GetItemID([MarshalAs(UnmanagedType.LPWStr)] string szItemDataID, [MarshalAs(UnmanagedType.LPWStr)] out string szItemID);
        void BrowseAccessPaths([MarshalAs(UnmanagedType.LPWStr)] string szItemID, out IEnumString ppIEnumString);
    }

    [ComImport, Guid("F31DFDE2-07B6-11d2-B2D8-0060083BA1FB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IOPCCommon
    {
        void SetLocaleID(int dwLcid);
        void GetLocaleID(out int pdwLcid);
        void QueryAvailableLocaleIDs(out int pdwCount, out IntPtr pdwLcid);
        void GetErrorString(int dwError, [MarshalAs(UnmanagedType.LPWStr)] out string ppString);
        void SetClientName([MarshalAs(UnmanagedType.LPWStr)] string szName);
    }

    /// <summary>OpcEnum (OPC Core Components): resolves a ProgID on another computer.</summary>
    [ComImport, Guid("13486D50-4821-11D2-A494-3CBA00000000"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IOPCServerList
    {
        void EnumClassesOfCategories(int cImplemented, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] Guid[] rgcatidImpl,
                                     int cRequired, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 2)] Guid[] rgcatidReq,
                                     [MarshalAs(UnmanagedType.IUnknown)] out object ppenumClsid);
        void GetClassDetails(ref Guid clsid, [MarshalAs(UnmanagedType.LPWStr)] out string ppszProgID,
                             [MarshalAs(UnmanagedType.LPWStr)] out string ppszUserType);
        void CLSIDFromProgID([MarshalAs(UnmanagedType.LPWStr)] string szProgId, out Guid clsid);
    }

    internal static class Native
    {
        [DllImport("ole32.dll")]
        public static extern int CoInitializeSecurity(IntPtr pSecDesc, int cAuthSvc, IntPtr asAuthSvc, IntPtr pReserved1, int dwAuthnLevel,
                                                      int dwImpLevel, IntPtr pAuthList, int dwCapabilities, IntPtr pReserved3);

        public const int RPC_C_AUTHN_LEVEL_PKT_INTEGRITY = 5;
        public const int RPC_C_IMP_LEVEL_IMPERSONATE = 3;
        public const int EOAC_NONE = 0;
    }
}
