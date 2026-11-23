$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$bundle = Join-Path $projectRoot 'libs/tdlib'
$revision = 'b47cd822037a4c38916f0b78f57298f10a88821e'
$nativePath = 'src/main/libs/30.0.16248370/android-24'
$opensslPath = 'openssl/30.0.16248370/android-24'
function Invoke-Git {
    & git @args
    if ($LASTEXITCODE -ne 0) { throw 'TDLib setup failed. Check the Git error above.' }
}
if (!(Test-Path (Join-Path $bundle '.git'))) {
    $previousSmudge = $env:GIT_LFS_SKIP_SMUDGE
    try {
        $env:GIT_LFS_SKIP_SMUDGE = '1'
        Invoke-Git init $bundle
        Invoke-Git -C $bundle remote add origin https://github.com/TGX-Android/tdlib.git
        Invoke-Git -C $bundle fetch --depth 1 origin $revision
        Invoke-Git -C $bundle checkout --detach $revision
    } finally {
        $env:GIT_LFS_SKIP_SMUDGE = $previousSmudge
    }
}
$actualRevision = & git -C $bundle rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actualRevision -ne $revision) {
    throw "Expected TDLib revision $revision. Keep Java and native binaries from the same revision."
}
Invoke-Git -C $bundle lfs pull "--include=$nativePath/**,$opensslPath/**"
foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
    $destination = Join-Path $bundle "jniLibs/$abi"
    New-Item -ItemType Directory -Force $destination | Out-Null
    $sources = @("$nativePath/$abi/libtdjni.so", "$opensslPath/$abi/lib/libsslx.so", "$opensslPath/$abi/lib/libcryptox.so")
    foreach ($source in $sources) {
        $file = Join-Path $bundle $source
        $stream = [IO.File]::OpenRead($file)
        try {
            $magic = New-Object byte[] 4
            [void]$stream.Read($magic, 0, 4)
            if ([BitConverter]::ToString($magic) -ne '7F-45-4C-46') { throw "Not an ELF binary: $source. Run Git LFS pull." }
        } finally { $stream.Dispose() }
        Copy-Item -LiteralPath $file -Destination $destination -Force
    }
}
& (Join-Path $PSScriptRoot 'setup-cpp-runtime.ps1')
Write-Host 'TDLib ready for all four Android ABIs, including the C++ runtime.'
