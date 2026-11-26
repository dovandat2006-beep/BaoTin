#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][int]$ProcessId)
$ErrorActionPreference='Stop'
if(-not ('BaoTinAgentToken' -as [type])) {
    Add-Type @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class BaoTinAgentToken {
  [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
  [DllImport("advapi32.dll", SetLastError=true)] static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
  [DllImport("advapi32.dll", SetLastError=true)] static extern bool GetTokenInformation(IntPtr token, int type, out int value, int size, out int length);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
  public static bool IsElevated(int pid) {
    IntPtr p=OpenProcess(0x1000,false,pid), t=IntPtr.Zero;
    if(p==IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
    try {
      if(!OpenProcessToken(p,8,out t)) throw new Win32Exception(Marshal.GetLastWin32Error());
      int value, length;
      if(!GetTokenInformation(t,20,out value,4,out length)) throw new Win32Exception(Marshal.GetLastWin32Error());
      return value!=0;
    } finally { if(t!=IntPtr.Zero) CloseHandle(t); CloseHandle(p); }
  }
}
'@
}
[BaoTinAgentToken]::IsElevated($ProcessId)
