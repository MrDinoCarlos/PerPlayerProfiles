param(
    [Parameter(Mandatory = $true)][string]$ClientJar,
    [string]$Directory = 'build/reports/profile-ui'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.Drawing

# Render dialog packets captured by smoke-test.cjs with the client's font and widgets.
# This is a layout preview, not a screenshot of a running Minecraft client.
# Minecraft assets stay in memory and are never bundled with the plugin or sources.
$glyphs = @{}
$tints = @{}
$images = [System.Collections.Generic.List[System.Drawing.Bitmap]]::new()
$headSkins = @{}
$palette = @('000000','0000aa','00aa00','00aaaa','aa0000','aa00aa','ffaa00','aaaaaa',
    '555555','5555ff','55ff55','55ffff','ff5555','ff55ff','ffff55','ffffff')
$colorNames = @('black','dark_blue','dark_green','dark_aqua','dark_red','dark_purple','gold','gray',
    'dark_gray','blue','green','aqua','red','light_purple','yellow','white')

function Component-Text($component, [string]$inherited = 'ffffff', [bool]$inheritedBold = $false) {
    if ($null -eq $component) { return '' }
    if ($component -is [string]) { return "§#$inherited" + $component }
    if ($component -is [array]) { return (($component | ForEach-Object { Component-Text $_ $inherited $inheritedBold }) -join '') }
    # The protocol decoder wraps optimized NBT string components in an empty key.
    if ($component -is [System.Collections.IDictionary] -and $component.Contains('')) { return "§#$inherited" + [string]$component[''] }
    $color = $inherited
    if ($component.color) {
        $index = [array]::IndexOf($colorNames, [string]$component.color)
        if ($index -ge 0) { $color = $palette[$index] }
        elseif ([string]$component.color -match '^#[0-9a-fA-F]{6}$') { $color = ([string]$component.color).Substring(1) }
    }
    $bold = if ($null -ne $component.bold) { [bool]$component.bold } else { $inheritedBold }
    $text = "§#$color" + $(if ($bold) { '§l' } else { '' }) + [string]$component.text
    foreach ($child in $component.extra) { $text += Component-Text $child $color $bold }
    return $text
}

function Button-Model($button) {
    if ($null -eq $button) { return $null }
    return @{ action = $(if ($button.action) { 'CLICK' } else { 'NONE' }); label = (Component-Text $button.label); width = $(if ($button.width) { [int]$button.width } else { 150 }) }
}

function Read-JarText($archive, [string]$name) {
    $reader = [System.IO.StreamReader]::new($archive.GetEntry($name).Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Read-JarBitmap($archive, [string]$name) {
    $stream = $archive.GetEntry($name).Open()
    try {
        $original = [System.Drawing.Bitmap]::new($stream)
        try { $copy = [System.Drawing.Bitmap]::new($original) } finally { $original.Dispose() }
        $images.Add($copy)
        return $copy
    } finally { $stream.Dispose() }
}

function Code-Points([string]$text) {
    $points = [System.Collections.Generic.List[int]]::new()
    for ($index = 0; $index -lt $text.Length; $index++) {
        $points.Add([char]::ConvertToUtf32($text, $index))
        if ([char]::IsHighSurrogate($text[$index])) { $index++ }
    }
    return ,$points.ToArray()
}

$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ClientJar))
try {
    $font = (Read-JarText $archive 'assets/minecraft/font/include/default.json') | ConvertFrom-Json
    foreach ($provider in $font.providers | Where-Object { $_.type -eq 'bitmap' }) {
        $imagePath = 'assets/minecraft/textures/' + ($provider.file -replace '^minecraft:', '')
        $bitmap = Read-JarBitmap $archive $imagePath
        $cellWidth = [int]($bitmap.Width / (Code-Points $provider.chars[0]).Count)
        $cellHeight = [int]($bitmap.Height / $provider.chars.Count)
        $height = if ($provider.height) { [int]$provider.height } else { 8 }
        $ratio = $height / $cellHeight
        for ($row = 0; $row -lt $provider.chars.Count; $row++) {
            $rowCharacters = Code-Points $provider.chars[$row]
            for ($column = 0; $column -lt $rowCharacters.Count; $column++) {
                $character = $rowCharacters[$column]
                if ($character -eq 0 -or $glyphs.ContainsKey($character)) { continue }
                $right = -1
                for ($x = 0; $x -lt $cellWidth; $x++) {
                    for ($y = 0; $y -lt $cellHeight; $y++) {
                        if ($bitmap.GetPixel($column * $cellWidth + $x, $row * $cellHeight + $y).A -gt 0) { $right = $x; break }
                    }
                }
                $glyphs[$character] = @{
                    Bitmap = $bitmap; X = $column * $cellWidth; Y = $row * $cellHeight
                    CellWidth = $cellWidth; CellHeight = $cellHeight; Height = $height
                    Width = [int][Math]::Ceiling($cellWidth * $ratio)
                    Advance = [int][Math]::Floor(($right + 1) * $ratio + 0.5) + 1
                    Ascent = [int]$provider.ascent
                }
            }
        }
    }
    $button = Read-JarBitmap $archive 'assets/minecraft/textures/gui/sprites/widget/button.png'
    $disabled = Read-JarBitmap $archive 'assets/minecraft/textures/gui/sprites/widget/button_disabled.png'
} finally { $archive.Dispose() }

function Text-Units([string]$text) {
    $units = [System.Collections.Generic.List[object]]::new()
    $color = 'ffffff'
    $bold = $false
    for ($index = 0; $index -lt $text.Length; $index++) {
        $character = $text[$index]
        if ($character -eq '§' -and $index + 1 -lt $text.Length) {
            $index++
            $code = [char]::ToLowerInvariant($text[$index])
            $colorIndex = '0123456789abcdef'.IndexOf($code)
            if ($colorIndex -ge 0) { $color = $palette[$colorIndex]; $bold = $false }
            elseif ($code -eq '#' -and $index + 6 -lt $text.Length) { $color = $text.Substring($index + 1, 6); $index += 6; $bold = $false }
            elseif ($code -eq 'l') { $bold = $true }
            elseif ($code -eq 'r') { $color = 'ffffff'; $bold = $false }
            continue
        }
        $glyph = $glyphs[[int]$character]
        if ($null -eq $glyph) { $glyph = $glyphs[[int][char]'?'] }
        $width = if ($character -eq ' ') { 4 } else { $glyph.Advance + [int]$bold }
        $units.Add(@{ Character = $character; Glyph = $glyph; Color = $color; Bold = $bold; Width = $width })
    }
    return ,$units.ToArray()
}

function Wrapped-Rows([string]$text, [int]$maxWidth) {
    $rows = [System.Collections.Generic.List[object]]::new()
    $current = [System.Collections.Generic.List[object]]::new()
    $width = 0
    foreach ($unit in (Text-Units $text)) {
        if ($unit.Character -eq "`n") {
            $rows.Add(@{ Units = $current.ToArray() })
            $current.Clear(); $width = 0
            continue
        }
        if ($width + $unit.Width -gt $maxWidth -and $current.Count -gt 0) {
            $space = -1
            for ($index = 0; $index -lt $current.Count; $index++) { if ($current[$index].Character -eq ' ') { $space = $index } }
            if ($space -ge 0) {
                $rows.Add(@{ Units = $current.GetRange(0, $space).ToArray() })
                $current = $current.GetRange($space + 1, $current.Count - $space - 1)
            } else { $rows.Add(@{ Units = $current.ToArray() }); $current.Clear() }
            $width = 0
            foreach ($remaining in $current) { $width += $remaining.Width }
        }
        $current.Add($unit)
        $width += $unit.Width
    }
    $rows.Add(@{ Units = $current.ToArray() })
    return ,$rows.ToArray()
}

function Draw-Units($graphics, $units, [int]$center, [int]$top) {
    $width = 0
    foreach ($unit in $units) { $width += $unit.Width }
    $cursor = [int][Math]::Floor($center - $width / 2)
    foreach ($unit in $units) {
        if ($unit.Character -ne ' ') {
            $key = [string][int]$unit.Character + '/' + $unit.Color
            if (-not $tints.ContainsKey($key)) {
                $glyph = $unit.Glyph
                $colored = [System.Drawing.Bitmap]::new($glyph.CellWidth, $glyph.CellHeight)
                $color = [System.Drawing.ColorTranslator]::FromHtml('#' + $unit.Color)
                for ($x = 0; $x -lt $glyph.CellWidth; $x++) {
                    for ($y = 0; $y -lt $glyph.CellHeight; $y++) {
                        $alpha = $glyph.Bitmap.GetPixel($glyph.X + $x, $glyph.Y + $y).A
                        $colored.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($alpha, $color))
                    }
                }
                $tints[$key] = $colored
                $images.Add($colored)
            }
            $glyph = $unit.Glyph
            $rectangle = [System.Drawing.Rectangle]::new($cursor, $top + 7 - $glyph.Ascent, $glyph.Width, $glyph.Height)
            $graphics.DrawImage($tints[$key], $rectangle)
            if ($unit.Bold) { $rectangle.X++; $graphics.DrawImage($tints[$key], $rectangle) }
        }
        $cursor += $unit.Width
    }
}

function Draw-Button($graphics, $model, [int]$left, [int]$top, [int]$width) {
    $sprite = if ($model.action -eq 'NONE') { $disabled } else { $button }
    # Match the client's nine-slice widget scaling; the border remains three pixels wide.
    $sourceWidths = @(3, ($sprite.Width - 6), 3)
    $targetWidths = @(3, ($width - 6), 3)
    $sourceHeights = @(3, ($sprite.Height - 6), 3)
    $targetHeights = @(3, 14, 3)
    $sourceY = 0; $targetY = $top
    for ($row = 0; $row -lt 3; $row++) {
        $sourceX = 0; $targetX = $left
        for ($column = 0; $column -lt 3; $column++) {
            $destination = [System.Drawing.Rectangle]::new($targetX, $targetY, $targetWidths[$column], $targetHeights[$row])
            $source = [System.Drawing.Rectangle]::new($sourceX, $sourceY, $sourceWidths[$column], $sourceHeights[$row])
            $graphics.DrawImage($sprite, $destination, $source, [System.Drawing.GraphicsUnit]::Pixel)
            $sourceX += $sourceWidths[$column]; $targetX += $targetWidths[$column]
        }
        $sourceY += $sourceHeights[$row]; $targetY += $targetHeights[$row]
    }
    Draw-Units $graphics (Text-Units $model.label) ($left + [int]($width / 2)) ($top + 6)
}

function Rows-Width($rows) {
    $maximum = 0
    foreach ($row in $rows) {
        $width = 0
        foreach ($unit in $row.Units) { $width += $unit.Width }
        $maximum = [Math]::Max($maximum, $width)
    }
    return $maximum
}

function Item-Skin($item) {
    $profile = $item.components['minecraft:profile']
    if ($null -eq $profile) { return $null }
    $property = $profile.properties | Where-Object { $_.name -eq 'textures' } | Select-Object -First 1
    if ($null -eq $property) { return $null }
    $data = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($property.value)) | ConvertFrom-Json
    $url = $data.textures.SKIN.url
    if ($url -notmatch '^https?://textures\.minecraft\.net/texture/[a-fA-F0-9]{32,128}$') { throw 'Unrecognized skin CDN URL.' }
    if ($headSkins.ContainsKey($url)) { return $headSkins[$url] }
    $response = Invoke-WebRequest -Uri ($url -replace '^http:', 'https:') -TimeoutSec 6 -MaximumRedirection 0
    $bitmap = [System.Drawing.Bitmap]::new($response.RawContentStream)
    $headSkins[$url] = $bitmap
    $images.Add($bitmap)
    return $bitmap
}

function Draw-Head($graphics, $item, [int]$center, [int]$top) {
    # Approximate the vanilla GUI item's cube, with all three visible skin faces and hat layers.
    # The actual plugin sends PLAYER_HEAD; Minecraft itself renders the 3D item in-game.
    $skin = Item-Skin $item
    $faces = @(
        @{ U = 8; V = 0; HatU = 40; HatV = 0; Points = @(8,0, 14,3, 2,3) },
        @{ U = 8; V = 8; HatU = 40; HatV = 8; Points = @(2,3, 8,6, 2,11) },
        @{ U = 0; V = 8; HatU = 32; HatV = 8; Points = @(8,6, 14,3, 8,14) }
    )
    foreach ($face in $faces) {
        $points = [System.Drawing.PointF[]]@(
            [System.Drawing.PointF]::new($center - 8 + $face.Points[0], $top + $face.Points[1]),
            [System.Drawing.PointF]::new($center - 8 + $face.Points[2], $top + $face.Points[3]),
            [System.Drawing.PointF]::new($center - 8 + $face.Points[4], $top + $face.Points[5])
        )
        if ($skin) {
            $scale = $skin.Width / 64
            $source = [System.Drawing.RectangleF]::new($face.U * $scale, $face.V * $scale, 8 * $scale, 8 * $scale)
            $graphics.DrawImage($skin, $points, $source, [System.Drawing.GraphicsUnit]::Pixel)
            $hat = [System.Drawing.RectangleF]::new($face.HatU * $scale, $face.HatV * $scale, 8 * $scale, 8 * $scale)
            $graphics.DrawImage($skin, $points, $hat, [System.Drawing.GraphicsUnit]::Pixel)
        }
    }
}

try {
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter '*.dialog.json') {
        $packet = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -AsHashtable
        $view = @{
            title = (Component-Text $packet.title)
            buttons = @($packet.actions | ForEach-Object { Button-Model $_ })
            primary = (Button-Model $(if ($packet.exit_action) { $packet.exit_action } else { $packet.action }))
            textEntry = [bool]$packet.inputs
            initialText = $(if ($packet.inputs) { $packet.inputs[0].initial } else { '' })
            layout = @{ bodyWidth = 304; columns = $(if ($packet.columns) { [int]$packet.columns } else { 2 }); buttonWidth = $(if ($packet.actions -and $packet.actions[0].width) { [int]$packet.actions[0].width } else { 150 }) }
        }
        $canvas = [System.Drawing.Bitmap]::new(320, 240)
        $graphics = [System.Drawing.Graphics]::FromImage($canvas)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $graphics.Clear([System.Drawing.Color]::FromArgb(17, 23, 30))
        Draw-Units $graphics (Text-Units $view.title) 148 13
        Draw-Button $graphics @{ action = 'WARNING'; label = '§e!' } 224 7 20
        $bodies = [System.Collections.Generic.List[object]]::new()
        foreach ($body in $packet.body) {
            if ($body.type -eq 'minecraft:item') {
                $description = $body.description
                $content = if ($description.contents) { $description.contents } else { $description }
                $width = if ($description.width) { [int]$description.width } else { 200 }
                $rows = Wrapped-Rows (Component-Text $content) ($width - 8)
                $textWidth = (Rows-Width $rows) + 8
                $bodies.Add(@{ Rows = $rows; Item = $body.item; IconWidth = [int]$body.width; Width = $textWidth + [int]$body.width + 2; Height = [Math]::Max([int]$body.height, $rows.Count * 9 + 8) })
            } else {
                $width = if ($body.width) { [int]$body.width } else { 200 }
                $rows = Wrapped-Rows (Component-Text $body.contents) ($width - 8)
                $bodies.Add(@{ Rows = $rows; Width = (Rows-Width $rows) + 8; Height = $rows.Count * 9 + 8 })
            }
        }
        $columns = [int]$view.layout.columns
        $gridRows = [int][Math]::Ceiling($view.buttons.Count / $columns)
        $gridHeight = if ($gridRows -gt 0) { $gridRows * 22 - 2 } else { 0 }
        $bodyHeight = [Math]::Max(0, $bodies.Count - 1) * 10
        foreach ($body in $bodies) { $bodyHeight += $body.Height }
        $contentHeight = $bodyHeight + $(if ($gridRows -gt 0) { 10 + $gridHeight } else { 0 }) + $(if ($view.textEntry) { 30 } else { 0 })
        if ($contentHeight -gt 174) { throw "Layout would scroll: $($file.Name) ($contentHeight pixels)." }
        $top = 33 + [int][Math]::Floor((174 - $contentHeight) / 2)
        foreach ($body in $bodies) {
            $left = [int][Math]::Floor((320 - $body.Width) / 2)
            $textCenter = 160
            if ($body.ContainsKey('Item')) {
                Draw-Head $graphics $body.Item ($left + [int]($body.IconWidth / 2)) ($top + [int](($body.Height - 14) / 2))
                $textWidth = $body.Width - $body.IconWidth - 2
                $textCenter = $left + $body.IconWidth + 2 + [int]($textWidth / 2)
            }
            $textTop = $top + [int][Math]::Floor(($body.Height - $body.Rows.Count * 9) / 2)
            foreach ($row in $body.Rows) { Draw-Units $graphics $row.Units $textCenter $textTop; $textTop += 9 }
            $top += $body.Height + 10
        }
        if ($bodies.Count -gt 0) { $top -= 10 }
        if ($view.textEntry) {
            $left = [int]((320 - $view.layout.bodyWidth) / 2)
            $graphics.FillRectangle([System.Drawing.Brushes]::Gray, $left, $top + 10, [int]$view.layout.bodyWidth, 20)
            $graphics.FillRectangle([System.Drawing.Brushes]::Black, $left + 1, $top + 11, [int]$view.layout.bodyWidth - 2, 18)
            Draw-Units $graphics (Text-Units $view.initialText) 160 ($top + 16)
            $top += 30
        }
        $top += 10
        $gridWidth = $columns * $view.layout.buttonWidth + ($columns - 1) * 2
        for ($index = 0; $index -lt $view.buttons.Count; $index++) {
            $rowWidth = $gridWidth
            $remaining = $view.buttons.Count % $columns
            if ($remaining -gt 0 -and $index -ge $view.buttons.Count - $remaining) {
                $rowWidth = $remaining * $view.layout.buttonWidth + ($remaining - 1) * 2
            }
            $left = [int]((320 - $rowWidth) / 2) + ($index % $columns) * ($view.layout.buttonWidth + 2)
            Draw-Button $graphics $view.buttons[$index] $left ($top + [int][Math]::Floor($index / $columns) * 22) $view.layout.buttonWidth
        }
        if ($view.primary) { $width = [int]$view.primary.width; Draw-Button $graphics $view.primary ([int]((320 - $width) / 2)) 213 $width }
        $graphics.Dispose()
        $scaled = [System.Drawing.Bitmap]::new(960, 720)
        $output = [System.Drawing.Graphics]::FromImage($scaled)
        $output.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $output.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $output.DrawImage($canvas, [System.Drawing.Rectangle]::new(0, 0, 960, 720))
        $output.Dispose()
        $path = [System.IO.Path]::ChangeExtension($file.FullName, '.png')
        $scaled.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $scaled.Dispose(); $canvas.Dispose()
    }
    Write-Output "Rendered dialog layout previews in $Directory using vanilla client assets."
} finally { foreach ($image in $images) { $image.Dispose() } }
