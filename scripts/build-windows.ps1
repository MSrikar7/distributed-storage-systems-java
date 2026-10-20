$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path build/kv,build/metadata,build/load,build/test | Out-Null
$kv = @(Get-ChildItem kv-engine/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -d build/kv $kv
if ($LASTEXITCODE -ne 0) { throw 'KV compilation failed' }
$meta = @(Get-ChildItem metadata-service/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -cp build/kv -d build/metadata $meta
if ($LASTEXITCODE -ne 0) { throw 'Metadata compilation failed' }
$load = @(Get-ChildItem storage-load-tester/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -d build/load $load
if ($LASTEXITCODE -ne 0) { throw 'Load tester compilation failed' }
$tests = @(Get-ChildItem kv-engine/src/test/java,metadata-service/src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -cp 'build/kv;build/metadata' -d build/test $tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
Write-Host 'All V5 components compiled successfully.'
