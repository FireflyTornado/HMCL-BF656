param(
    [string]$SourceLauncher,
    [Parameter(Mandatory = $true)][string]$IconPath,
    [Parameter(Mandatory = $true)][string]$OutputLauncher,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'

# Update only a generated native launcher, before Gradle appends the application JAR.
# API contract: https://learn.microsoft.com/windows/win32/api/winbase/nf-winbase-updateresourcew
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;

public static class BF656IconResources {
    private sealed class Group {
        public int Id;
        public string Name;
        public ushort Language;
    }
    private sealed class Icon {
        public byte[] Header;
        public byte[] Image;
    }
    private delegate bool NameCallback(IntPtr module, IntPtr type, IntPtr name, IntPtr data);
    private delegate bool LanguageCallback(IntPtr module, IntPtr type, IntPtr name, ushort language, IntPtr data);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadLibraryExW(string path, IntPtr file, uint flags);
    [DllImport("kernel32.dll")]
    private static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool EnumResourceNamesW(IntPtr module, IntPtr type, NameCallback callback, IntPtr data);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool EnumResourceLanguagesW(IntPtr module, IntPtr type, IntPtr name, LanguageCallback callback, IntPtr data);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr FindResourceExW(IntPtr module, IntPtr type, IntPtr name, ushort language);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll")]
    private static extern IntPtr LockResource(IntPtr resource);
    [DllImport("kernel32.dll")]
    private static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr BeginUpdateResourceW(string path, bool deleteExisting);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool UpdateResourceW(IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool EndUpdateResourceW(IntPtr update, bool discard);

    private static void Check(bool success) {
        if (!success) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
    private static IntPtr Open(string path) {
        IntPtr module = LoadLibraryExW(path, IntPtr.Zero, 0x22);
        Check(module != IntPtr.Zero);
        return module;
    }
    private static List<Icon> ReadIcons(string path) {
        byte[] bytes = File.ReadAllBytes(path);
        if (bytes.Length < 6 || BitConverter.ToUInt16(bytes, 0) != 0 || BitConverter.ToUInt16(bytes, 2) != 1)
            throw new InvalidDataException("Expected a Windows ICO file.");
        int count = BitConverter.ToUInt16(bytes, 4);
        if (count < 1 || count > 256 || bytes.Length < 6 + 16 * count)
            throw new InvalidDataException("Invalid ICO directory.");
        List<Icon> icons = new List<Icon>();
        for (int i = 0; i < count; i++) {
            int entry = 6 + i * 16;
            uint size = BitConverter.ToUInt32(bytes, entry + 8);
            uint offset = BitConverter.ToUInt32(bytes, entry + 12);
            if (size == 0 || offset < 6 + count * 16 || (long)offset + size > bytes.Length)
                throw new InvalidDataException("ICO image lies outside its file.");
            icons.Add(new Icon { Header = bytes.Skip(entry).Take(12).ToArray(), Image = bytes.Skip((int)offset).Take((int)size).ToArray() });
        }
        return icons;
    }
    private static List<Group> Groups(IntPtr module, out int maximumIconId) {
        int maximum = 0;
        NameCallback iconCallback = delegate(IntPtr m, IntPtr t, IntPtr n, IntPtr d) {
            if ((n.ToInt64() >> 16) == 0) maximum = Math.Max(maximum, n.ToInt32());
            return true;
        };
        if (!EnumResourceNamesW(module, (IntPtr)3, iconCallback, IntPtr.Zero)) {
            int error = Marshal.GetLastWin32Error();
            if (error != 1813) throw new Win32Exception(error);
        }
        List<Group> groups = new List<Group>();
        NameCallback callback = delegate(IntPtr m, IntPtr t, IntPtr n, IntPtr d) {
            int id = (n.ToInt64() >> 16) == 0 ? n.ToInt32() : -1;
            string name = id < 0 ? Marshal.PtrToStringUni(n) : null;
            LanguageCallback languages = delegate(IntPtr lm, IntPtr lt, IntPtr ln, ushort language, IntPtr ld) {
                groups.Add(new Group { Id = id, Name = name, Language = language });
                return true;
            };
            Check(EnumResourceLanguagesW(m, t, n, languages, IntPtr.Zero));
            return true;
        };
        if (!EnumResourceNamesW(module, (IntPtr)14, callback, IntPtr.Zero)) {
            int error = Marshal.GetLastWin32Error();
            if (error != 1813) throw new Win32Exception(error);
        }
        maximumIconId = maximum;
        return groups;
    }
    private static byte[] ReadResource(IntPtr module, int type, IntPtr name, ushort language) {
        IntPtr resource = FindResourceExW(module, (IntPtr)type, name, language);
        Check(resource != IntPtr.Zero);
        int size = checked((int)SizeofResource(module, resource));
        IntPtr data = LockResource(LoadResource(module, resource));
        Check(data != IntPtr.Zero);
        byte[] bytes = new byte[size];
        Marshal.Copy(data, bytes, 0, size);
        return bytes;
    }
    public static void Patch(string launcher, string ico) {
        List<Icon> icons = ReadIcons(ico);
        IntPtr module = Open(launcher);
        int maximum;
        List<Group> groups;
        try { groups = Groups(module, out maximum); } finally { FreeLibrary(module); }
        if (groups.Count == 0) groups.Add(new Group { Id = 1, Language = 0 });
        if (maximum + icons.Count > UInt16.MaxValue) throw new InvalidDataException("No available icon resource IDs.");
        byte[] groupData;
        using (MemoryStream buffer = new MemoryStream()) {
            using (BinaryWriter writer = new BinaryWriter(buffer)) {
                writer.Write((ushort)0); writer.Write((ushort)1); writer.Write((ushort)icons.Count);
                for (int i = 0; i < icons.Count; i++) {
                    writer.Write(icons[i].Header); writer.Write((ushort)(maximum + 1 + i));
                }
                groupData = buffer.ToArray();
            }
        }
        IntPtr update = BeginUpdateResourceW(launcher, false);
        Check(update != IntPtr.Zero);
        try {
            foreach (Group group in groups) {
                for (int i = 0; i < icons.Count; i++) {
                    byte[] image = icons[i].Image;
                    Check(UpdateResourceW(update, (IntPtr)3, (IntPtr)(maximum + 1 + i), group.Language, image, (uint)image.Length));
                }
                IntPtr name = group.Id >= 0 ? (IntPtr)group.Id : Marshal.StringToHGlobalUni(group.Name);
                try { Check(UpdateResourceW(update, (IntPtr)14, name, group.Language, groupData, (uint)groupData.Length)); }
                finally { if (group.Id < 0) Marshal.FreeHGlobal(name); }
            }
            bool committed = EndUpdateResourceW(update, false);
            update = IntPtr.Zero;
            Check(committed);
        } finally { if (update != IntPtr.Zero) EndUpdateResourceW(update, true); }
        Verify(launcher, ico);
    }
    public static void Verify(string launcher, string ico) {
        List<Icon> icons = ReadIcons(ico);
        IntPtr module = Open(launcher);
        try {
            int unused;
            List<Group> groups = Groups(module, out unused);
            if (groups.Count == 0) throw new InvalidDataException("No native icon group was generated.");
            foreach (Group group in groups) {
                IntPtr name = group.Id >= 0 ? (IntPtr)group.Id : Marshal.StringToHGlobalUni(group.Name);
                byte[] data;
                try { data = ReadResource(module, 14, name, group.Language); }
                finally { if (group.Id < 0) Marshal.FreeHGlobal(name); }
                if (data.Length != 6 + 14 * icons.Count || BitConverter.ToUInt16(data, 4) != icons.Count)
                    throw new InvalidDataException("Native icon group count mismatch.");
                for (int i = 0; i < icons.Count; i++) {
                    int entry = 6 + i * 14;
                    int id = BitConverter.ToUInt16(data, entry + 12);
                    if (!data.Skip(entry).Take(12).SequenceEqual(icons[i].Header)
                        || !ReadResource(module, 3, (IntPtr)id, group.Language).SequenceEqual(icons[i].Image))
                        throw new InvalidDataException("Native icon image mismatch.");
                }
            }
        } finally { FreeLibrary(module); }
    }
}
'@

$outputPath = [System.IO.Path]::GetFullPath($OutputLauncher)
$resolvedIcon = (Resolve-Path -LiteralPath $IconPath).Path
if ($VerifyOnly) {
    [BF656IconResources]::Verify($outputPath, $resolvedIcon)
    Write-Output 'Verified all native icon groups and image data.'
} else {
    if ([string]::IsNullOrWhiteSpace($SourceLauncher)) { throw 'SourceLauncher is required.' }
    $sourcePath = (Resolve-Path -LiteralPath $SourceLauncher).Path
    if ($sourcePath -eq $outputPath) { throw 'Refusing to modify the original native launcher.' }
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputPath)) | Out-Null
    Copy-Item -LiteralPath $sourcePath -Destination $outputPath -Force
    [BF656IconResources]::Patch($outputPath, $resolvedIcon)
    Write-Output 'Generated and verified BF656 native launcher icons.'
}
