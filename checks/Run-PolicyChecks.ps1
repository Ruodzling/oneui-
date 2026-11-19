$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$javac = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\javac.exe'
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/javac'))) {
    Join-Path $env:JAVA_HOME 'bin/javac'
} else {
    (Get-Command javac -ErrorAction Stop).Source
}
$java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java'))) {
    Join-Path $env:JAVA_HOME 'bin/java'
} else {
    (Get-Command java -ErrorAction Stop).Source
}

$kotlinClasses = Join-Path $ProjectRoot 'app/build/tmp/kotlin-classes/debug'
if (-not (Test-Path (Join-Path $kotlinClasses 'io/github/s24u/popupscale/gestures/MiniPlaybackPolicy.class'))) {
    $gradlew = Join-Path $ProjectRoot 'gradlew.bat'
    & $gradlew :app:compileDebugKotlin --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle :app:compileDebugKotlin failed.' }
}

$classes = Join-Path ([IO.Path]::GetTempPath()) 'popupscale-policy-checks'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

& $javac -encoding UTF-8 --release 17 -d $classes (Join-Path $ProjectRoot 'app/src/main/java/io/github/s24u/popupscale/DouyinShareGeometry.java') (Join-Path $PSScriptRoot 'DouyinShareChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Douyin share checks compilation failed.' }
& $java -cp $classes io.github.s24u.popupscale.DouyinShareChecks
if ($LASTEXITCODE -ne 0) { throw 'Douyin share checks failed.' }

$src = Join-Path $ProjectRoot 'app/src/main/java/io/github/s24u/popupscale'
$sources = @(
    'CloneUserPolicy.java',
    'ScalePolicy.java',
    'AppScopePolicy.java',
    'PopupGeometry.java',
    'CommentPanelGeometry.java',
    'AppHookProcessGuard.java',
    'ExternalScalePolicy.java',
    'CommerceHeaderGeometry.java',
    'DouyinHeaderGeometry.java',
    'WechatScalePolicy.java',
    'WechatVoicePolicy.java',
    'SettingsDefaults.java',
    'AppCanvasPolicy.java'
) | ForEach-Object { Join-Path $src $_ }

$checkSources = @(
    'PolicyChecks.java',
    'AppScopeChecks.java',
    'PopupChecks.java',
    'CommentPanelChecks.java',
    'ExternalScaleChecks.java',
    'SettingsDefaultsChecks.java'
) | ForEach-Object { Join-Path $PSScriptRoot $_ }

$cpSep = [IO.Path]::PathSeparator
$policyCp = "$classes$cpSep$kotlinClasses"

& $javac -encoding UTF-8 --release 17 -cp $policyCp -d $classes @sources @checkSources
if ($LASTEXITCODE -ne 0) { throw 'Policy checks compilation failed.' }

foreach ($cls in @(
    'io.github.s24u.popupscale.PolicyChecks',
    'io.github.s24u.popupscale.AppScopeChecks',
    'io.github.s24u.popupscale.PopupChecks',
    'io.github.s24u.popupscale.CommentPanelChecks',
    'io.github.s24u.popupscale.ExternalScaleChecks',
    'io.github.s24u.popupscale.SettingsDefaultsChecks'
)) {
    & $java -cp $policyCp $cls
    if ($LASTEXITCODE -ne 0) { throw "$cls failed." }
}

& $javac -encoding UTF-8 --release 17 -cp $policyCp -d $classes (Join-Path $PSScriptRoot 'CloneUserChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Clone checks compilation failed.' }
& $java -cp $policyCp io.github.s24u.popupscale.CloneUserChecks
if ($LASTEXITCODE -ne 0) { throw 'Clone checks failed.' }
