# jev-guard demo: assumes the app is already running on 127.0.0.1:8080
#   terminal 1:  mvn spring-boot:run          (mock mode, no key needed)
#   terminal 2:  .\demo.ps1
$Base = "http://127.0.0.1:8080"

Write-Host "== 1. simulate 5 transfer events =="
(Invoke-WebRequest -Method Post "$Base/v1/simulate?count=5").Content.Substring(0, 2000)
Write-Host "`n== 2. metrics (latency measured, not promised) =="
(Invoke-WebRequest "$Base/v1/metrics").Content
Write-Host "`n== 3. latest decisions (addresses masked) =="
(Invoke-WebRequest "$Base/v1/decisions?limit=3").Content.Substring(0, 2000)
