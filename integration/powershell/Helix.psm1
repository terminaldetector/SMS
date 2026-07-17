# SCAFFOLD (not run here). PowerShell interface to the HELIX stack on a PC.
#
# Talks to helix/host/control_server.py over newline-delimited JSON on localhost.
# Start the Python side first (a ControlServer bound to 127.0.0.1:<port>), then:
#
#   Import-Module ./Helix.psm1
#   Connect-Helix -Port 8765
#   Get-HelixNodes
#   Invoke-HelixInfer -Prompt "hello" -Mode single -Skill chat
#
# Production: swap the TcpClient for a Windows named pipe (System.IO.Pipes) if preferred.

$script:HelixClient = $null
$script:HelixStream = $null
$script:HelixReader = $null
$script:HelixWriter = $null

function Connect-Helix {
    param([string]$HostName = "127.0.0.1", [int]$Port = 8765)
    $script:HelixClient = [System.Net.Sockets.TcpClient]::new($HostName, $Port)
    $script:HelixStream = $script:HelixClient.GetStream()
    $script:HelixReader = [System.IO.StreamReader]::new($script:HelixStream)
    $script:HelixWriter = [System.IO.StreamWriter]::new($script:HelixStream)
    $script:HelixWriter.AutoFlush = $true
    Write-Verbose "Connected to HELIX at ${HostName}:${Port}"
}

function Invoke-HelixCommand {
    # Core JSON round-trip: one request line -> one response line.
    param([Parameter(Mandatory)] [hashtable]$Request)
    if (-not $script:HelixWriter) { throw "Not connected. Run Connect-Helix first." }
    $json = $Request | ConvertTo-Json -Compress -Depth 6
    $script:HelixWriter.WriteLine($json)
    $line = $script:HelixReader.ReadLine()
    return $line | ConvertFrom-Json
}

function Test-HelixPing { Invoke-HelixCommand -Request @{ cmd = "ping" } }

function Get-HelixNodes {
    $r = Invoke-HelixCommand -Request @{ cmd = "nodes" }
    if (-not $r.ok) { throw $r.error }
    return $r.agents
}

function Get-HelixStatus { Invoke-HelixCommand -Request @{ cmd = "status" } }

function Add-HelixContext {
    param([Parameter(Mandatory)][string]$Role, [Parameter(Mandatory)][string]$Content)
    $r = Invoke-HelixCommand -Request @{ cmd = "context"; role = $Role; content = $Content }
    if (-not $r.ok) { throw $r.error }
}

function Invoke-HelixInfer {
    param(
        [Parameter(Mandatory)][string]$Prompt,
        [ValidateSet("single", "parallel", "voting", "pipeline")][string]$Mode = "single",
        [string]$Skill,
        [string]$TaskType,
        [string[]]$AgentIds
    )
    $req = @{ cmd = "infer"; prompt = $Prompt; mode = $Mode }
    if ($Skill)    { $req.skill = $Skill }
    if ($TaskType) { $req.task_type = $TaskType }
    if ($AgentIds) { $req.agent_ids = $AgentIds }
    $r = Invoke-HelixCommand -Request $req
    if (-not $r.ok) { throw $r.error }
    return $r.result
}

function Invoke-HelixSuper {
    # Unified mode: one prompt across Track A (agents) + Track B (sharded model).
    param(
        [Parameter(Mandatory)][string]$Prompt,
        [ValidateSet("hierarchical", "ensemble")][string]$Strategy = "hierarchical"
    )
    $r = Invoke-HelixCommand -Request @{ cmd = "super"; prompt = $Prompt; strategy = $Strategy }
    if (-not $r.ok) { throw $r.error }
    return $r.result
}

function Disconnect-Helix {
    if ($script:HelixClient) { $script:HelixClient.Close() }
    $script:HelixClient = $null
}

Export-ModuleMember -Function Connect-Helix, Disconnect-Helix, Test-HelixPing,
    Get-HelixNodes, Get-HelixStatus, Add-HelixContext, Invoke-HelixInfer, Invoke-HelixSuper
