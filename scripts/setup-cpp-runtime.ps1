$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$hashes = @{
    'arm64-v8a' = '7466ED097631A564ED6BFF0B47768CAA76BF03270D2047B5A65C03F497B824DF'
    'armeabi-v7a' = '2EE592C9BA3372DA027384DEB1070DEF5AE76F3EA2246BD447360FAA73AC22F4'
    'x86' = '865B82BCDA0B5B829614DD7A4C08ABA121E53259C807898B6D0B375414187075'
    'x86_64' = '7918EBD0B8074C312D0C610205D49760B98C008026E6B817CD92103A3E12241B'
}
$ready = $true
foreach ($abi in $hashes.Keys) {
    $runtime = Join-Path $projectRoot "libs/tdlib/jniLibs/$abi/libc++_shared.so"
    if (!(Test-Path $runtime) -or (Get-FileHash $runtime -Algorithm SHA256).Hash -ne $hashes[$abi]) { $ready = $false }
}
if ($ready) { Write-Host 'Verified NDK r30 C++ runtimes already present.'; return }
$cache = Join-Path $projectRoot '.cache'
$archivePath = Join-Path $cache 'android-ndk-r30-windows.zip'
# Official Android repository2-3.xml: NDK 30.0.16248370, used by this TDLib bundle.
$expectedHash = '9bf167a1985fa7d4a036186b78f702eab9179408'
New-Item -ItemType Directory -Force $cache | Out-Null
if (!(Test-Path $archivePath)) {
    Invoke-WebRequest 'https://dl.google.com/android/repository/android-ndk-r30-windows.zip' -OutFile "$archivePath.partial"
    Move-Item -LiteralPath "$archivePath.partial" -Destination $archivePath
}
if ((Get-FileHash $archivePath -Algorithm SHA1).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw "NDK checksum mismatch. Download the official r30 archive again: $archivePath"
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    $triples = @{
        'arm64-v8a' = 'aarch64-linux-android'
        'armeabi-v7a' = 'arm-linux-androideabi'
        'x86' = 'i686-linux-android'
        'x86_64' = 'x86_64-linux-android'
    }
    foreach ($abi in $triples.Keys) {
        $suffix = "/sysroot/usr/lib/$($triples[$abi])/libc++_shared.so"
        $entries = @($archive.Entries | Where-Object { $_.FullName.EndsWith($suffix) })
        if ($entries.Count -ne 1) { throw "Expected one C++ runtime for $abi" }
        $destination = Join-Path $projectRoot "libs/tdlib/jniLibs/$abi"
        New-Item -ItemType Directory -Force $destination | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], (Join-Path $destination 'libc++_shared.so'), $true)
        if ((Get-FileHash (Join-Path $destination 'libc++_shared.so') -Algorithm SHA256).Hash -ne $hashes[$abi]) {
            throw "C++ runtime checksum mismatch for $abi"
        }
    }
    foreach ($notice in ($archive.Entries | Where-Object { $_.FullName -match '^android-ndk-r30/NOTICE(\.toolchain)?$' })) {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($notice, (Join-Path $projectRoot "libs/tdlib/$($notice.Name)"), $true)
    }
} finally { $archive.Dispose() }
Write-Host 'NDK r30 C++ runtime prepared for all four ABIs.'
