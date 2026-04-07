# build.ps1 – Lädt Maven einmalig herunter und baut das Plugin
# Aufruf: .\build.ps1

$MavenVersion = "3.9.9"
$MavenDir     = "$PSScriptRoot\.maven\apache-maven-$MavenVersion"
$MavenExe     = "$MavenDir\bin\mvn.cmd"
$ZipUrl       = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MavenVersion/apache-maven-$MavenVersion-bin.zip"
$ZipPath      = "$PSScriptRoot\.maven\maven.zip"

# Maven herunterladen falls noch nicht vorhanden
if (-not (Test-Path $MavenExe)) {
    Write-Host "Maven $MavenVersion wird heruntergeladen..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\.maven" | Out-Null

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $ZipUrl -OutFile $ZipPath -UseBasicParsing

    Write-Host "Entpacke Maven..." -ForegroundColor Yellow
    Expand-Archive -Path $ZipPath -DestinationPath "$PSScriptRoot\.maven" -Force
    Remove-Item $ZipPath
    Write-Host "Maven bereit." -ForegroundColor Green
}

# Build ausführen
Write-Host "Baue Plugin..." -ForegroundColor Cyan
& $MavenExe -f "$PSScriptRoot\pom.xml" clean package

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "Fertig! JAR liegt in: $PSScriptRoot\target\Deathrun.jar" -ForegroundColor Green

    # Deploy in Server-Plugins-Ordner
    $PluginsDir = "$PSScriptRoot\server\plugins"
    if (Test-Path $PluginsDir) {
        Copy-Item "$PSScriptRoot\target\Deathrun.jar" "$PluginsDir\Deathrun.jar" -Force
        Write-Host "Deployed nach: $PluginsDir\Deathrun.jar" -ForegroundColor Cyan
    } else {
        Write-Host "Hinweis: '$PluginsDir' nicht gefunden, kein Deploy." -ForegroundColor Yellow
    }
} else {
    Write-Host "Build fehlgeschlagen." -ForegroundColor Red
}
