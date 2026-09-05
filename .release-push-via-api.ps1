param(
    [string]$Repository = "SaltyFishOTL/ChatChatBar",
    [string]$Branch = "master",
    [string[]]$Commits = @(
        "258baa7e9d19899a3cc0ea8b014c4ea968536e38",
        "c953f2195044e492eaa94d2dc74e88f20b0fba41"
    )
)

$ErrorActionPreference = "Stop"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Invoke-ProcessText {
    param(
        [string]$FileName,
        [string[]]$Arguments,
        [AllowNull()][string]$InputText = $null
    )
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FileName
    $startInfo.WorkingDirectory = (Get-Location).Path
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = $utf8NoBom
    $startInfo.StandardErrorEncoding = $utf8NoBom
    if ($null -ne $InputText) {
        $startInfo.RedirectStandardInput = $true
    }
    $startInfo.Arguments = ($Arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if ($null -ne $InputText) {
        $inputBytes = $utf8NoBom.GetBytes($InputText)
        $process.StandardInput.BaseStream.Write($inputBytes, 0, $inputBytes.Length)
        $process.StandardInput.BaseStream.Close()
    }
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        throw "$FileName failed ($($process.ExitCode)): $stderr"
    }
    return $stdout
}

function Get-GitBlobBytes {
    param([string]$ObjectSpec)
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "git"
    $startInfo.WorkingDirectory = (Get-Location).Path
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardErrorEncoding = $utf8NoBom
    $startInfo.Arguments = 'cat-file blob "' + $ObjectSpec.Replace('"', '\"') + '"'
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $memory = [System.IO.MemoryStream]::new()
    $copyTask = $process.StandardOutput.BaseStream.CopyToAsync($memory)
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    [void]$copyTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        throw "git cat-file failed ($($process.ExitCode)): $stderr"
    }
    return ,$memory.ToArray()
}

function Invoke-GhApi {
    param(
        [string]$Method,
        [string]$Endpoint,
        [AllowNull()]$Body = $null
    )
    $arguments = [System.Collections.Generic.List[string]]::new()
    foreach ($argument in @("api", "--method", $Method, $Endpoint)) {
        [void]$arguments.Add($argument)
    }
    $inputJson = $null
    if ($null -ne $Body) {
        [void]$arguments.Add("--input")
        [void]$arguments.Add("-")
        $inputJson = $Body | ConvertTo-Json -Depth 100 -Compress
    }
    $output = Invoke-ProcessText "gh" $arguments.ToArray() $inputJson
    return $output | ConvertFrom-Json
}

function Get-GitText {
    param([string[]]$Arguments)
    return (Invoke-ProcessText "git" $Arguments).TrimEnd("`r", "`n")
}

$remoteRef = Invoke-GhApi "GET" "repos/$Repository/git/ref/heads/$Branch"
$remoteParent = [string]$remoteRef.object.sha
$originalHead = Get-GitText @("rev-parse", "HEAD")
if ($originalHead -ne $Commits[-1]) {
    throw "HEAD $originalHead does not match final requested commit $($Commits[-1])"
}

foreach ($commit in $Commits) {
    $localParent = Get-GitText @("rev-parse", "$commit^")
    $remoteParentCommit = Invoke-GhApi "GET" "repos/$Repository/git/commits/$remoteParent"
    $localParentTree = Get-GitText @("rev-parse", "$localParent^{tree}")
    if ([string]$remoteParentCommit.tree.sha -ne $localParentTree) {
        throw "Remote parent tree $($remoteParentCommit.tree.sha) does not match local parent tree $localParentTree for $commit"
    }
    $entries = [System.Collections.Generic.List[object]]::new()
    $changes = Get-GitText @("diff-tree", "--no-commit-id", "--name-status", "-r", $commit)
    foreach ($line in ($changes -split "`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line.TrimEnd("`r") -split "`t"
        $status = $parts[0]
        if ($status.StartsWith("D")) {
            $entries.Add([ordered]@{ path = $parts[1]; mode = "100644"; type = "blob"; sha = $null })
            continue
        }
        if ($status.StartsWith("R")) {
            $entries.Add([ordered]@{ path = $parts[1]; mode = "100644"; type = "blob"; sha = $null })
            $path = $parts[2]
        } else {
            $path = $parts[1]
        }
        $treeLine = Get-GitText @("ls-tree", $commit, "--", $path)
        if ($treeLine -notmatch '^(\d+)\s+(\w+)\s+([0-9a-f]+)\t') {
            throw "Cannot parse tree entry for $path"
        }
        $mode = $Matches[1]
        $type = $Matches[2]
        $localBlobSha = $Matches[3]
        if ($type -ne "blob") {
            throw "Unsupported tree entry type $type for $path"
        }
        $bytes = Get-GitBlobBytes "$commit`:$path"
        $blob = Invoke-GhApi "POST" "repos/$Repository/git/blobs" ([ordered]@{
            content = [Convert]::ToBase64String($bytes)
            encoding = "base64"
        })
        if ([string]$blob.sha -ne $localBlobSha) {
            throw "Blob SHA mismatch for ${path}: local=$localBlobSha remote=$($blob.sha)"
        }
        $entries.Add([ordered]@{ path = $path; mode = $mode; type = "blob"; sha = [string]$blob.sha })
    }

    $tree = Invoke-GhApi "POST" "repos/$Repository/git/trees" ([ordered]@{
        base_tree = [string]$remoteParentCommit.tree.sha
        tree = $entries.ToArray()
    })
    $localTree = Get-GitText @("rev-parse", "$commit^{tree}")
    if ([string]$tree.sha -ne $localTree) {
        throw "Tree SHA mismatch for ${commit}: local=$localTree remote=$($tree.sha)"
    }

    $message = Get-GitText @("show", "-s", "--format=%B", $commit)
    $createdCommit = Invoke-GhApi "POST" "repos/$Repository/git/commits" ([ordered]@{
        message = $message
        tree = [string]$tree.sha
        parents = @($remoteParent)
        author = [ordered]@{
            name = Get-GitText @("show", "-s", "--format=%an", $commit)
            email = Get-GitText @("show", "-s", "--format=%ae", $commit)
            date = Get-GitText @("show", "-s", "--format=%aI", $commit)
        }
        committer = [ordered]@{
            name = Get-GitText @("show", "-s", "--format=%cn", $commit)
            email = Get-GitText @("show", "-s", "--format=%ce", $commit)
            date = Get-GitText @("show", "-s", "--format=%cI", $commit)
        }
    })
    $authorName = Get-GitText @("show", "-s", "--format=%an", $commit)
    $authorEmail = Get-GitText @("show", "-s", "--format=%ae", $commit)
    $authorEpoch = Get-GitText @("show", "-s", "--format=%at", $commit)
    $committerName = Get-GitText @("show", "-s", "--format=%cn", $commit)
    $committerEmail = Get-GitText @("show", "-s", "--format=%ce", $commit)
    $committerEpoch = Get-GitText @("show", "-s", "--format=%ct", $commit)
    $normalizedCommitBody = @(
        "tree $($tree.sha)",
        "parent $remoteParent",
        "author $authorName <$authorEmail> $authorEpoch +0000",
        "committer $committerName <$committerEmail> $committerEpoch +0000",
        "",
        $message
    ) -join "`n"
    $normalizedCommitBody += "`n"
    $normalizedLocalSha = (Invoke-ProcessText "git" @("hash-object", "-t", "commit", "-w", "--stdin") $normalizedCommitBody).Trim()
    if ($normalizedLocalSha -ne [string]$createdCommit.sha) {
        $authorOriginalOffset = (Get-GitText @("show", "-s", "--format=%ai", $commit) -split " ")[-1]
        $committerOriginalOffset = (Get-GitText @("show", "-s", "--format=%ci", $commit) -split " ")[-1]
        $matchedSha = $null
        foreach ($authorOffset in @("+0000", $authorOriginalOffset) | Select-Object -Unique) {
            foreach ($committerOffset in @("+0000", $committerOriginalOffset) | Select-Object -Unique) {
                foreach ($encodingHeader in @("", "encoding UTF-8", "encoding utf-8")) {
                    foreach ($trailingNewlines in @(0, 1, 2)) {
                        $candidateLines = [System.Collections.Generic.List[string]]::new()
                        $candidateLines.Add("tree $($tree.sha)")
                        $candidateLines.Add("parent $remoteParent")
                        $candidateLines.Add("author $authorName <$authorEmail> $authorEpoch $authorOffset")
                        $candidateLines.Add("committer $committerName <$committerEmail> $committerEpoch $committerOffset")
                        if ($encodingHeader.Length -gt 0) { $candidateLines.Add($encodingHeader) }
                        $candidateLines.Add("")
                        $candidateLines.Add($message)
                        $candidateBody = $candidateLines.ToArray() -join "`n"
                        $candidateBody += "`n" * $trailingNewlines
                        $candidateSha = (Invoke-ProcessText "git" @("hash-object", "--literally", "-t", "commit", "-w", "--stdin") $candidateBody).Trim()
                        if ($candidateSha -eq [string]$createdCommit.sha) {
                            $matchedSha = $candidateSha
                            break
                        }
                    }
                    if ($null -ne $matchedSha) { break }
                }
                if ($null -ne $matchedSha) { break }
            }
            if ($null -ne $matchedSha) { break }
        }
        if ($null -eq $matchedSha) {
            Write-Output "GitHub normalized commit metadata: local=$commit remote=$($createdCommit.sha)"
        } else {
            $normalizedLocalSha = $matchedSha
        }
    }
    Write-Output "Mapped $commit to remote commit $($createdCommit.sha) with tree $($tree.sha)"
    $remoteParent = [string]$createdCommit.sha
}

[void](Invoke-GhApi "PATCH" "repos/$Repository/git/refs/heads/$Branch" ([ordered]@{
    sha = $remoteParent
    force = $false
}))
Write-Output "Updated $Branch to $remoteParent"
