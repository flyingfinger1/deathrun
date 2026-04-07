# build.ps1 – Downloads Maven once and builds the plugin
# Usage: .\build.ps1

$MavenVersion = "3.9.9"
$MavenDir     = "$PSScriptRoot\.maven\apache-maven-$MavenVersion"
$MavenExe     = "$MavenDir\bin\mvn.cmd"
$ZipUrl       = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MavenVersion/apache-maven-$MavenVersion-bin.zip"
$ZipPath      = "$PSScriptRoot\.maven\maven.zip"

# Download Maven if not already present
if (-not (Test-Path $MavenExe)) {
    Write-Host "Downloading Maven $MavenVersion..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\.maven" | Out-Null

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $ZipUrl -OutFile $ZipPath -UseBasicParsing

    Write-Host "Extracting Maven..." -ForegroundColor Yellow
    Expand-Archive -Path $ZipPath -DestinationPath "$PSScriptRoot\.maven" -Force
    Remove-Item $ZipPath
    Write-Host "Maven ready." -ForegroundColor Green
}

# Run build
Write-Host "Building plugin..." -ForegroundColor Cyan
& $MavenExe -f "$PSScriptRoot\pom.xml" clean package

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "Done! JAR located at: $PSScriptRoot\target\Deathrun.jar" -ForegroundColor Green

    # Deploy to server plugins folder
    $PluginsDir = "$PSScriptRoot\server\plugins"
    if (Test-Path $PluginsDir) {
        Copy-Item "$PSScriptRoot\target\Deathrun.jar" "$PluginsDir\Deathrun.jar" -Force
        Write-Host "Deployed to: $PluginsDir\Deathrun.jar" -ForegroundColor Cyan
    } else {
        Write-Host "Note: '$PluginsDir' not found, skipping deploy." -ForegroundColor Yellow
    }
} else {
    Write-Host "Build failed." -ForegroundColor Red
}
