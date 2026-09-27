<#
.SYNOPSIS
  Keep this fork's main mirrored to upstream and scaffold feature branches.

.DESCRIPTION
  Fork helper for Hoshi-Reader-Android. The personal fork (origin) tracks
  HuangAntimony/Hoshi-Reader-Android (upstream). Local main stays a pure
  mirror; custom work lives on codex/* or feat/* branches.

  Modes:
    -SyncMain                 Fetch upstream and fast-forward main to upstream/main.
    -NewFeature <name>        Sync main, then create and check out <name> from main.
    -RebaseCurrent            After main is fresh, rebase the branch that was
                              checked out when the script started onto main.

  -NewFeature implies -SyncMain. Combine -NewFeature with -Push to also run
  `git push -u origin <name>`. Feature-branch pushes elsewhere in this repo
  should use --force-with-lease after a rebase; main is only ever
  fast-forwarded, never rebased.

.EXAMPLE
  .\tools\sync-fork.ps1 -SyncMain

.EXAMPLE
  .\tools\sync-fork.ps1 -NewFeature codex/my-feature

.EXAMPLE
  .\tools\sync-fork.ps1 -SyncMain -RebaseCurrent

.EXAMPLE
  .\tools\sync-fork.ps1 -SyncMain -DryRun
#>
[CmdletBinding(DefaultParameterSetName = 'Sync')]
param(
  [Parameter(ParameterSetName = 'Sync')]
  [switch]$SyncMain,

  [Parameter(ParameterSetName = 'New', Mandatory = $true)]
  [string]$NewFeature,

  [switch]$RebaseCurrent,

  [switch]$Push,

  [switch]$DryRun,

  [switch]$AllowDirty,

  [string]$OriginRemote = 'origin',

  [string]$UpstreamRemote = 'upstream',

  [string]$UpstreamUrl = 'https://github.com/HuangAntimony/Hoshi-Reader-Android.git',

  [string]$MainBranch = 'main'
)

$ErrorActionPreference = 'Stop'

function Invoke-ReadGit {
  param([string[]]$GitArgs)
  & git @GitArgs
  if ($LASTEXITCODE -ne 0) {
    throw "git $($GitArgs -join ' ') failed with exit code $LASTEXITCODE."
  }
}

function Invoke-MutatingGit {
  param([string[]]$GitArgs)
  $display = "git $($GitArgs -join ' ')"
  if ($DryRun) {
    Write-Host "[DryRun] $display"
    return
  }
  Write-Host "+ $display"
  & git @GitArgs
  if ($LASTEXITCODE -ne 0) {
    throw "$display failed with exit code $LASTEXITCODE."
  }
}

function Test-WorkingTreeClean {
  # Only tracked modifications block branch moves; untracked files survive
  # checkout/reset and would otherwise make this helper unusable right after
  # adding any new file.
  $porcelain = (& git status --porcelain --untracked-files=no) -join "`n"
  return [string]::IsNullOrWhiteSpace($porcelain)
}

function Test-RefExists {
  param([string]$Ref)
  & git show-ref --verify --quiet "refs/heads/$Ref" 2>$null
  return $LASTEXITCODE -eq 0
}

function Test-RemoteBranchExists {
  param([string]$Remote, [string]$Branch)
  $out = & git ls-remote --heads $Remote $Branch 2>$null
  return (-not [string]::IsNullOrWhiteSpace(($out | Out-String)))
}

if (-not $SyncMain -and [string]::IsNullOrWhiteSpace($NewFeature) -and -not $RebaseCurrent) {
  throw 'Nothing to do. Pass -SyncMain, -NewFeature <name>, or -RebaseCurrent (see Get-Help ./tools/sync-fork.ps1).'
}

if (-not [string]::IsNullOrWhiteSpace($NewFeature)) {
  $SyncMain = $true
  if ($NewFeature -notmatch '^(codex|feat)/[a-z0-9][a-z0-9._/-]*$') {
    throw "Invalid branch name '$NewFeature'. Use codex/<name> or feat/<name> with lowercase letters, digits, '.', '_', '/' or '-'."
  }
}

# 1. Sanity: inside a git repo.
Invoke-ReadGit @('rev-parse', '--show-toplevel') | Out-Null

# 2. Ensure upstream remote exists and points at the expected URL.
$existingUpstreamUrl = ''
try {
  $existingUpstreamUrl = ((& git remote get-url $UpstreamRemote 2>$null) | Out-String).Trim()
} catch {
  $existingUpstreamUrl = ''
}
if ([string]::IsNullOrWhiteSpace($existingUpstreamUrl)) {
  Invoke-MutatingGit @('remote', 'add', $UpstreamRemote, $UpstreamUrl)
} elseif ($existingUpstreamUrl -ne $UpstreamUrl) {
  Write-Warning "Remote '$UpstreamRemote' points at '$existingUpstreamUrl' (expected '$UpstreamUrl'). Continuing with the existing URL."
}

# 3. Fetch before reading any refs.
Invoke-MutatingGit @('fetch', $UpstreamRemote)
if (-not $DryRun) {
  Invoke-ReadGit @('fetch', $UpstreamRemote, '--tags')
}

$originBranch = "$OriginRemote/$MainBranch"
$upstreamBranch = "$UpstreamRemote/$MainBranch"

foreach ($ref in @($upstreamBranch, $originBranch)) {
  & git show-ref --verify --quiet "refs/remotes/$ref" 2>$null
  if ($LASTEXITCODE -ne 0) {
    throw "Expected remote ref '$ref' not found. Run 'git fetch $UpstreamRemote' / 'git fetch $OriginRemote' and retry."
  }
}

# 4. Refuse to move branches with uncommitted changes unless explicitly allowed.
if (-not (Test-WorkingTreeClean)) {
  if (-not $AllowDirty) {
    throw 'Working tree has uncommitted changes. Commit, stash, or re-run with -AllowDirty.'
  }
  Write-Warning 'Continuing with a dirty working tree because -AllowDirty was passed.'
}

$startingBranch = ((& git branch --show-current) | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($startingBranch)) {
  throw 'Detached HEAD is not supported by this helper. Check out a branch first.'
}

# 5. Sync main: fast-forward only, never rebase a published main.
if ($SyncMain) {
  Invoke-MutatingGit @('checkout', $MainBranch)

  if (-not $DryRun) {
    & git merge-base --is-ancestor $MainBranch $upstreamBranch 2>$null
    if ($LASTEXITCODE -ne 0) {
      throw ("Local '{0}' has commits not reachable from '{1}'. " -f $MainBranch, $upstreamBranch) +
        "Move that work to a feature branch before mirroring upstream."
    }
  }

  $localMain = ''
  $upstreamMain = ''
  $originMain = ''
  if (-not $DryRun) {
    $localMain = ((& git rev-parse $MainBranch) | Out-String).Trim()
    $upstreamMain = ((& git rev-parse $upstreamBranch) | Out-String).Trim()
    $originMain = ((& git rev-parse $originBranch) | Out-String).Trim()
  }

  if ($DryRun) {
    Invoke-MutatingGit @('reset', '--hard', $upstreamBranch)
  } elseif ($localMain -ne $upstreamMain) {
    Invoke-MutatingGit @('reset', '--hard', $upstreamBranch)
  } else {
    Write-Host "main is already at $upstreamBranch ($localMain)."
  }

  if ($DryRun) {
    Invoke-MutatingGit @('push', $OriginRemote, $MainBranch)
  } elseif ($originMain -ne $upstreamMain) {
    Invoke-MutatingGit @('push', $OriginRemote, $MainBranch)
  } else {
    Write-Host "$originBranch is already in sync; nothing to push."
  }
}

# 6. Scaffold a new feature branch from the fresh main.
if (-not [string]::IsNullOrWhiteSpace($NewFeature)) {
  if (-not $DryRun) {
    if (Test-RefExists $NewFeature) {
      throw "Local branch '$NewFeature' already exists."
    }
    if (Test-RemoteBranchExists $OriginRemote $NewFeature) {
      throw "Branch '$NewFeature' already exists on '$OriginRemote'."
    }
  }
  Invoke-MutatingGit @('checkout', '-b', $NewFeature, $MainBranch)
  if ($Push) {
    Invoke-MutatingGit @('push', '-u', $OriginRemote, $NewFeature)
  } else {
    Write-Host "Created '$NewFeature'. Push later with: git push -u $OriginRemote $NewFeature"
  }
  $startingBranch = $NewFeature
}

# 7. Optionally rebase the original branch onto the fresh main.
if ($RebaseCurrent -and [string]::IsNullOrWhiteSpace($NewFeature)) {
  if ($startingBranch -ne $MainBranch) {
    Invoke-MutatingGit @('checkout', $startingBranch)
    try {
      Invoke-MutatingGit @('rebase', $MainBranch)
    } catch {
      Write-Warning ("Rebase of '{0}' onto '{1}' stopped with conflicts." -f $startingBranch, $MainBranch)
      Write-Warning "Inspect with 'git status', resolve, then 'git rebase --continue' or 'git rebase --abort'."
      throw
    }
    Write-Host "Rebased '$startingBranch' onto '$MainBranch'. Push with: git push --force-with-lease"
  } else {
    Write-Host 'Already on main; nothing to rebase.'
  }
}

# 8. Refresh submodules so the working tree matches the synced refs.
Invoke-MutatingGit @('submodule', 'update', '--init', '--recursive')

Write-Host 'Done.'
