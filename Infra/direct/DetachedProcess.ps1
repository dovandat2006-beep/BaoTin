# Native Windows primary-runtime launcher. Does not elevate the caller or register a service.
if(-not ('BaoTinDetachedProcess' -as [type])) {
Add-Type @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
public static class BaoTinDetachedProcess {
  [StructLayout(LayoutKind.Sequential)] struct SA { public int length; public IntPtr descriptor; [MarshalAs(UnmanagedType.Bool)] public bool inherit; }
  [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct SI {
    public int cb; public string reserved,desktop,title; public int x,y,xSize,ySize,xChars,yChars,fill,flags; public short show,reservedSize; public IntPtr reservedBytes,input,output,error;
  }
  [StructLayout(LayoutKind.Sequential)] struct PI { public IntPtr process,thread; public int pid,tid; }
  [StructLayout(LayoutKind.Sequential)] struct SIEX { public SI startup; public IntPtr attributes; }
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateFile(string path,uint access,uint share,ref SA attributes,uint disposition,uint flags,IntPtr template);
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateProcess(string app,StringBuilder command,IntPtr processAttributes,IntPtr threadAttributes,bool inherit,uint flags,IntPtr environment,string directory,ref SIEX startup,out PI info);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool InitializeProcThreadAttributeList(IntPtr list,int count,int flags,ref IntPtr size);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool UpdateProcThreadAttribute(IntPtr list,uint flags,IntPtr attribute,IntPtr value,IntPtr size,IntPtr previous,IntPtr returned);
  [DllImport("kernel32.dll")] static extern void DeleteProcThreadAttributeList(IntPtr list);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
  static string Quote(string value) {
    var s=new StringBuilder("\""); int slashes=0;
    foreach(char c in value){ if(c=='\\'){slashes++;continue;} if(c=='\"'){s.Append('\\',slashes*2+1);s.Append(c);}else{s.Append('\\',slashes);s.Append(c);}slashes=0; }
    s.Append('\\',slashes*2);return s.Append('"').ToString();
  }
  static IntPtr Open(string path,uint access,uint disposition,ref SA sa) {
    var h=CreateFile(path,access,3,ref sa,disposition,128,IntPtr.Zero);
    if(h==new IntPtr(-1))throw new Win32Exception(Marshal.GetLastWin32Error());return h;
  }
  public static int Start(string exe,string[] args,string directory,Dictionary<string,string> variables,string stdout,string stderr) {
    IntPtr input=IntPtr.Zero,output=IntPtr.Zero,error=IntPtr.Zero,env=IntPtr.Zero,attributes=IntPtr.Zero,handles=IntPtr.Zero;
    bool attributesReady=false;
    var sa=new SA{length=Marshal.SizeOf(typeof(SA)),inherit=true};var info=new PI();
    try {
      input=Open("NUL",0x80000000,3,ref sa);output=Open(stdout,0x40000000,2,ref sa);error=Open(stderr,0x40000000,2,ref sa);
      var block=string.Join("\0",variables.OrderBy(p=>p.Key,StringComparer.OrdinalIgnoreCase).Select(p=>p.Key+"="+p.Value))+"\0\0";
      env=Marshal.StringToHGlobalUni(block);
      IntPtr size=IntPtr.Zero;InitializeProcThreadAttributeList(IntPtr.Zero,1,0,ref size);
      attributes=Marshal.AllocHGlobal(size);
      if(!InitializeProcThreadAttributeList(attributes,1,0,ref size))throw new Win32Exception(Marshal.GetLastWin32Error());
      attributesReady=true;
      handles=Marshal.AllocHGlobal(IntPtr.Size*3);Marshal.WriteIntPtr(handles,0,input);Marshal.WriteIntPtr(handles,IntPtr.Size,output);Marshal.WriteIntPtr(handles,IntPtr.Size*2,error);
      if(!UpdateProcThreadAttribute(attributes,0,new IntPtr(0x20002),handles,new IntPtr(IntPtr.Size*3),IntPtr.Zero,IntPtr.Zero))throw new Win32Exception(Marshal.GetLastWin32Error());
      var si=new SIEX{startup=new SI{cb=Marshal.SizeOf(typeof(SIEX)),flags=0x100,input=input,output=output,error=error},attributes=attributes};
      var cmd=new StringBuilder(Quote(exe)+" "+string.Join(" ",args.Select(Quote)));
      // Detached console, separate process group, Unicode environment, break away from
      // an inherited job if allowed. Never fall back to a tied-to-Jenkins process silently.
      if(!CreateProcess(exe,cmd,IntPtr.Zero,IntPtr.Zero,true,0x8|0x200|0x400|0x80000|0x1000000,env,directory,ref si,out info))
        throw new Win32Exception(Marshal.GetLastWin32Error());
      return info.pid;
    } finally {
      foreach(var h in new[]{input,output,error,info.process,info.thread})if(h!=IntPtr.Zero && h!=new IntPtr(-1))CloseHandle(h);
      if(env!=IntPtr.Zero)Marshal.FreeHGlobal(env);
      if(attributes!=IntPtr.Zero){if(attributesReady)DeleteProcThreadAttributeList(attributes);Marshal.FreeHGlobal(attributes);}
      if(handles!=IntPtr.Zero)Marshal.FreeHGlobal(handles);
    }
  }
}
'@
}
function Start-BaoTinDetachedProcess([Diagnostics.ProcessStartInfo]$Info,[string]$LogPrefix) {
    $environment=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($entry in $Info.Environment.GetEnumerator()){$environment[$entry.Key]=$entry.Value}
    $processId=[BaoTinDetachedProcess]::Start($Info.FileName,[string[]]@($Info.ArgumentList),$Info.WorkingDirectory,$environment,$LogPrefix+'.out.log',$LogPrefix+'.err.log')
    return [Diagnostics.Process]::GetProcessById($processId)
}
