<#
.SYNOPSIS
  Simulates a plant WAN outage by cutting the edge broker off the "wan" network.

.EXAMPLE
  .\scripts\wan.ps1 down     # cut the link   (edge keeps collecting, queues to disk)
  .\scripts\wan.ps1 up       # restore        (queue auto-flushes to the cloud)
  .\scripts\wan.ps1 status
#>
param([Parameter(Mandatory)][ValidateSet("down", "up", "status")][string]$Action)

$network = "bimba-poc_wan"
$container = "bimba-poc-edge-mqtt-1"

switch ($Action) {
    "down" {
        docker network disconnect $network $container
        Write-Host "WAN DOWN - $container disconnected from $network at $(Get-Date -Format T)" -ForegroundColor Red
    }
    "up" {
        docker network connect $network $container
        Write-Host "WAN UP - $container reconnected to $network at $(Get-Date -Format T)" -ForegroundColor Green
    }
    "status" {
        $nets = docker inspect $container --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}'
        if ($nets -match $network) { Write-Host "WAN is UP ($nets)" -ForegroundColor Green }
        else { Write-Host "WAN is DOWN ($nets)" -ForegroundColor Red }
    }
}
