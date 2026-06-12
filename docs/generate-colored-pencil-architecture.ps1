Add-Type -AssemblyName System.Drawing

$source = Join-Path $PSScriptRoot "heteromesh-architecture-colored-pencil-style.png"
$output = Join-Path $PSScriptRoot "heteromesh-architecture-colored-pencil.png"

if (-not (Test-Path $source)) {
    throw "Missing style source image: $source"
}

$base = [System.Drawing.Image]::FromFile($source)
$scale = 1.5
$width = [int]($base.Width * $scale)
$height = [int]($base.Height * $scale)

$bitmap = [System.Drawing.Bitmap]::new($width, $height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$graphics.DrawImage($base, 0, 0, $width, $height)

function C($hex, $alpha = 255) {
    $c = [System.Drawing.ColorTranslator]::FromHtml($hex)
    return [System.Drawing.Color]::FromArgb($alpha, $c.R, $c.G, $c.B)
}

function S($value) {
    return [int]([math]::Round($value * $script:scale))
}

function FontOf($size, $style = [System.Drawing.FontStyle]::Regular, $family = "Microsoft YaHei UI") {
    return [System.Drawing.Font]::new($family, [single](S $size), $style, [System.Drawing.GraphicsUnit]::Pixel)
}

function RectPath($x, $y, $w, $h, $r) {
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $d = $r * 2
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function Draw-RoundRect($x, $y, $w, $h, $fill, $stroke, $alpha = 210, $strokeAlpha = 230, $strokeWidth = 2, $radius = 16) {
    $x = S $x; $y = S $y; $w = S $w; $h = S $h; $radius = S $radius
    $path = RectPath $x $y $w $h $radius
    $brush = [System.Drawing.SolidBrush]::new((C $fill $alpha))
    $pen = [System.Drawing.Pen]::new((C $stroke $strokeAlpha), (S $strokeWidth))
    $graphics.FillPath($brush, $path)
    $graphics.DrawPath($pen, $path)
    $brush.Dispose()
    $pen.Dispose()
    $path.Dispose()
}

function Draw-Text($text, $x, $y, $w, $h, $font, $color = "#1f2937", $align = "Center", $valign = "Center") {
    $brush = [System.Drawing.SolidBrush]::new((C $color 255))
    $format = [System.Drawing.StringFormat]::new()
    $format.Alignment = switch ($align) {
        "Near" { [System.Drawing.StringAlignment]::Near }
        "Far" { [System.Drawing.StringAlignment]::Far }
        default { [System.Drawing.StringAlignment]::Center }
    }
    $format.LineAlignment = switch ($valign) {
        "Near" { [System.Drawing.StringAlignment]::Near }
        "Far" { [System.Drawing.StringAlignment]::Far }
        default { [System.Drawing.StringAlignment]::Center }
    }
    $format.Trimming = [System.Drawing.StringTrimming]::EllipsisWord
    $format.FormatFlags = [System.Drawing.StringFormatFlags]::LineLimit
    $rect = [System.Drawing.RectangleF]::new((S $x), (S $y), (S $w), (S $h))
    $graphics.DrawString($text, $font, $brush, $rect, $format)
    $brush.Dispose()
    $format.Dispose()
}

function Draw-Module($x, $y, $w, $h, $title, $lines, $fill, $stroke) {
    Draw-RoundRect $x $y $w $h $fill $stroke 222 235 2.2 16
    Draw-Text $title ($x + 10) ($y + 9) ($w - 20) 24 (FontOf 18 ([System.Drawing.FontStyle]::Bold)) "#111827"
    $body = ($lines -join "`n")
    Draw-Text $body ($x + 15) ($y + 38) ($w - 30) ($h - 45) (FontOf 13 ([System.Drawing.FontStyle]::Regular) "Consolas") "#334155" "Near" "Near"
}

function Draw-Badge($x, $y, $w, $h, $text, $stroke = "#64748b") {
    Draw-RoundRect $x $y $w $h "#fffdf7" $stroke 236 230 1.8 13
    Draw-Text $text ($x + 8) ($y + 5) ($w - 16) ($h - 10) (FontOf 13 ([System.Drawing.FontStyle]::Bold)) "#334155"
}

function Draw-PencilLine($x1, $y1, $x2, $y2, $color) {
    $random = [System.Random]::new([int]($x1 + $y1 + $x2 + $y2))
    for ($i = 0; $i -lt 3; $i++) {
        $pen = [System.Drawing.Pen]::new((C $color 185), (S 2.0))
        $jx1 = S ($x1 + ($random.NextDouble() - 0.5) * 2.0)
        $jy1 = S ($y1 + ($random.NextDouble() - 0.5) * 2.0)
        $jx2 = S ($x2 + ($random.NextDouble() - 0.5) * 2.0)
        $jy2 = S ($y2 + ($random.NextDouble() - 0.5) * 2.0)
        $graphics.DrawLine($pen, $jx1, $jy1, $jx2, $jy2)
        $pen.Dispose()
    }
}

function Draw-Arrow($x1, $y1, $x2, $y2, $color) {
    Draw-PencilLine $x1 $y1 $x2 $y2 $color
    $angle = [math]::Atan2(($y2 - $y1), ($x2 - $x1))
    $len = 16
    $a1 = $angle + [math]::PI * 0.82
    $a2 = $angle - [math]::PI * 0.82
    Draw-PencilLine $x2 $y2 ($x2 + [math]::Cos($a1) * $len) ($y2 + [math]::Sin($a1) * $len) $color
    Draw-PencilLine $x2 $y2 ($x2 + [math]::Cos($a2) * $len) ($y2 + [math]::Sin($a2) * $len) $color
}

# Soft title card.
Draw-RoundRect 480 16 575 58 "#fffdf7" "#cbd5e1" 226 210 1.8 18
Draw-Text "HeteroMesh Architecture" 495 23 545 32 (FontOf 24 ([System.Drawing.FontStyle]::Bold)) "#0f172a"
Draw-Text "AI / Agent Task Runtime | Java | Netty | Custom RPC" 495 54 545 18 (FontOf 12) "#475569"

# Client area.
Draw-Module 92 190 300 138 "DemoClient / Caller" @(
    "RpcProxy.reference(...)",
    "RpcClient.call(...)",
    "RpcFuture + requestId",
    "receives TASK_RESULT / RESPONSE"
) "#eef6ff" "#2563eb"

# Controller area.
Draw-Module 585 190 310 118 "Netty Entry" @(
    "HeteroMeshServer",
    "MessageDecoder / Encoder",
    "HeartbeatHandler"
) "#f0fdf4" "#16a34a"

Draw-Module 585 330 310 128 "ServerHandler" @(
    "REGISTER",
    "TASK_SUBMIT / TASK_REQUEST",
    "TASK_RESULT / TASK_RESPONSE",
    "pendingClients"
) "#f0fdf4" "#16a34a"

Draw-Module 585 480 310 128 "Control Plane" @(
    "ServiceRegistry",
    "NodeChannelMap",
    "DeadNodeDetector",
    "CircuitBreakerManager"
) "#f0fdf4" "#16a34a"

Draw-Module 615 635 255 112 "Scheduling Core" @(
    "TaskScheduler",
    "LoadBalancer",
    "TaskStore",
    "Timeout + Retry"
) "#f0fdf4" "#16a34a"

# Worker area.
Draw-Module 1124 210 310 104 "Worker Node" @(
    "WorkerClient",
    "connect + REGISTER",
    "heartbeats"
) "#fff7ed" "#ea580c"

Draw-Module 1124 365 310 110 "Task Path" @(
    "ClientHandler",
    "TaskExecutor",
    "DefaultTaskExecutor",
    "TaskPayloadCodec"
) "#fff7ed" "#ea580c"

Draw-Module 1124 523 310 112 "RPC Service Path" @(
    "RpcServiceRegistry",
    "RpcDispatcher",
    "RpcInterceptorChain",
    "published TaskService"
) "#fff7ed" "#ea580c"

# Flow labels and clean arrows.
Draw-Badge 390 268 190 50 "TASK_SUBMIT`nTASK_REQUEST" "#2563eb"
Draw-Badge 925 216 176 46 "schedule + LB" "#16a34a"
Draw-Badge 918 426 190 50 "REGISTER`nHEARTBEAT" "#ea580c"
Draw-Badge 918 565 190 50 "TASK_RESULT`nTASK_RESPONSE" "#ea580c"
Draw-Arrow 392 320 545 320 "#2563eb"
Draw-Arrow 897 279 1078 279 "#16a34a"
Draw-Arrow 1078 435 897 435 "#ea580c"
Draw-Arrow 1078 585 897 585 "#ea580c"

# Common layer.
Draw-Module 68 790 260 100 "Protocol / Transport" @(
    "Message + MessageType",
    "Encoder / Decoder",
    "Exception + Heartbeat"
) "#eef2ff" "#4f46e5"

Draw-Module 360 790 230 100 "Serialization" @(
    "SerializerRouter",
    "JSON / Binary",
    "SPI + YAML"
) "#f0f9ff" "#0284c7"

Draw-Module 620 790 250 100 "RPC Layer" @(
    "RpcProxy / RpcClient",
    "RpcRequest / Response",
    "Dispatcher / Interceptor"
) "#fdf4ff" "#a21caf"

Draw-Module 900 790 245 100 "Task Model" @(
    "TaskRequest / Result",
    "TaskStatus",
    "TaskMetadata / Store"
) "#ecfdf5" "#059669"

Draw-Module 1180 790 288 100 "Load Balancing" @(
    "consistentHash",
    "random / roundRobin",
    "weighted + SPI"
) "#fff7ed" "#ea580c"

Draw-RoundRect 370 934 790 44 "#fffdf7" "#cbd5e1" 230 220 1.4 16
Draw-Text "Main loop: submit task -> schedule worker -> execute -> collect result -> reply to client" 385 944 760 24 (FontOf 14 ([System.Drawing.FontStyle]::Bold)) "#334155"

$bitmap.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()
$base.Dispose()
Write-Host $output
